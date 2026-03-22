package net.clench.wallet

import android.app.Application
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.entity.WalletEntity
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

@HiltAndroidApp
class ClenchApplication : Application() {

    @Inject lateinit var keystoreManager: KeystoreManager
    @Inject lateinit var walletDao: WalletDao
    @Inject lateinit var transactionDao: net.clench.wallet.data.local.dao.TransactionDao

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // One-time migration: delete any stale passphrases from encrypted storage [C-2]
        try {
            keystoreManager.deleteAllPassphrases()
        } catch (e: Exception) {
            Log.w("ClenchApp", "Passphrase cleanup failed (non-fatal)", e)
        }

        // On every cold start, wipe on-disk DBs for passphrase wallets.
        // Passphrase wallets use in-memory sessions only — the disk DB must not contain
        // cached UTXOs or transaction history that would be visible before the passphrase is entered.
        try {
            val passphraseWallets = walletDao.getAllSync().filter { it.hasPassphrase }
            for (wallet in passphraseWallets) {
                // Wipe BDK on-disk DB
                val dbFile = getDatabasePath("wallet_${wallet.id}.db")
                dbFile.delete()
                java.io.File(dbFile.path + "-wal").delete()
                java.io.File(dbFile.path + "-shm").delete()
                java.io.File(dbFile.path + "-journal").delete()
                Log.d("ClenchApp", "Startup: wiped on-disk DB for passphrase wallet ${wallet.id}")
                // Wipe Room transaction cache — same reason: real tx history must not be visible
                // before the passphrase is entered
                kotlinx.coroutines.runBlocking {
                    try { transactionDao.deleteForWallet(wallet.id) } catch (_: Exception) {}
                }
                Log.d("ClenchApp", "Startup: wiped Room tx cache for passphrase wallet ${wallet.id}")
            }
        } catch (e: Exception) {
            Log.w("ClenchApp", "Passphrase wallet DB wipe failed (non-fatal): ${e.message}")
        }

        // Recovery: rebuild Room wallet records from orphaned BDK wallet files
        // This handles the case where SQLCipher migration wiped Room but BDK files + Keystore secrets survived
        try {
            recoverOrphanedWallets()
        } catch (e: Exception) {
            Log.w("ClenchApp", "Wallet recovery failed (non-fatal)", e)
        }
    }

    /**
     * Recover wallets that have BDK database files + Keystore secrets but no Room record.
     * This can happen when the Room database is recreated (e.g., SQLCipher migration).
     */
    private fun recoverOrphanedWallets() = runBlocking {
        val dbDir = getDatabasePath("clench.db").parentFile ?: return@runBlocking
        val walletFiles = dbDir.listFiles { f -> f.name.startsWith("wallet_") && f.name.endsWith(".db") } ?: return@runBlocking

        val existingIds = walletDao.getAll().map { it.id }.toSet()
        var recovered = 0

        for (file in walletFiles) {
            val walletId = file.name.removePrefix("wallet_").removeSuffix(".db")
            if (walletId in existingIds) continue  // already in Room

            // Check if we have secrets for this wallet in Keystore
            val secretDescriptor = keystoreManager.getSecretDescriptor(walletId)
            val hasMnemonic = keystoreManager.hasMnemonic(walletId)

            // Get the public descriptor — derive from secret if available
            val publicDescriptor: String
            val publicChangeDescriptor: String
            val isWatchOnly: Boolean

            if (secretDescriptor != null) {
                // Strip xprv to get public descriptor
                publicDescriptor = secretDescriptor
                    .replace(Regex("xprv[1-9A-HJ-NP-Za-km-z]+")) { key ->
                        // Can't easily convert xprv→xpub without BDK, so store the secret descriptor
                        // and let the app derive the public one on first sync
                        key.value
                    }
                publicChangeDescriptor = keystoreManager.getSecretChangeDescriptor(walletId) ?: ""
                isWatchOnly = false
            } else {
                // No secret descriptor — skip, we can't recover without keys
                Log.w("ClenchApp", "Skipping orphan $walletId — no keystore secrets found")
                continue
            }

            // Determine network from descriptor (testnet uses tprv/tpub)
            val network = if (secretDescriptor.contains("tprv") || secretDescriptor.contains("tpub")) "testnet" else "mainnet"

            // Check if wallet had a passphrase (we can't know for sure, default to false)
            val hasPassphrase = false

            val entity = WalletEntity(
                id = walletId,
                name = "Recovered Wallet",
                descriptor = publicDescriptor,
                changeDescriptor = publicChangeDescriptor,
                isWatchOnly = isWatchOnly,
                isMultisig = false,
                createdAtEpochMs = file.lastModified(),
                network = network,
                hasPassphrase = hasPassphrase
            )

            walletDao.insert(entity)
            recovered++
            Log.i("ClenchApp", "Recovered orphaned wallet: $walletId (network=$network, watchOnly=$isWatchOnly)")
        }

        if (recovered > 0) {
            Log.i("ClenchApp", "Recovered $recovered orphaned wallet(s)")
        }
    }

    /**
     * Sanitize crash reports by stripping potentially sensitive data:
     * - xprv/xpub extended keys
     * - BIP39 mnemonic word sequences
     */
    internal fun sanitizeCrashReport(report: String): String {
        var sanitized = report

        // Redact xprv extended private keys (base58, ~111 chars)
        sanitized = sanitized.replace(
            Regex("xprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_XPRV]"
        )

        // Redact xpub extended public keys (base58, ~111 chars)
        sanitized = sanitized.replace(
            Regex("xpub[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_XPUB]"
        )

        // R7-23: Redact testnet equivalents (tprv/tpub)
        sanitized = sanitized.replace(
            Regex("tprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_TPRV]"
        )
        sanitized = sanitized.replace(
            Regex("tpub[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_TPUB]"
        )

        // Redact zpub/vpub/ypub extended keys
        sanitized = sanitized.replace(
            Regex("[zvyZVY]pub[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_EXTKEY]"
        )

        // R7-11: Redact testnet private keys (zprv, yprv, vprv, uprv)
        sanitized = sanitized.replace(
            Regex("zprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_ZPRV]"
        )
        sanitized = sanitized.replace(
            Regex("Zprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_ZPRV]"
        )
        sanitized = sanitized.replace(
            Regex("yprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_YPRV]"
        )
        sanitized = sanitized.replace(
            Regex("Yprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_YPRV]"
        )
        sanitized = sanitized.replace(
            Regex("vprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_VPRV]"
        )
        sanitized = sanitized.replace(
            Regex("Vprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_VPRV]"
        )
        sanitized = sanitized.replace(
            Regex("uprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_UPRV]"
        )
        sanitized = sanitized.replace(
            Regex("Uprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_UPRV]"
        )

        // Redact likely BIP39 mnemonic sequences (12-24 lowercase words, 3-8 chars each)
        sanitized = sanitized.replace(
            Regex("""(?<!\S)(?:[a-z]{3,8}\s){11,23}[a-z]{3,8}(?!\S)"""),
            "[REDACTED_MNEMONIC]"
        )

        return sanitized
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val versionName = try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (e: Exception) { "unknown" }

                val rawReport = """
CLENCH WALLET CRASH REPORT
==========================
Time: $timestamp
Thread: ${thread.name}
Device: ${Build.MANUFACTURER} ${Build.MODEL}
Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
App Version: $versionName

STACK TRACE:
$stackTrace
""".trimIndent()

                // Sanitize to strip any sensitive material (xprv, mnemonics, etc.)
                val report = sanitizeCrashReport(rawReport)

                // Write sanitized report to internal storage (always accessible)
                try {
                    File(filesDir, "crash_log.txt").writeText(report)
                } catch (e: Exception) {
                    Log.e("CrashHandler", "Failed to write internal crash log", e)
                }

                Log.e("CLENCH_CRASH", report)

            } catch (e: Exception) {
                Log.e("CrashHandler", "Error in crash handler itself", e)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
