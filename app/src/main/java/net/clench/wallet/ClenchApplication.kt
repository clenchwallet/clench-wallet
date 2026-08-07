package net.clench.wallet

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.repository.BdkBitcoinRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import net.clench.wallet.security.CrashReportSanitizer
import net.clench.wallet.ui.AppProcessSecurityCoordinator

@HiltAndroidApp
class ClenchApplication : Application() {

    @Inject lateinit var keystoreManager: KeystoreManager
    @Inject lateinit var walletDao: WalletDao
    @Inject lateinit var appProcessSecurityCoordinator: AppProcessSecurityCoordinator
    @Inject internal lateinit var bitcoinRepository: BdkBitcoinRepository

    // [S-4] Gate sensitive debug logging in release builds.
    private val logSensitive: Boolean
        get() = BuildConfig.DEBUG &&
            Log.isLoggable("ClenchApp", Log.DEBUG) &&
            ((runCatching { applicationInfo.flags }.getOrDefault(0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0)

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        appProcessSecurityCoordinator.register(this)
        // Establish the same fail-closed boundary on cold start as on process background. Both
        // stale encrypted-passphrase deletion and native/disk/Room cache eviction are attempted
        // independently; wallet admission opens only when every pass verifies successfully.
        runBlocking { appProcessSecurityCoordinator.secureColdStart() }

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
                if (net.clench.wallet.BuildConfig.DEBUG) Log.w("ClenchApp", "Orphan detected: $walletId ($walletType, mtime=${file.lastModified()}) — " +
                    "requires explicit re-import by user")
            }
            detected++
        }

        if (detected > 0) {
            // [S-4] Gate: count-only log, safe to keep
            if (net.clench.wallet.BuildConfig.DEBUG) Log.i("ClenchApp", "Detected $detected orphaned wallet DB(s) — manual re-import required")
        }
    }

    /**
     * Sanitize crash reports by stripping potentially sensitive data:
     * - xprv/xpub extended keys
     * - BIP39 mnemonic word sequences
     */
    internal fun sanitizeCrashReport(report: String): String {
        return CrashReportSanitizer.sanitize(report)
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
                    if (net.clench.wallet.BuildConfig.DEBUG) Log.e("CrashHandler", "Failed to write internal crash log", e)
                }

                if (net.clench.wallet.BuildConfig.DEBUG) Log.e("CLENCH_CRASH", report)

            } catch (e: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) Log.e("CrashHandler", "Error in crash handler itself", e)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
