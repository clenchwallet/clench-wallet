package net.clench.wallet.data.repository

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseWalletCacheCleanupTest {

    @Test
    fun `deletes database and every sidecar then verifies absence`() {
        val root = Files.createTempDirectory("clench-passphrase-cache").toFile()
        try {
            val database = root.resolve("wallet_test.db")
            PassphraseWalletCacheCleanup.cacheFiles(database).forEach {
                it.writeBytes(byteArrayOf(1, 2, 3))
            }

            val remaining = PassphraseWalletCacheCleanup.deleteAndFindRemaining(database)

            assertTrue(remaining.isEmpty())
            PassphraseWalletCacheCleanup.cacheFiles(database).forEach {
                assertFalse(it.exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reports a path that cannot be deleted`() {
        val root = Files.createTempDirectory("clench-passphrase-cache").toFile()
        try {
            val database = root.resolve("wallet_test.db").apply { mkdirs() }
            database.resolve("unexpected-child").writeText("prevents directory deletion")

            val remaining = PassphraseWalletCacheCleanup.deleteAndFindRemaining(database)

            assertEquals(listOf(database), remaining)
        } finally {
            root.deleteRecursively()
        }
    }
}
