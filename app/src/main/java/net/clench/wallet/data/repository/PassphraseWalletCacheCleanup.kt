package net.clench.wallet.data.repository

import java.io.File

/** Fail-closed deletion policy for public wallet-state caches of passphrase wallets. */
internal object PassphraseWalletCacheCleanup {
    fun cacheFiles(databaseFile: File): List<File> = listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
        File(databaseFile.path + "-journal")
    )

    /** Attempt every deletion and return any path that still exists afterward. */
    fun deleteAndFindRemaining(databaseFile: File): List<File> {
        val files = cacheFiles(databaseFile)
        files.forEach { file ->
            if (file.exists()) file.delete()
        }
        return files.filter(File::exists)
    }
}
