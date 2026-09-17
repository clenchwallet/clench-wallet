package net.clench.wallet.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.entity.UtxoMetadataEntity
import net.clench.wallet.data.local.entity.WalletEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real importer + Room tests. All keys/outpoints are public, synthetic and offline. */
@RunWith(AndroidJUnit4::class)
class WalletScopedMetadataTest {
    private fun isolated(block: suspend (ClenchStateBackupManager, ClenchDatabase) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "metadata-fixture-${UUID.randomUUID()}"
        val wrapper = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
        }
        val database = Room.inMemoryDatabaseBuilder(context, ClenchDatabase::class.java).build()
        try {
            block(ClenchStateBackupManager(database, database.walletDao(), database.transactionLabelDao(),
                database.utxoMetadataDao(), SettingsManager(wrapper)), database)
        } finally {
            database.close()
            context.deleteSharedPreferences(preferences)
        }
    }

    @Test fun collidingImportsPreserveBothWalletsForFrozenAndUnfrozenState() = isolated { manager, db ->
        for (frozen in listOf(false, true)) {
            db.utxoMetadataDao().upsert(UtxoMetadataEntity(OUTPOINT, "a", "A intact", !frozen))
            db.walletDao().insert(wallet("a", FIRST, SECOND))
            val result = manager.importStateBackupJson(backup(wallet("b", THIRD, SECOND), frozen).toString())
            assertEquals(1, result.importedUtxoMetadata)
            assertEquals(UtxoMetadataEntity(OUTPOINT, "a", "A intact", !frozen), db.utxoMetadataDao().getByOutpoint("a", OUTPOINT))
            assertEquals(UtxoMetadataEntity(OUTPOINT, "b", "restored", frozen), db.utxoMetadataDao().getByOutpoint("b", OUTPOINT))
            assertEquals(if (frozen) 1 else 0, db.utxoMetadataDao().getFrozenForWallet("b").size)
        }
        db.utxoMetadataDao().deleteForWallet("a")
        db.walletDao().deleteById("a")
        assertNotNull(db.utxoMetadataDao().getByOutpoint("b", OUTPOINT))
    }

    @Test fun sharedReceiveDescriptorDoesNotAliasDifferentChangePolicy() = isolated { manager, db ->
        db.walletDao().insert(wallet("a", FIRST, SECOND))
        db.utxoMetadataDao().upsert(UtxoMetadataEntity(OUTPOINT, "a", "original", true))
        val imported = manager.importStateBackupJson(backup(wallet("b", FIRST, THIRD), false).toString())
        assertEquals(1, imported.importedWallets)
        assertEquals("original", db.utxoMetadataDao().getByOutpoint("a", OUTPOINT)?.label)
        assertEquals(false, db.utxoMetadataDao().getByOutpoint("b", OUTPOINT)?.isFrozen)
    }

    @Test fun identicalWalletViewsRetainTheirExplicitOwnersOnRoundTrip() = isolated { manager, db ->
        for (id in listOf("a", "b")) {
            db.walletDao().insert(wallet(id, FIRST, SECOND))
            db.utxoMetadataDao().upsert(UtxoMetadataEntity(OUTPOINT, id, id, id == "a"))
        }
        val document = manager.exportStateBackupJson()
        for (id in listOf("a", "b")) db.utxoMetadataDao().deleteForWallet(id)
        val result = manager.importStateBackupJson(document)
        assertEquals(2, result.skippedWallets)
        assertEquals(true, db.utxoMetadataDao().getByOutpoint("a", OUTPOINT)?.isFrozen)
        assertEquals(false, db.utxoMetadataDao().getByOutpoint("b", OUTPOINT)?.isFrozen)
        // A fresh offline restore must also preserve both views, not merge them.
        for (id in listOf("a", "b")) {
            db.utxoMetadataDao().deleteForWallet(id)
            db.walletDao().deleteById(id)
        }
        assertEquals(2, manager.importStateBackupJson(document).importedWallets)
        assertEquals(2, db.walletDao().getAll().size)
    }

    @Test fun multipleSourceViewsCannotSilentlyAliasOneExistingWallet() = isolated { manager, db ->
        val existing = wallet("a", FIRST, SECOND)
        db.walletDao().insert(existing)
        db.utxoMetadataDao().upsert(UtxoMetadataEntity(OUTPOINT, "a", "do not replace", true))
        // Foreign ID first would otherwise steal the target required by source A.
        val document = backup(wallet("b", FIRST, SECOND), false)
        document.getJSONArray("wallets").put(walletJson(existing))
        assertTrue(runCatching { manager.importStateBackupJson(document.toString()) }.isFailure)
        assertEquals("do not replace", db.utxoMetadataDao().getByOutpoint("a", OUTPOINT)?.label)
        assertEquals(1, db.walletDao().getAll().size)
    }

    @Test fun repeatedSameWalletImportIntentionallyUpdatesOnlyItsOwnRecord() = isolated { manager, db ->
        manager.importStateBackupJson(backup(wallet("a", FIRST, SECOND), false).toString())
        val result = manager.importStateBackupJson(backup(wallet("a", FIRST, SECOND), true).toString())
        assertEquals(0, result.importedWallets)
        assertEquals(1, db.utxoMetadataDao().getForWallet("a").size)
        assertEquals(true, db.utxoMetadataDao().getByOutpoint("a", OUTPOINT)?.isFrozen)
        db.utxoMetadataDao().upsertLabel("a", OUTPOINT, "changed")
        assertEquals(true, db.utxoMetadataDao().getByOutpoint("a", OUTPOINT)?.isFrozen)
        assertFalse(db.utxoMetadataDao().toggleFrozen("a", OUTPOINT))
        assertEquals("changed", db.utxoMetadataDao().getByOutpoint("a", OUTPOINT)?.label)
    }

    @Test fun invalidOwnerAndConflictingCanonicalOutpointsRejectBeforeWrites() = isolated { manager, db ->
        for (invalid in listOf("missing", "", "../a")) {
            val document = backup(wallet("a", FIRST, SECOND), true)
            document.getJSONArray("utxoMetadata").put(JSONObject().put("walletId", invalid).put("outpoint", OUTPOINT))
            assertTrue(runCatching { manager.importStateBackupJson(document.toString()) }.isFailure)
            assertTrue(db.walletDao().getAll().isEmpty())
        }
        val document = backup(wallet("a", FIRST, SECOND), true)
        document.getJSONArray("utxoMetadata").put(JSONObject().put("walletId", "a").put("outpoint", "${"a".repeat(64)}:00"))
        assertTrue(runCatching { manager.importStateBackupJson(document.toString()) }.isFailure)
        assertTrue(db.walletDao().getAll().isEmpty())
    }

    @Test fun singleRowFieldUpdatesNeverReachAnotherWallet() = isolated { _, db ->
        db.utxoMetadataDao().upsert(UtxoMetadataEntity(OUTPOINT, "a", "A", false))
        db.utxoMetadataDao().upsert(UtxoMetadataEntity(OUTPOINT, "b", "B", false))
        db.utxoMetadataDao().setFrozen("b", OUTPOINT, true)
        db.utxoMetadataDao().setLabel("b", OUTPOINT, "B updated")
        assertEquals(UtxoMetadataEntity(OUTPOINT, "a", "A", false), db.utxoMetadataDao().getByOutpoint("a", OUTPOINT))
        db.utxoMetadataDao().upsertLabel("b", OUTPOINT, "B label only")
        assertEquals(true, db.utxoMetadataDao().getByOutpoint("b", OUTPOINT)?.isFrozen)
        assertFalse(db.utxoMetadataDao().toggleFrozen("b", OUTPOINT))
        assertEquals("B label only", db.utxoMetadataDao().getByOutpoint("b", OUTPOINT)?.label)
        assertEquals(UtxoMetadataEntity(OUTPOINT, "a", "A", false), db.utxoMetadataDao().getByOutpoint("a", OUTPOINT))
    }

    @Test fun ambiguousLocalMappingRollsBackEarlierWalletWrites() = isolated { manager, db ->
        db.walletDao().insert(wallet("local-a", FIRST, SECOND))
        db.walletDao().insert(wallet("local-b", FIRST, SECOND))
        val document = backup(wallet("new-first", THIRD, SECOND), true)
        document.getJSONArray("wallets").put(walletJson(wallet("ambiguous", FIRST, SECOND)))
        assertTrue(runCatching { manager.importStateBackupJson(document.toString()) }.isFailure)
        assertNull(db.walletDao().getById("new-first"))
        assertTrue(db.utxoMetadataDao().getForWallet("new-first").isEmpty())
        assertEquals(2, db.walletDao().getAll().size)
    }

    @Test fun offlineUnknownHistoricalOutpointsAreIsolatedAndCanonicalized() = isolated { manager, db ->
        // No transaction graph, network client or sync is created for restore.
        val document = backup(wallet("a", FIRST, SECOND), true)
        document.getJSONArray("utxoMetadata").getJSONObject(0).put("outpoint", "${"A".repeat(64)}:000")
        manager.importStateBackupJson(document.toString())
        assertNotNull(db.utxoMetadataDao().getByOutpoint("a", OUTPOINT))
        assertTrue(db.utxoMetadataDao().getForWallet("unknown-wallet").isEmpty())
        val invalid = backup(wallet("b", THIRD, SECOND), true)
        invalid.getJSONArray("utxoMetadata").getJSONObject(0).put("outpoint", "${"a".repeat(64)}:4294967296")
        assertTrue(runCatching { manager.importStateBackupJson(invalid.toString()) }.isFailure)
        assertNull(db.walletDao().getById("b"))
    }

    private fun backup(wallet: WalletEntity, frozen: Boolean) = JSONObject()
        .put("format", "clench-state-backup").put("version", 1)
        .put("wallets", JSONArray().put(walletJson(wallet)))
        .put("utxoMetadata", JSONArray().put(JSONObject().put("walletId", wallet.id)
            .put("outpoint", OUTPOINT).put("label", "restored").put("isFrozen", frozen)))

    private fun walletJson(wallet: WalletEntity) = JSONObject().put("id", wallet.id).put("name", wallet.name)
        .put("descriptor", wallet.descriptor).put("changeDescriptor", wallet.changeDescriptor).put("network", "testnet")

    private fun wallet(id: String, receive: String, change: String) = WalletEntity(id, id,
        "wpkh($receive)", "wpkh($change)", true, false, 0L, "testnet")

    private companion object {
        val OUTPOINT = "${"a".repeat(64)}:0"
        const val FIRST = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        const val SECOND = "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"
        const val THIRD = "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
    }
}
