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

    // [S-4] Gate sensitive debug logging in release builds.
    private val logSensitive = android.util.Log.isLoggable("ClenchApp", android.util.Log.DEBUG)
        && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

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
                // [S-4] Gate: wallet ID exposure
                if (logSensitive) {
                    Log.d("ClenchApp", "Startup: wiped on-disk DB for passphrase wallet ${wallet.id}")
                }
                // Wipe Room transaction cache — same reason: real tx history must not be visible
                // before the passphrase is entered
                kotlinx.coroutines.runBlocking {
                    try { transactionDao.deleteForWallet(wallet.id) } catch (_: Exception) {}
                }
                if (logSensitive) {
                    Log.d("ClenchApp", "Startup: wiped Room tx cache for passphrase wallet ${wallet.id}")
                }
            }
        } catch (e: Exception) {
            Log.w("ClenchApp", "Passphrase wallet DB wipe failed (non-fatal): ${e.message}")
        }

        // [H-3] Orphan wallet auto-recovery is disabled.
        // We do not silently reconstruct wallet records on startup because that can
        // misclassify passphrase wallets and create misleading state.
        // If recovery tooling is ever added back, it should be explicit and user-driven.
    }

    /**
     * Orphan wallet detection (no auto-recovery).
     * Logs orphaned wallet DB files without modifying Room.
     *
     * This remains intentionally unused by default. Recovery for wallet state should be
     * explicit and user-driven, especially for passphrase-backed wallets.
     */
    @Suppress("UNUSED")
    private fun recoverOrphanedWallets() = runBlocking {
        val dbDir = getDatabasePath("clench.db").parentFile ?: return@runBlocking
        val walletFiles = dbDir.listFiles { f -> f.name.startsWith("wallet_") && f.name.endsWith(".db") } ?: return@runBlocking

        val existingIds = walletDao.getAll().map { it.id }.toSet()
        var detected = 0

        for (file in walletFiles) {
            val walletId = file.name.removePrefix("wallet_").removeSuffix(".db")
            if (walletId in existingIds) continue  // already in Room — not an orphan

            // Check if we have secrets for this wallet in Keystore
            val secretDescriptor = keystoreManager.getSecretDescriptor(walletId)
            val hasMnemonic = keystoreManager.hasMnemonic(walletId)

            // [H-3] Determine passphrase status safely.
            // Passphrase wallets store mnemonic but NOT secret descriptors (derived on-the-fly).
            // Regular (non-passphrase) wallets store BOTH mnemonic AND secret descriptors.
            val isPassphraseWallet = hasMnemonic && secretDescriptor == null
            val isRegularWallet = hasMnemonic && secretDescriptor != null
            val isWatchOnly = !hasMnemonic && secretDescriptor != null

            val walletType = when {
                isPassphraseWallet -> "passphrase wallet"
                isRegularWallet -> "regular wallet"
                isWatchOnly -> "watch-only wallet"
                else -> "unknown type"
            }

            // [H-3] Do NOT auto-insert wallet records. Log only (when logSensitive).
            // Passphrase wallets: cannot recover without the passphrase — user must re-unlock
            // Regular wallets: user should re-import via seed phrase (safe and verified)
            // Watch-only: user should re-import via descriptor
            if (logSensitive) {
                Log.w("ClenchApp", "Orphan detected: $walletId ($walletType, mtime=${file.lastModified()}) — " +
                    "requires explicit re-import by user")
            }
            detected++
        }

        if (detected > 0) {
            // [S-4] Gate: count-only log, safe to keep
            Log.i("ClenchApp", "Detected $detected orphaned wallet DB(s) — manual re-import required")
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
