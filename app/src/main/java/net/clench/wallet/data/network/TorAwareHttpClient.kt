package net.clench.wallet.data.network

import android.util.Log
import net.clench.wallet.data.local.SettingsManager
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * H-2: Tor-aware HTTP helper.
 * Routes HTTP requests through the SOCKS5 proxy when Tor is enabled.
 * Falls back to direct connection when Tor is disabled.
 */
@Singleton
class TorAwareHttpClient @Inject constructor(
    private val settingsManager: SettingsManager
) {
    companion object {
        private const val TAG = "TorAwareHttp"
    }

    /**
     * Open an HttpURLConnection, routed through Tor SOCKS5 proxy if Tor is enabled.
     */
    fun openConnection(url: String, connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 5_000): HttpURLConnection {
        val conn = if (settingsManager.isTorEnabled()) {
            val proxyHost = settingsManager.getTorProxyHost()
            val proxyPort = settingsManager.getTorProxyPort()
            Log.d(TAG, "Routing through Tor SOCKS5 proxy $proxyHost:$proxyPort: $url")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))
            URL(url).openConnection(proxy) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        return conn
    }

    /**
     * Fetch text content from a URL, Tor-aware.
     */
    fun fetchText(url: String, connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 5_000): String {
        val conn = openConnection(url, connectTimeoutMs, readTimeoutMs)
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Check if Tor is currently enabled.
     */
    fun isTorEnabled(): Boolean = settingsManager.isTorEnabled()
}
