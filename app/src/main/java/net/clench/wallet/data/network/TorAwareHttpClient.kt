package net.clench.wallet.data.network

import net.clench.wallet.data.local.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tor-aware HTTP helper for plain HTTPS fetches used by mempool.space, Coinbase, and CoinGecko.
 * Routes traffic through the configured SOCKS5 proxy when Tor is enabled.
 */
@Singleton
class TorAwareHttpClient @Inject constructor(
    private val settingsManager: SettingsManager
) {
    /**
     * Fetch URL content as plain text.
     * When Tor is enabled, routes through the configured SOCKS5 proxy.
     *
     * @param url The HTTPS URL to fetch
     * @param connectTimeoutMs Connection timeout in milliseconds (default 5 seconds)
     * @param readTimeoutMs Read timeout in milliseconds (default 10 seconds)
     * @return The response body as a String
     * @throws java.io.IOException on network errors
     */
    fun fetchText(
        url: String,
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 10_000
    ): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection

        if (settingsManager.isTorEnabled()) {
            val proxyHost = settingsManager.getTorProxyHost()
            val proxyPort = settingsManager.getTorProxyPort()
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress(proxyHost, proxyPort)
            )
            // Re-open with proxy
            val proxyConn = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
            proxyConn.connectTimeout = connectTimeoutMs
            proxyConn.readTimeout = readTimeoutMs
            return fetchWithConnection(proxyConn)
        }

        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        return fetchWithConnection(conn)
    }

    private fun fetchWithConnection(conn: java.net.HttpURLConnection): String {
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
