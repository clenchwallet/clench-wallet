package net.clench.wallet.data.network

import android.util.Log
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.ElectrumConfig
import org.bitcoindevkit.ElectrumClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Connection mode for Electrum server.
 */
enum class ConnectionMode {
    /** Plain TCP (port 50001 convention) */
    PLAIN_TCP,
    /** TLS with system trust store (standard CAs) */
    TLS_SYSTEM,
    /** TLS with a user-pinned certificate */
    TLS_PINNED,
    /** Routed through Tor SOCKS5 proxy (Orbot) — plain TCP to .onion */
    TOR_PLAIN,
    /** Routed through Tor SOCKS5 proxy — with TLS on top */
    TOR_TLS
}

/**
 * Result of resolving connection parameters.
 */
data class ResolvedConnection(
    val mode: ConnectionMode,
    val host: String,
    val port: Int,
    val pinnedCertDer: ByteArray? = null,
    val socksHost: String? = null,
    val socksPort: Int? = null
)

/**
 * Exception types for connection failures with human-readable messages.
 */
sealed class ElectrumConnectionException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class TlsCertPinningFailed(host: String, cause: Throwable? = null) :
        ElectrumConnectionException("TLS certificate pinning failed for $host — the server's certificate doesn't match the pinned cert.", cause)

    class TlsHandshakeFailed(host: String, cause: Throwable? = null) :
        ElectrumConnectionException("TLS handshake failed with $host — check that the server supports TLS on this port.", cause)

    class TorProxyUnavailable(socksHost: String, socksPort: Int, cause: Throwable? = null) :
        ElectrumConnectionException("Clench uses Orbot for Tor. Install or start Orbot and make sure its SOCKS5 proxy is listening at $socksHost:$socksPort.", cause)

    class ConnectionFailed(host: String, port: Int, cause: Throwable? = null) :
        ElectrumConnectionException("Could not connect to $host:$port — check that the server is running.", cause)
}

/**
 * Factory that creates BDK ElectrumClient instances supporting three connection modes:
 *
 * 1. **Plain TCP** — direct socket, passed to BDK as `tcp://host:port`
 * 2. **TLS** (system or pinned cert) — for pinned certs, we run a local TCP relay
 *    that terminates TLS with a custom TrustManager, and BDK connects to `tcp://127.0.0.1:localPort`
 * 3. **Tor/SOCKS5** — we open a SOCKS5 connection through Orbot, then run a local TCP relay
 *    so BDK connects to `tcp://127.0.0.1:localPort`
 *
 * For standard TLS (system trust store, no pinning), BDK handles it natively via `ssl://host:port`.
 */
@Singleton
class ElectrumConnectionFactory @Inject constructor(
    private val settingsManager: SettingsManager
) {
    companion object {
        private const val TAG = "ElectrumConnFactory"
        private const val SOCKS5_CONNECT_TIMEOUT_MS = 15_000
        private const val SOCKET_NEGOTIATION_TIMEOUT_MS = 15_000
        private const val RELAY_BUFFER_SIZE = 8192
    }

    /**
     * Resolve what connection mode to use based on config + settings.
     */
    fun resolveConnection(config: ElectrumConfig): ResolvedConnection {
        val host = config.serverUrl.removePrefix("ssl://").removePrefix("tcp://").trim()
        val isOnion = host.endsWith(".onion")
        val torEnabled = settingsManager.isTorEnabled()
        val useTor = isOnion || config.useTor || torEnabled
        val pinnedCertBase64 = config.pinnedCert
        val pinnedCertDer = if (!pinnedCertBase64.isNullOrBlank()) {
            try {
                java.util.Base64.getDecoder().decode(pinnedCertBase64)
            } catch (e: Exception) {
                throw ElectrumConnectionException.TlsCertPinningFailed(host, e)
            }
        } else null

        val socksHost = settingsManager.getTorProxyHost()
        val socksPort = settingsManager.getTorProxyPort()

        return when {
            // .onion addresses: must use Tor, plain TCP to the onion service
            isOnion -> ResolvedConnection(
                mode = ConnectionMode.TOR_PLAIN,
                host = host,
                port = config.port,
                socksHost = socksHost,
                socksPort = socksPort
            )
            // Tor enabled for clearnet address with TLS
            useTor && config.useSsl -> ResolvedConnection(
                mode = ConnectionMode.TOR_TLS,
                host = host,
                port = config.port,
                pinnedCertDer = pinnedCertDer,
                socksHost = socksHost,
                socksPort = socksPort
            )
            // Tor enabled for clearnet address without TLS
            useTor -> ResolvedConnection(
                mode = ConnectionMode.TOR_PLAIN,
                host = host,
                port = config.port,
                socksHost = socksHost,
                socksPort = socksPort
            )
            // Pinned cert → TLS with custom trust manager (needs relay)
            pinnedCertDer != null -> ResolvedConnection(
                mode = ConnectionMode.TLS_PINNED,
                host = host,
                port = config.port,
                pinnedCertDer = pinnedCertDer
            )
            // Standard TLS → BDK handles natively
            config.useSsl -> ResolvedConnection(
                mode = ConnectionMode.TLS_SYSTEM,
                host = host,
                port = config.port
            )
            // Plain TCP → BDK handles natively
            else -> ResolvedConnection(
                mode = ConnectionMode.PLAIN_TCP,
                host = host,
                port = config.port
            )
        }
    }

    /**
     * Create a BDK ElectrumClient using the resolved connection mode.
     *
     * For modes that need a relay (TLS pinned, Tor), this starts a background relay
     * and returns an [ActiveElectrumConnection] wrapping the client + relay resources.
     *
     * For native modes (plain TCP, system TLS), returns a simple wrapper.
     */
    fun createConnection(config: ElectrumConfig): ActiveElectrumConnection {
        val resolved = resolveConnection(config)
        if (net.clench.wallet.BuildConfig.DEBUG) Log.d(TAG, "createConnection: mode=${resolved.mode} host=${resolved.host}:${resolved.port}")

        return when (resolved.mode) {
            ConnectionMode.PLAIN_TCP -> {
                val url = "tcp://${resolved.host}:${resolved.port}"
                val client = ElectrumClient(url)
                ActiveElectrumConnection(client, mode = resolved.mode)
            }
            ConnectionMode.TLS_SYSTEM -> {
                val url = "ssl://${resolved.host}:${resolved.port}"
                val client = ElectrumClient(url)
                ActiveElectrumConnection(client, mode = resolved.mode)
            }
            ConnectionMode.TLS_PINNED -> {
                createRelayedConnection(resolved)
            }
            ConnectionMode.TOR_PLAIN, ConnectionMode.TOR_TLS -> {
                createRelayedConnection(resolved)
            }
        }
    }

    /**
     * Build a BDK-compatible URL string for modes that BDK handles natively.
     * Falls back to relay for modes that need custom socket handling.
     */
    fun buildBdkUrl(config: ElectrumConfig): String {
        val resolved = resolveConnection(config)
        return when (resolved.mode) {
            ConnectionMode.PLAIN_TCP -> "tcp://${resolved.host}:${resolved.port}"
            ConnectionMode.TLS_SYSTEM -> "ssl://${resolved.host}:${resolved.port}"
            // These modes need a relay — this method shouldn't be called for them
            // but return a fallback that will fail with a clear error
            else -> "tcp://${resolved.host}:${resolved.port}"
        }
    }

    /**
     * Check if the current config requires a relay (non-native BDK mode).
     */
    fun needsRelay(config: ElectrumConfig): Boolean {
        val resolved = resolveConnection(config)
        return resolved.mode != ConnectionMode.PLAIN_TCP && resolved.mode != ConnectionMode.TLS_SYSTEM
    }

    // ─── Internal relay implementation ───

    private fun createRelayedConnection(resolved: ResolvedConnection): ActiveElectrumConnection {
        // Open the upstream socket (SOCKS5 or direct + TLS)
        val upstreamSocket = openUpstreamSocket(resolved)

        // Start a local TCP server that BDK will connect to
        val localServer = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val localPort = localServer.localPort
        if (net.clench.wallet.BuildConfig.DEBUG) Log.d(TAG, "relay: listening on 127.0.0.1:$localPort for mode=${resolved.mode}")

        // Accept exactly one connection (from BDK), relay bidirectionally
        val relayThread = Thread({
            try {
                localServer.soTimeout = 30_000  // 30s timeout for BDK to connect
                val localSocket = localServer.accept()
                if (net.clench.wallet.BuildConfig.DEBUG) Log.d(TAG, "relay: BDK connected to local port $localPort")

                // Bidirectional relay
                val t1 = Thread({
                    relay(localSocket.getInputStream(), upstreamSocket.getOutputStream(), "BDK→upstream")
                }, "relay-up-${resolved.host}")
                val t2 = Thread({
                    relay(upstreamSocket.getInputStream(), localSocket.getOutputStream(), "upstream→BDK")
                }, "relay-down-${resolved.host}")

                t1.isDaemon = true
                t2.isDaemon = true
                t1.start()
                t2.start()

                // Wait for either direction to finish
                t1.join()
                t2.join()
            } catch (e: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) Log.w(TAG, "relay error: ${e.message}")
            } finally {
                try { upstreamSocket.close() } catch (_: Exception) {}
                try { localServer.close() } catch (_: Exception) {}
            }
        }, "relay-accept-${resolved.host}")
        relayThread.isDaemon = true
        relayThread.start()

        // BDK connects to local relay via plain TCP
        val url = "tcp://127.0.0.1:$localPort"
        val client = ElectrumClient(url)

        return ActiveElectrumConnection(
            client = client,
            mode = resolved.mode,
            relayResources = RelayResources(localServer, upstreamSocket, relayThread)
        )
    }

    private fun openUpstreamSocket(resolved: ResolvedConnection): Socket {
        val socket: Socket = if (resolved.socksHost != null && resolved.socksPort != null) {
            // SOCKS5 connection through Tor
            openSocks5Socket(resolved.socksHost, resolved.socksPort, resolved.host, resolved.port)
        } else {
            // Direct TCP connection
            Socket().also {
                it.connect(InetSocketAddress(resolved.host, resolved.port), SOCKS5_CONNECT_TIMEOUT_MS)
            }
        }
        // Bound SOCKS5 negotiation and TLS handshake reads. Callers may replace
        // this with a protocol-specific timeout after the socket is established.
        socket.soTimeout = SOCKET_NEGOTIATION_TIMEOUT_MS

        // Wrap in TLS if needed
        return if (resolved.mode == ConnectionMode.TLS_PINNED || resolved.mode == ConnectionMode.TOR_TLS) {
            wrapTls(socket, resolved.host, resolved.pinnedCertDer)
        } else {
            socket
        }
    }

    /**
     * Open a SOCKS5 connection through the proxy (Orbot).
     * Implements SOCKS5 protocol with domain-name resolution at proxy (for .onion support).
     */
    private fun openSocks5Socket(socksHost: String, socksPort: Int, targetHost: String, targetPort: Int): Socket {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(socksHost, socksPort), SOCKS5_CONNECT_TIMEOUT_MS)
            sock.soTimeout = SOCKET_NEGOTIATION_TIMEOUT_MS
        } catch (e: Exception) {
            throw ElectrumConnectionException.TorProxyUnavailable(socksHost, socksPort, e)
        }

        val os = sock.getOutputStream()
        val ins = sock.getInputStream()

        // SOCKS5 greeting: version=5, 1 auth method (no auth)
        os.write(byteArrayOf(0x05, 0x01, 0x00))
        os.flush()

        // Server response: version, chosen method
        val greetResp = ByteArray(2)
        readFully(ins, greetResp)
        if (greetResp[0] != 0x05.toByte() || greetResp[1] != 0x00.toByte()) {
            sock.close()
            throw ElectrumConnectionException.TorProxyUnavailable(socksHost, socksPort,
                IOException("SOCKS5 auth negotiation failed: ${greetResp.joinToString { "%02x".format(it) }}"))
        }

        // SOCKS5 connect request: version=5, cmd=CONNECT(1), rsv=0, atyp=DOMAINNAME(3)
        val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(4 + 1 + hostBytes.size + 2)
        request[0] = 0x05  // version
        request[1] = 0x01  // connect
        request[2] = 0x00  // reserved
        request[3] = 0x03  // domain name
        request[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
        request[5 + hostBytes.size] = (targetPort shr 8).toByte()
        request[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        os.write(request)
        os.flush()

        // Read response header (4 bytes)
        val respHeader = ByteArray(4)
        readFully(ins, respHeader)
        if (respHeader[1] != 0x00.toByte()) {
            sock.close()
            val errCode = respHeader[1].toInt() and 0xFF
            val errMsg = when (errCode) {
                1 -> "general SOCKS server failure"
                2 -> "connection not allowed by ruleset"
                3 -> "network unreachable"
                4 -> "host unreachable"
                5 -> "connection refused"
                6 -> "TTL expired"
                7 -> "command not supported"
                8 -> "address type not supported"
                else -> "unknown error ($errCode)"
            }
            throw ElectrumConnectionException.ConnectionFailed(targetHost, targetPort,
                IOException("SOCKS5 connect failed: $errMsg"))
        }

        // Skip bound address based on address type
        when (respHeader[3].toInt() and 0xFF) {
            0x01 -> readFully(ins, ByteArray(4 + 2))  // IPv4 + port
            0x03 -> {
                val len = ins.read()
                readFully(ins, ByteArray(len + 2))      // domain + port
            }
            0x04 -> readFully(ins, ByteArray(16 + 2)) // IPv6 + port
        }

        if (net.clench.wallet.BuildConfig.DEBUG) Log.d(TAG, "SOCKS5 connected to $targetHost:$targetPort via $socksHost:$socksPort")
        return sock
    }

    /**
     * Wrap an existing socket in TLS, optionally with a pinned certificate.
     */
    private fun wrapTls(socket: Socket, host: String, pinnedCertDer: ByteArray?): Socket {
        val sslSocketFactory: SSLSocketFactory = if (pinnedCertDer != null) {
            // Build a TrustManager that only trusts the pinned cert
            try {
                val certFactory = CertificateFactory.getInstance("X.509")
                val cert = certFactory.generateCertificate(pinnedCertDer.inputStream()) as X509Certificate

                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null, null)
                keyStore.setCertificateEntry("pinned", cert)

                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(keyStore)

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, tmf.trustManagers, null)
                sslContext.socketFactory
            } catch (e: Exception) {
                socket.close()
                throw ElectrumConnectionException.TlsCertPinningFailed(host, e)
            }
        } else {
            // System trust store
            SSLSocketFactory.getDefault() as SSLSocketFactory
        }

        return try {
            val sslSocket = sslSocketFactory.createSocket(socket, host, socket.port, true) as javax.net.ssl.SSLSocket
            // Pinning narrows certificate trust; it does not replace hostname verification.
            val sslParameters = sslSocket.sslParameters
            sslParameters.endpointIdentificationAlgorithm = "HTTPS"
            sslSocket.sslParameters = sslParameters
            sslSocket.startHandshake()
            if (net.clench.wallet.BuildConfig.DEBUG) Log.d(TAG, "TLS handshake complete with $host (pinned=${pinnedCertDer != null})")
            sslSocket
        } catch (e: Exception) {
            socket.close()
            if (pinnedCertDer != null) {
                throw ElectrumConnectionException.TlsCertPinningFailed(host, e)
            } else {
                throw ElectrumConnectionException.TlsHandshakeFailed(host, e)
            }
        }
    }

    private fun relay(input: InputStream, output: OutputStream, label: String) {
        val buf = ByteArray(RELAY_BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // Connection closed
        }
        if (net.clench.wallet.BuildConfig.DEBUG) Log.d(TAG, "relay $label finished")
    }

    /**
     * Create a raw TCP/TLS/SOCKS5 socket for direct Electrum JSON-RPC usage
     * (e.g. batch transaction lookups that bypass BDK).
     * Caller is responsible for closing the returned socket.
     */
    fun createRawSocket(config: ElectrumConfig): Socket {
        val resolved = resolveConnection(config)
        return openUpstreamSocket(resolved)
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw IOException("Unexpected end of stream")
            offset += n
        }
    }
}

/**
 * Holds relay resources (local server socket, upstream socket, threads) that must
 * be cleaned up when the connection is done.
 */
data class RelayResources(
    val localServer: ServerSocket,
    val upstreamSocket: Socket,
    val relayThread: Thread
)

/**
 * Wraps a BDK ElectrumClient with its connection mode and any relay resources.
 * Always call [close] when done.
 */
class ActiveElectrumConnection(
    val client: ElectrumClient,
    val mode: ConnectionMode,
    private val relayResources: RelayResources? = null
) : AutoCloseable {
    override fun close() {
        try { client.close() } catch (_: Exception) {}
        relayResources?.let {
            try { it.upstreamSocket.close() } catch (_: Exception) {}
            try { it.localServer.close() } catch (_: Exception) {}
            it.relayThread.interrupt()
        }
    }
}
