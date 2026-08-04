package net.clench.wallet.data.repository

import java.io.File

/**
 * Deletes only BDK wallet databases whose UUID is absent from authoritative Room metadata.
 *
 * This runs while sensitive admission is closed on cold start/background cleanup. It removes
 * databases left by a process death or constructor failure between SQLite creation and the Room
 * metadata transaction, including WAL/SHM/journal sidecars. Unrelated databases are untouched.
 */
internal object WalletDatabaseOrphanCleanup {
    private val walletDatabaseName = Regex(
        "^wallet_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.db(?:-(?:wal|shm|journal))?$"
    )

    fun findOrphans(databaseDirectory: File, knownWalletIds: Set<String>): List<File> =
        databaseDirectory.listFiles().orEmpty()
            .filter { file ->
                val walletId = walletDatabaseName.matchEntire(file.name)?.groupValues?.get(1)
                walletId != null && walletId !in knownWalletIds
            }
            .sortedBy(File::getName)

    /** Attempts every orphan deletion and returns anything that could not be removed. */
    fun deleteAndFindRemaining(
        databaseDirectory: File,
        knownWalletIds: Set<String>
    ): List<File> {
        val orphans = findOrphans(databaseDirectory, knownWalletIds)
        orphans.forEach { file ->
            runCatching {
                check(file.parentFile?.canonicalFile == databaseDirectory.canonicalFile)
                check(!file.isDirectory)
                check(!file.exists() || file.delete())
            }
        }
        return orphans.filter(File::exists)
    }
}
