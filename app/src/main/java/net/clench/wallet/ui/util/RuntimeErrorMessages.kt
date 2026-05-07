package net.clench.wallet.ui.util

import kotlinx.coroutines.CancellationException

internal fun Throwable.shouldRethrowForUiBoundary(): Boolean {
    return this is CancellationException ||
        this is VirtualMachineError ||
        this is ThreadDeath
}

internal fun Throwable.walletRuntimeMessage(action: String): String {
    val type = javaClass.simpleName.ifBlank { "RuntimeError" }
    val detail = message?.take(160)?.takeIf { it.isNotBlank() }
    return if (this is LinkageError) {
        "Bitcoin runtime failed while $action ($type). Install the latest APK. If it still happens, open Settings > Diagnostics > Share."
    } else if (detail != null) {
        "$type: $detail"
    } else {
        "$type: $action failed"
    }
}

internal fun Throwable.connectionRuntimeMessage(): String {
    if (this is LinkageError) {
        return walletRuntimeMessage("testing the Bitcoin connection")
    }

    val msg = message ?: "Connection error"
    return when {
        msg.contains("SSL", ignoreCase = true) ||
            msg.contains("TLS", ignoreCase = true) ||
            msg.contains("certificate", ignoreCase = true) ||
            msg.contains("handshake", ignoreCase = true) ->
            "SSL/TLS error - check that SSL matches the server port, or disable SSL and use port 50001."
        msg.contains("refused", ignoreCase = true) ->
            "Connection refused - check host/port and that your server is running."
        msg.contains("SOCKS", ignoreCase = true) ||
            msg.contains("Tor", ignoreCase = true) ->
            "Orbot SOCKS5 proxy error - install/start Orbot and try again. ${msg.take(100)}"
        else -> "${javaClass.simpleName}: ${msg.take(150)}"
    }
}
