package net.clench.wallet.ui.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast

/**
 * Copies text to the system clipboard with security best practices:
 * - Marks data as sensitive on Android 13+ (EXTRA_IS_SENSITIVE)
 * - Attempts to clear unchanged clipboard content after [clearAfterMs] (default 60 seconds)
 *   while this process remains alive and Android permits clipboard access.
 */
fun copyToClipboardWithAutoClear(
    context: Context,
    label: String,
    text: String,
    clearAfterMs: Long = 60_000L
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)

    // Request sensitive-content presentation on Android 13+; not an access-control
    // or third-party clipboard-history erasure guarantee.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }

    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()

    // Auto-clear clipboard after timeout
    Handler(Looper.getMainLooper()).postDelayed({
        try {
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (current == text) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
            // Clipboard may be unavailable (app in background on newer Android)
        }
    }, clearAfterMs)
}
