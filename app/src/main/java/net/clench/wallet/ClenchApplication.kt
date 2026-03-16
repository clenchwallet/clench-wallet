package net.clench.wallet

import android.app.Application
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

@HiltAndroidApp
class ClenchApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
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

                // Write to external storage (accessible via file manager)
                try {
                    val extDir = getExternalFilesDir(null)
                    extDir?.let { File(it, "crash_log.txt").writeText(report) }
                } catch (e: Exception) {
                    Log.e("CrashHandler", "Failed to write external crash log", e)
                }

                Log.e("CLENCH_CRASH", report)

                // DEBUG ONLY — remove before production release
                // Auto-POST to debug endpoint (Tailscale — only reachable on dev network)
                if (BuildConfig.DEBUG) {
                    try {
                        val url = java.net.URL("http://100.120.112.75:8181/crash")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.outputStream.use { it.write(report.toByteArray()) }
                        conn.responseCode // trigger send
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.w("CrashHandler", "Failed to POST crash log to debug endpoint", e)
                    }
                }

            } catch (e: Exception) {
                Log.e("CrashHandler", "Error in crash handler itself", e)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
