package net.clench.wallet.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.clench.wallet.data.local.entity.UtxoMetadataEntity
import net.clench.wallet.data.backup.ClenchStateBackupManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalletScopedMetadataMigrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everySupportedLegacyRoutePreservesSurvivingRowsAndRestarts() = runBlocking {
        for (version in 3..13) {
            fixture(version) { name, factory ->
                migrateAndCheck(name, factory, version >= 8)
            }
        }
    }

    @Test fun encryptedUpgradeAndRestartPreserveWalletScopedMetadata() = runBlocking {
        System.loadLibrary("sqlcipher")
        fixture(13, encrypted = true) { name, factory -> migrateAndCheck(name, factory, true) }
    }

    @Test fun interruptedMigrationRollsBackDdlAndRowsThenRetriesSuccessfully() = runBlocking {
        for (encrypted in listOf(false, true)) {
            if (encrypted) System.loadLibrary("sqlcipher")
            fixture(13, encrypted) { name, factory ->
                val interruption = object : Migration(13, 14) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        ClenchDatabase.MIGRATION_13_14.migrate(db)
                        error("Synthetic interruption before the Room migration transaction commits")
                    }
                }
                val failed = open(name, factory, interruption)
                try {
                    assertTrue(runCatching { failed.walletDao().getAll() }.isFailure)
                } finally { failed.close() }
                helper(name, 13, factory).use { old ->
                    old.writableDatabase.query("SELECT walletId, label, isFrozen FROM utxo_metadata WHERE outpoint = ?", arrayOf(OUTPOINT)).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("a", it.getString(0))
                        assertEquals("surviving label", it.getString(1))
                        assertEquals(1, it.getInt(2))
                    }
                    old.writableDatabase.query("PRAGMA user_version").use {
                        assertTrue(it.moveToFirst()); assertEquals(13, it.getInt(0))
                    }
                    old.writableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'utxo_metadata_wallet_scoped'").use {
                        assertFalse(it.moveToFirst())
                    }
                }
                migrateAndCheck(name, factory, true)
            }
        }
    }

    @Test fun unsupportedOldSchemasFailClosedWithoutReset() = runBlocking {
        // The application never supplied 2 -> 3. Do not invent a destructive shortcut.
        for (version in listOf(1, 2)) fixture(version) { name, factory ->
            val database = open(name, factory)
            try { assertTrue(runCatching { database.walletDao().getAll() }.isFailure) }
            finally { database.close() }
            helper(name, version, factory).use { old ->
                old.writableDatabase.query("SELECT name FROM wallets WHERE id = 'a'").use {
                    assertTrue(it.moveToFirst()); assertEquals("fixture wallet", it.getString(0))
                }
            }
        }
    }

    private suspend fun migrateAndCheck(name: String, factory: () -> SupportSQLiteOpenHelper.Factory, hasMetadata: Boolean) {
        open(name, factory).let { database ->
            try {
                assertEquals("fixture wallet", database.walletDao().getById("a")?.name)
                if (hasMetadata) assertEquals(UtxoMetadataEntity(OUTPOINT, "a", "surviving label", true),
                    database.utxoMetadataDao().getByOutpoint("a", OUTPOINT))
                database.utxoMetadataDao().upsert(UtxoMetadataEntity(OUTPOINT, "b", "new owner", false))
                if (hasMetadata) assertEquals(true, database.utxoMetadataDao().getByOutpoint("a", OUTPOINT)?.isFrozen)
            } finally { database.close() }
        }
        open(name, factory).let { database ->
            try {
                assertEquals(false, database.utxoMetadataDao().getByOutpoint("b", OUTPOINT)?.isFrozen)
                if (hasMetadata) assertEquals("surviving label", database.utxoMetadataDao().getByOutpoint("a", OUTPOINT)?.label)
                // Exercise the real offline importer after both upgrade and restart,
                // including in the encrypted production-open-helper case.
                val manager = ClenchStateBackupManager(database, database.walletDao(),
                    database.transactionLabelDao(), database.utxoMetadataDao(), SettingsManager(context))
                val document = """{
                    "format":"clench-state-backup", "version":1,
                    "wallets":[{"id":"restored", "network":"testnet",
                      "descriptor":"wpkh(0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798)",
                      "changeDescriptor":"wpkh(02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5)"}],
                    "utxoMetadata":[{"walletId":"restored", "outpoint":"$OUTPOINT", "isFrozen":false}]
                }""".trimIndent()
                assertEquals(1, manager.importStateBackupJson(document).importedUtxoMetadata)
                assertEquals(false, database.utxoMetadataDao().getByOutpoint("restored", OUTPOINT)?.isFrozen)
                if (hasMetadata) assertEquals(true, database.utxoMetadataDao().getByOutpoint("a", OUTPOINT)?.isFrozen)
                database.utxoMetadataDao().setFrozen("b", OUTPOINT, true)
                database.utxoMetadataDao().setLabel("b", OUTPOINT, "B updated")
                database.utxoMetadataDao().deleteForWallet("a")
                assertEquals(UtxoMetadataEntity(OUTPOINT, "b", "B updated", true), database.utxoMetadataDao().getByOutpoint("b", OUTPOINT))
            } finally { database.close() }
        }
    }

    private suspend fun fixture(version: Int, encrypted: Boolean = false,
        block: suspend (String, () -> SupportSQLiteOpenHelper.Factory) -> Unit) {
        val name = "metadata-upgrade-$version-${UUID.randomUUID()}"
        val factory: () -> SupportSQLiteOpenHelper.Factory = if (encrypted) {
            { SupportOpenHelperFactory(ByteArray(32) { (it * 7 + 11).toByte() }) }
        } else { { FrameworkSQLiteOpenHelperFactory() } }
        try {
            helper(name, version, factory, create = true).use { it.writableDatabase }
            block(name, factory)
        } finally { context.deleteDatabase(name) }
    }

    private fun open(name: String, factory: () -> SupportSQLiteOpenHelper.Factory, lastMigration: Migration = ClenchDatabase.MIGRATION_13_14) =
        Room.databaseBuilder(context, ClenchDatabase::class.java, name)
            .openHelperFactory(factory()).addMigrations(
                ClenchDatabase.MIGRATION_1_2, ClenchDatabase.MIGRATION_3_4,
                ClenchDatabase.MIGRATION_4_5, ClenchDatabase.MIGRATION_5_6,
                ClenchDatabase.MIGRATION_6_7, ClenchDatabase.MIGRATION_7_8,
                ClenchDatabase.MIGRATION_8_9, ClenchDatabase.MIGRATION_9_10,
                ClenchDatabase.MIGRATION_10_11, ClenchDatabase.MIGRATION_11_12,
                ClenchDatabase.MIGRATION_12_13, lastMigration).build()

    private fun helper(name: String, version: Int, factory: () -> SupportSQLiteOpenHelper.Factory, create: Boolean = false): SupportSQLiteOpenHelper =
        factory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    check(create)
                    createLegacySchema(db, version)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())

    /** Only schemas12/13 were historically exported. Reduce schema13 according to
     * the actual additive migration history to exercise all supported entry routes. */
    private fun createLegacySchema(db: SupportSQLiteDatabase, version: Int) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = JSONObject(assets.open("net.clench.wallet.data.local.ClenchDatabase/13.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        val introducedTable = mapOf("utxo_metadata" to 8, "transaction_labels" to 9,
            "address_book_entries" to 11, "wallet_keystore_metadata" to 12, "saved_signers" to 13)
        val introducedColumn = mapOf("network" to 4, "preferredHardwareWallet" to 5,
            "hasPassphrase" to 6, "identiconBytes" to 7, "masterFingerprint" to 10,
            "derivationPath" to 10, "importedViaDevice" to 10)
        for (i in 0 until schema.length()) {
            val entity = schema.getJSONObject(i)
            val table = entity.getString("tableName")
            if ((introducedTable[table] ?: 1) > version) continue
            if (table == "wallets") {
                val fields = entity.getJSONArray("fields")
                val definitions = (0 until fields.length()).map { fields.getJSONObject(it) }.filter {
                    (introducedColumn[it.getString("columnName")] ?: 1) <= version
                }.map {
                    "`${it.getString("columnName")}` ${it.getString("affinity")}" + if (it.optBoolean("notNull")) " NOT NULL" else ""
                }
                db.execSQL("CREATE TABLE wallets (${definitions.joinToString()}, PRIMARY KEY(id))")
            } else {
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val indices = entity.optJSONArray("indices") ?: continue
            for (index in 0 until indices.length()) db.execSQL(indices.getJSONObject(index).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val walletFields = mutableListOf("id", "name", "descriptor", "changeDescriptor", "isWatchOnly", "isMultisig", "createdAtEpochMs")
        val walletValues = mutableListOf<Any>("a", "fixture wallet", "public-receive", "public-change", 1, 0, 0L)
        if (version >= 4) { walletFields += "network"; walletValues += "testnet" }
        if (version >= 6) { walletFields += "hasPassphrase"; walletValues += 0 }
        db.execSQL("INSERT INTO wallets (${walletFields.joinToString()}) VALUES (${walletFields.joinToString { "?" }})", walletValues.toTypedArray())
        if (version >= 8) db.execSQL("INSERT INTO utxo_metadata (outpoint,walletId,label,isFrozen) VALUES (?, 'a', 'surviving label', 1)", arrayOf(OUTPOINT))
    }

    private companion object { val OUTPOINT = "${"1".repeat(64)}:0" }
}
