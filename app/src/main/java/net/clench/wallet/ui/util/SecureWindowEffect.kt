package net.clench.wallet.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

@Composable
fun SecureWindowEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity, enabled) {
        if (enabled && activity != null) {
            SecureWindowFlags.acquire(activity)
        }
        onDispose {
            if (enabled && activity != null) {
                SecureWindowFlags.release(activity)
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

private object SecureWindowFlags {
    private val counts = WeakHashMap<Activity, Int>()

    @Synchronized
    fun acquire(activity: Activity) {
        counts[activity] = (counts[activity] ?: 0) + 1
        activity.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Synchronized
    fun release(activity: Activity) {
        val next = ((counts[activity] ?: 1) - 1).coerceAtLeast(0)
        if (next == 0) {
            counts.remove(activity)
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            counts[activity] = next
        }
    }
}
