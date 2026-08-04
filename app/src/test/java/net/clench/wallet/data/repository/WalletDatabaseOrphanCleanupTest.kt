package net.clench.wallet.data.repository

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletDatabaseOrphanCleanupTest {
    @Test
    fun `startup scavenger deletes only unknown UUID wallet databases and sidecars`() {
        val directory = Files.createTempDirectory("clench-wallet-db-test").toFile()
        try {
            val known = "11111111-1111-4111-8111-111111111111"
            val orphan = "22222222-2222-4222-8222-222222222222"
            val knownDb = directory.resolve("wallet_$known.db").also { it.writeText("known") }
            val knownWal = directory.resolve("wallet_$known.db-wal").also { it.writeText("known") }
            val orphanDb = directory.resolve("wallet_$orphan.db").also { it.writeText("orphan") }
            val orphanWal = directory.resolve("wallet_$orphan.db-wal").also { it.writeText("orphan") }
            val unrelated = directory.resolve("wallet_not-a-uuid.db").also { it.writeText("keep") }

            assertTrue(
                WalletDatabaseOrphanCleanup.deleteAndFindRemaining(directory, setOf(known))
                    .isEmpty()
            )

            assertTrue(knownDb.exists())
            assertTrue(knownWal.exists())
            assertFalse(orphanDb.exists())
            assertFalse(orphanWal.exists())
            assertTrue(unrelated.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `orphan discovery is deterministic and ignores unrelated files`() {
        val directory = Files.createTempDirectory("clench-wallet-db-test").toFile()
        try {
            val second = directory.resolve(
                "wallet_bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.db-shm"
            ).also { it.writeText("2") }
            val first = directory.resolve(
                "wallet_aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.db"
            ).also { it.writeText("1") }
            directory.resolve("clench_database.db").writeText("room")

            assertEquals(
                listOf(first.name, second.name),
                WalletDatabaseOrphanCleanup.findOrphans(directory, emptySet()).map { it.name }
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
