package net.clench.wallet.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.clench.wallet.data.local.entity.WalletEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class ClenchDatabaseSqlCipherTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteTestDatabase()
    }

    @After
    fun tearDown() {
        deleteTestDatabase()
    }

    @Test
    fun encryptedDatabaseRoundTripsWalletWithProductionOpenHelper() = runBlocking {
        val key = testKey()
        try {
            encryptedDatabase(key).let { database ->
                try {
                    database.walletDao().insert(TEST_WALLET)
                    assertEquals(TEST_WALLET.id, database.walletDao().getById(TEST_WALLET.id)?.id)
                } finally {
                    database.close()
                }
            }

            encryptedDatabase(key).let { database ->
                try {
                    val restored = database.walletDao().getById(TEST_WALLET.id)
                    assertNotNull(restored)
                    assertEquals(TEST_WALLET.name, restored?.name)
                    assertEquals(TEST_WALLET.descriptor, restored?.descriptor)
                } finally {
                    database.close()
                }
            }

            val header = DataInputStream(
                context.getDatabasePath(TEST_DATABASE).inputStream()
            ).use { input ->
                ByteArray(SQLITE_HEADER.size).also { bytes ->
                    input.readFully(bytes)
                }
            }
            assertFalse(header.contentEquals(SQLITE_HEADER))
        } finally {
            key.fill(0)
        }
    }

    @Test
    fun productionPreflightRejectsCorruptionWithoutMutatingWalletFile() = runBlocking {
        val key = testKey()
        try {
            encryptedDatabase(key).let { database ->
                try {
                    database.walletDao().insert(TEST_WALLET)
                    assertEquals(TEST_WALLET.id, database.walletDao().getById(TEST_WALLET.id)?.id)
                    database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
                } finally {
                    database.close()
                }
            }

            val databaseFile = context.getDatabasePath(TEST_DATABASE)
            assertTrue(databaseFile.isFile)
            val originalSize = databaseFile.length()
            RandomAccessFile(databaseFile, "rw").use { file ->
                file.seek(0)
                file.write(ByteArray(CORRUPTED_HEADER_BYTES) { 0xA5.toByte() })
                file.fd.sync()
            }

            val corruptedBytes = databaseFile.readBytes()
            val preflightKey = key.copyOf()

            var preflightRejected = false
            try {
                SqlCipherDatabasePreflight.verifyExisting(databaseFile, preflightKey)
            } catch (_: Exception) {
                preflightRejected = true
            }

            assertTrue("Production preflight accepted a corrupted encrypted database", preflightRejected)
            assertTrue("Production preflight retained its verification key", preflightKey.all { it == 0.toByte() })
            assertTrue("Production preflight deleted the wallet database", databaseFile.isFile)
            assertArrayEquals(
                "Production preflight mutated or recreated the wallet database",
                corruptedBytes,
                databaseFile.readBytes()
            )

            var database: ClenchDatabase? = null
            var roomRejected = false
            try {
                database = encryptedDatabase(key)
                database.walletDao().getById(TEST_WALLET.id)
            } catch (_: Exception) {
                roomRejected = true
            } finally {
                database?.close()
            }

            assertTrue("Room SQLCipher accepted a corrupted encrypted database", roomRejected)
            assertTrue("Room SQLCipher deleted the wallet database", databaseFile.isFile)
            assertEquals(originalSize, databaseFile.length())
            assertArrayEquals(
                "Room SQLCipher mutated or recreated the wallet database",
                corruptedBytes,
                databaseFile.readBytes()
            )
        } finally {
            key.fill(0)
        }
    }

    private fun encryptedDatabase(key: ByteArray): ClenchDatabase =
        Room.databaseBuilder(context, ClenchDatabase::class.java, TEST_DATABASE)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
            .addMigrations(
                ClenchDatabase.MIGRATION_1_2,
                ClenchDatabase.MIGRATION_3_4,
                ClenchDatabase.MIGRATION_4_5,
                ClenchDatabase.MIGRATION_5_6,
                ClenchDatabase.MIGRATION_6_7,
                ClenchDatabase.MIGRATION_7_8,
                ClenchDatabase.MIGRATION_8_9,
                ClenchDatabase.MIGRATION_9_10,
                ClenchDatabase.MIGRATION_10_11,
                ClenchDatabase.MIGRATION_11_12,
                ClenchDatabase.MIGRATION_12_13
            )
            .build()

    private fun deleteTestDatabase() {
        context.deleteDatabase(TEST_DATABASE)
        context.getDatabasePath("$TEST_DATABASE-journal").delete()
        context.getDatabasePath("$TEST_DATABASE-shm").delete()
        context.getDatabasePath("$TEST_DATABASE-wal").delete()
    }

    private fun testKey(): ByteArray = ByteArray(32) { index -> (index * 7 + 11).toByte() }

    private companion object {
        const val TEST_DATABASE = "room-sqlcipher-compatibility"
        const val CORRUPTED_HEADER_BYTES = 64
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val TEST_WALLET = WalletEntity(
            id = "sqlcipher-room-wallet",
            name = "SQLCipher Room compatibility",
            descriptor = "wpkh([d34db33f/84h/1h/0h]tpub-test/0/*)",
            changeDescriptor = "wpkh([d34db33f/84h/1h/0h]tpub-test/1/*)",
            isWatchOnly = true,
            isMultisig = false,
            createdAtEpochMs = 1_700_000_000_000L,
            network = "testnet"
        )
    }
}
