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

                val report = """
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

                // Write to internal storage (always accessible)
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

            } catch (e: Exception) {
                Log.e("CrashHandler", "Error in crash handler itself", e)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
