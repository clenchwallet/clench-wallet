package net.clench.wallet.verification.sqlcipherupgrade

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.clench.wallet.BuildConfig
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.SqlCipherDatabasePreflight
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.entity.WalletEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Overlay-only regression. Never included in the production source set. */
private object Fixture {
    const val DB = "clench-sqlcipher-upgrade.db"
    val key get() = ByteArray(32) { (it * 7 + 11).toByte() } // public synthetic fixture only
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val snapshot get() = File(context.filesDir, "clench-sqlcipher-upgrade-snapshot")
    val wallet = WalletEntity(
        id = "public-sqlcipher-upgrade-fixture", name = "COMMITTED IN DATABASE",
        descriptor = "wpkh([d34db33f/84h/1h/0h]tpub-test/0/*)",
        changeDescriptor = "wpkh([d34db33f/84h/1h/0h]tpub-test/1/*)",
        isWatchOnly = true, isMultisig = false, createdAtEpochMs = 0, network = "testnet"
    )
    fun guard() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("clenchDisposableEmulator") == "YES")
        SettingsManager(context).apply {
            setOfflineMode(true)
            setBtcPriceEnabled(false)
            setExternalFeeLookupEnabled(false)
        }
        System.loadLibrary("sqlcipher")
    }
    fun open() = Room.databaseBuilder(context, ClenchDatabase::class.java, DB)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .openHelperFactory(SupportOpenHelperFactory(key))
        .build()
    fun version(db: ClenchDatabase, expected: String) {
        db.openHelper.writableDatabase.query("PRAGMA cipher_version").use {
            assertTrue(it.moveToFirst())
            assertEquals(expected, it.getString(0).substringBefore(' '))
        }
    }
    fun checkpoint(db: ClenchDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }
    fun bytes(file: File): ByteArray {
        check(file.isFile && file.length() in 1..8L * 1024 * 1024)
        return file.readBytes()
    }
    fun assertRows(db: ClenchDatabase) = runBlocking {
        assertEquals(wallet, db.walletDao().getById(wallet.id))
        assertEquals(wallet.copy(id = "committed-wal-row", name = "COMMITTED IN WAL"),
            db.walletDao().getById("committed-wal-row"))
    }
}

@RunWith(AndroidJUnit4::class)
class SqlCipher415WriterTest {
    @Test fun writeEncryptedDatabaseAndCrashStyleWalSnapshot() = runBlocking {
        Fixture.guard()
        check(!Fixture.context.getDatabasePath(Fixture.DB).exists())
        check(!Fixture.snapshot.exists())
        check(Fixture.snapshot.mkdir())
        val db = Fixture.open()
        try {
            Fixture.version(db, "4.15.0")
            db.walletDao().insert(Fixture.wallet)
            Fixture.checkpoint(db)
            val sql = db.openHelper.writableDatabase
            sql.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
            db.walletDao().insert(Fixture.wallet.copy(id = "committed-wal-row", name = "COMMITTED IN WAL"))
            // A single paused writer: snapshot after committed WAL data and while a
            // later transaction is unfinished. No concurrent writes during copying.
            sql.beginTransaction()
            try {
                sql.execSQL("UPDATE wallets SET name=? WHERE id=?", arrayOf("UNCOMMITTED", Fixture.wallet.id))
                val main = Fixture.context.getDatabasePath(Fixture.DB)
                val wal = File(main.path + "-wal")
                assertTrue(wal.length() > 32)
                File(Fixture.snapshot, "database").writeBytes(Fixture.bytes(main))
                File(Fixture.snapshot, "wal").writeBytes(Fixture.bytes(wal))
                assertFalse(Fixture.bytes(main).take(16).toByteArray()
                    .contentEquals("SQLite format 3\u0000".toByteArray()))
                File(Fixture.snapshot, "writer-version").writeText("4.15.0\n")
            } finally {
                sql.endTransaction() // rollback; the frozen snapshot stays unchanged
            }
        } finally { db.close() }
    }
}

@RunWith(AndroidJUnit4::class)
class SqlCipher417ReaderTest {
    @Test fun recoverOldWalAndRejectWrongKeyAndCorruption() {
        Fixture.guard()
        assertEquals("4.15.0\n", File(Fixture.snapshot, "writer-version").readText())
        // First open the original writer database directly after install -r,
        // before restoring the separate crash-style snapshot below.
        val upgraded = Fixture.open()
        try { Fixture.version(upgraded, "4.17.0"); Fixture.assertRows(upgraded) }
        finally { upgraded.close() }
        val mainBytes = Fixture.bytes(File(Fixture.snapshot, "database"))
        val walBytes = Fixture.bytes(File(Fixture.snapshot, "wal"))
        // This named DB belongs only to this fixture; the harness already verified
        // that neither app package existed on the disposable emulator before use.
        Fixture.context.deleteDatabase(Fixture.DB)
        val main = Fixture.context.getDatabasePath(Fixture.DB)
        main.parentFile!!.mkdirs()
        main.writeBytes(mainBytes)
        File(main.path + "-wal").writeBytes(walBytes)

        val wrongKey = Fixture.key.apply { this[0] = (this[0].toInt() xor 1).toByte() }
        var rejected = false
        try { SqlCipherDatabasePreflight.verifyExisting(main, wrongKey) }
        catch (_: Exception) { rejected = true }
        assertTrue("Wrong key must be rejected", rejected)
        assertTrue(wrongKey.all { it == 0.toByte() })
        assertArrayEquals(mainBytes, Fixture.bytes(main))
        assertArrayEquals(walBytes, Fixture.bytes(File(main.path + "-wal")))

        val corrupt = File(Fixture.snapshot, "corrupt-database")
        val corruptBytes = mainBytes.copyOf().apply { fill(0xA5.toByte(), 0, 64) }
        corrupt.writeBytes(corruptBytes)
        rejected = false
        try { SqlCipherDatabasePreflight.verifyExisting(corrupt, Fixture.key) }
        catch (_: Exception) { rejected = true }
        assertTrue("Corrupt copy must be rejected", rejected)
        assertArrayEquals(corruptBytes, Fixture.bytes(corrupt))

        val db = Fixture.open()
        try {
            Fixture.version(db, "4.17.0")
            db.openHelper.writableDatabase.query("SELECT sqlite_version()").use {
                assertTrue(it.moveToFirst()); assertEquals("3.53.3", it.getString(0))
            }
            Fixture.assertRows(db) // Room also validates the old schema identity.
            Fixture.checkpoint(db)
        } finally { db.close() }
        File(Fixture.snapshot, "reader-version").writeText("4.17.0\n")
    }
}

@RunWith(AndroidJUnit4::class)
class SqlCipher417ReopenTest {
    @Test fun reopenAfterCandidateCheckpointInNewProcess() {
        Fixture.guard()
        assertEquals("4.17.0\n", File(Fixture.snapshot, "reader-version").readText())
        val db = Fixture.open()
        try { Fixture.version(db, "4.17.0"); Fixture.assertRows(db) }
        finally { db.close() }
    }
}
