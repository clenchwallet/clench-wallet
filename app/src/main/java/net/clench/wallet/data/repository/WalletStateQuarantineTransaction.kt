package net.clench.wallet.data.repository

import java.io.File

/**
 * Testable filesystem transaction used by extended wallet-state recovery.
 *
 * Original state is moved to no-backup quarantine before replacement state is
 * created. If any later operation fails, generated replacement files are
 * removed and the original bytes are restored in reverse move order.
 */
internal class WalletStateQuarantineTransaction(
    private val originalFiles: List<File>,
    private val quarantineDir: File,
    private val recoveryId: String
) {
    init {
        require(Regex("^[A-Za-z0-9-]{1,80}$").matches(recoveryId)) {
            "Invalid wallet-state recovery identifier"
        }
    }

    private val movedFiles = mutableListOf<Pair<File, File>>()
    private var replacementStateStarted = false

    val preservedFileCount: Int
        get() = movedFiles.size

    fun quarantineOriginals() {
        check(quarantineDir.exists() || quarantineDir.mkdirs()) {
            "Could not create the wallet-state quarantine directory"
        }
        check(quarantineDir.isDirectory) {
            "Wallet-state quarantine path is not a directory"
        }
        originalFiles.filter { it.exists() }.forEachIndexed { index, original ->
            check(original.isFile) { "Wallet state path is not a regular file: ${original.name}" }
            val quarantined = File(quarantineDir, "$recoveryId-$index-${original.name}")
            check(quarantined.canonicalFile.parentFile == quarantineDir.canonicalFile) {
                "Wallet-state quarantine target escaped its directory"
            }
            check(!quarantined.exists()) {
                "Wallet-state quarantine target already exists"
            }
            check(original.renameTo(quarantined)) {
                "Could not quarantine ${original.name}; wallet state was left unchanged"
            }
            movedFiles += original to quarantined
        }
    }

    fun markReplacementStateStarted() {
        replacementStateStarted = true
    }

    fun rollback(cause: Exception) {
        if (replacementStateStarted) {
            originalFiles.forEach { generated ->
                if (generated.exists() && (!generated.isFile || !generated.delete())) {
                    throw IllegalStateException(
                        "Recovery failed and Clench could not remove the incomplete replacement " +
                            "${generated.name}. The original wallet state remains in internal quarantine.",
                        cause
                    )
                }
            }
        }
        movedFiles.asReversed().forEach { (original, quarantined) ->
            if (quarantined.exists() && !quarantined.renameTo(original)) {
                throw IllegalStateException(
                    "Recovery failed and Clench could not restore ${original.name}. " +
                        "The preserved copy remains in internal quarantine.",
                    cause
                )
            }
        }
    }
}
