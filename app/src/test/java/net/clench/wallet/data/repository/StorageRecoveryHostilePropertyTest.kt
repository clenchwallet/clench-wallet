package net.clench.wallet.data.repository

import java.io.ByteArrayInputStream
import java.nio.file.Files
import net.clench.wallet.security.readBytesBounded
import net.clench.wallet.verification.VerificationPropertyHarness
import net.clench.wallet.verification.VerificationPropertyHarness.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageRecoveryHostilePropertyTest {
    @Test
    fun `bounded storage imports reject corruption payload expansion`() {
        val limit = 4_096
        val exact = ByteArray(limit) { it.toByte() }
        assertEquals(limit, ByteArrayInputStream(exact).readBytesBounded(limit).size)

        assertThrows(IllegalStateException::class.java) {
            ByteArrayInputStream(exact + 0x01.toByte()).readBytesBounded(limit)
        }
    }

    @Test
    fun `temporary replacement always restores original visibility after interruption`() {
        VerificationPropertyHarness.forAll(seed = 0x5702A6EL) { random, _ ->
            val events = mutableListOf<String>()
            val shouldInterrupt = random.nextBoolean()

            runCatching {
                ReplacementTransactionPolicy.withTemporaryEviction(
                    evict = { events += "evict" },
                    restore = { events += "restore" },
                    build = {
                        events += "build"
                        if (shouldInterrupt) error("simulated process boundary")
                        "replacement"
                    }
                )
            }

            assertEquals("evict", events.first())
            assertEquals("restore", events.last())
            assertEquals(1, events.count { it == "restore" })
        }
    }

    @Test
    fun `quarantine identifiers cannot escape their wallet namespace`() {
        val walletId = "1234567890abcdef"
        val valid = "${walletId.take(12)}-1785110000000"
        WalletStateQuarantinePolicy.validateId(walletId, valid)

        listOf(
            "$valid/../escape",
            "${walletId.take(12)}-1785_110",
            "other-wallet-1785110000000",
            "${walletId.take(12)}-" + "9".repeat(100)
        ).forEach { hostile ->
            assertTrue(runCatching {
                WalletStateQuarantinePolicy.validateId(walletId, hostile)
        }.isFailure)
        }
    }

    @Test
    fun `filesystem transaction rejects hostile recovery identifiers before creating paths`() {
        val root = Files.createTempDirectory("clench-hostile-recovery-id-").toFile()
        try {
            listOf(
                "../escape",
                "wallet/child",
                "",
                "a".repeat(81),
                "wallet-\u0000"
            ).forEach { hostile ->
                assertThrows(IllegalArgumentException::class.java) {
                    WalletStateQuarantineTransaction(
                        originalFiles = emptyList(),
                        quarantineDir = root.resolve("quarantine"),
                        recoveryId = hostile
                    )
                }
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `corrupt wallet files are restored byte for byte after interrupted replacement`() {
        VerificationPropertyHarness.forAll(seed = 0xC022A77L, cases = 64) { random, caseIndex ->
            val root = Files.createTempDirectory("clench-corrupt-state-$caseIndex-").toFile()
            try {
                val database = root.resolve("wallet.db")
                val wal = root.resolve("wallet.db-wal")
                val originalDatabase = random.bytes(random.nextInt(32_768) + 1)
                val originalWal = random.bytes(random.nextInt(8_192) + 1)
                database.writeBytes(originalDatabase)
                wal.writeBytes(originalWal)
                val quarantine = root.resolve("quarantine")
                val transaction = WalletStateQuarantineTransaction(
                    originalFiles = listOf(database, wal),
                    quarantineDir = quarantine,
                    recoveryId = "1234567890ab-${1785110000000L + caseIndex}"
                )

                transaction.quarantineOriginals()
                assertTrue(!database.exists() && !wal.exists())
                assertEquals(2, transaction.preservedFileCount)
                transaction.markReplacementStateStarted()
                database.writeBytes(random.bytes(random.nextInt(4_096) + 1))
                wal.writeBytes(random.bytes(random.nextInt(4_096) + 1))

                transaction.rollback(IllegalStateException("simulated interrupted recovery"))

                assertArrayEquals(originalDatabase, database.readBytes())
                assertArrayEquals(originalWal, wal.readBytes())
                assertTrue(quarantine.listFiles().orEmpty().isEmpty())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `partial quarantine failure restores files already moved`() {
        val root = Files.createTempDirectory("clench-partial-quarantine-").toFile()
        try {
            val database = root.resolve("wallet.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val hostileDirectory = root.resolve("wallet.db-wal").apply { mkdir() }
            val transaction = WalletStateQuarantineTransaction(
                originalFiles = listOf(database, hostileDirectory),
                quarantineDir = root.resolve("quarantine"),
                recoveryId = "1234567890ab-1785110000000"
            )

            assertThrows(IllegalStateException::class.java) {
                try {
                    transaction.quarantineOriginals()
                } catch (failure: Exception) {
                    transaction.rollback(failure)
                    throw failure
                }
            }
            assertArrayEquals(byteArrayOf(1, 2, 3), database.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }
}
