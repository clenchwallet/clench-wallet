package net.clench.wallet.data.network

import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.security.readTextBounded
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
        val lease = settingsManager.networkAccess.begin()
        try {
        lease.requireCurrent()
        val conn = if (settingsManager.isTorEnabled()) {
            val proxyHost = settingsManager.getTorProxyHost()
            val proxyPort = settingsManager.getTorProxyPort()
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress(proxyHost, proxyPort)
            )
            java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
        } else {
            java.net.URL(url).openConnection() as java.net.HttpURLConnection
        }

        lease.register { conn.disconnect() }
        conn.instanceFollowRedirects = false // Follow-ups require fresh route/admission review.
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        lease.requireCurrent()
        check(conn.responseCode in 200..299) { "HTTP request did not succeed" }
        val body = conn.inputStream.bufferedReader().use { it.readTextBounded(MAX_HTTP_RESPONSE_CHARS) }
        lease.requireCurrent()
        return body
        } finally {
            lease.close()
        }
    }

    private companion object {
        const val MAX_HTTP_RESPONSE_CHARS = 2 * 1024 * 1024
    }
}
