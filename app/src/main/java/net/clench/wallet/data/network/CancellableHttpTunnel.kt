package net.clench.wallet.data.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/** Per-request loopback proxy: URLConnection cannot create/reopen a remote socket itself.
 * HTTPS remains end-to-end TLS in URLConnection (CONNECT), with normal hostname validation.
 * All externally connected sockets are registered BEFORE connect, so cancellation is terminal.
 */
internal class CancellableHttpTunnel(
    private val url: URL,
    private val lease: NetworkAccessGate.Lease,
    private val upstreamProxy: Proxy?,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val beforeUpstreamConnect: () -> Unit = {}
) {
    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), listener.localPort))

    init {
        lease.registerTransport { listener.close() }
        Thread({
            try { serve() } catch (_: Exception) { lease.close() }
        }, "clench-http-tunnel").apply { isDaemon = true; start() }
    }

    private fun serve() {
        val local = listener.accept()
        lease.registerTransport { local.close() }
        listener.close() // One request/connection only; no reconnect or reuse after cancellation.
        local.soTimeout = readTimeoutMs
        val input = local.getInputStream()
        val header = java.io.ByteArrayOutputStream()
        var tail = 0
        while (header.size() < 32 * 1024) {
            lease.requireCurrent()
            val next = input.read()
            if (next < 0) throw IOException("Incomplete HTTP proxy request")
            header.write(next)
            tail = (tail shl 8) or next
            if (tail == 0x0d0a0d0a) break
        }
        if (tail != 0x0d0a0d0a) throw IOException("HTTP proxy headers too large")
        val isTls = url.protocol.equals("https", ignoreCase = true)
        val firstLine = header.toString(Charsets.ISO_8859_1.name()).substringBefore("\r\n")
        if (isTls && !firstLine.startsWith("CONNECT ${url.host}:${port()} HTTP/")) {
            throw IOException("Unexpected HTTP tunnel destination")
        }
        if (!isTls && !firstLine.startsWith("GET ")) throw IOException("Unexpected HTTP method")
        lease.requireCurrent()
        val upstream = if (upstreamProxy == null) Socket() else Socket(upstreamProxy)
        lease.registerTransport { upstream.close() }
        val address = if (upstreamProxy == null) InetSocketAddress(url.host, port())
            else InetSocketAddress.createUnresolved(url.host, port())
        lease.requireCurrent()
        beforeUpstreamConnect()
        upstream.connect(address, connectTimeoutMs)
        upstream.soTimeout = readTimeoutMs
        lease.requireCurrent()
        if (isTls) {
            local.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            local.getOutputStream().flush()
        } else {
            upstream.getOutputStream().write(header.toByteArray())
            upstream.getOutputStream().flush()
        }
        Thread({ copy(upstream, local) }, "clench-http-response").apply { isDaemon = true; start() }
        copy(local, upstream)
    }

    private fun port(): Int = if (url.port >= 0) url.port else url.defaultPort

    private fun copy(source: Socket, destination: Socket) {
        try {
            val input = source.getInputStream()
            val output = destination.getOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                lease.requireCurrent()
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Exception) { }
        finally {
            // A completed response remains readable by URLConnection. Do not invalidate the
            // request's epoch merely for EOF; only the caller or a mode change closes its lease.
            runCatching { destination.shutdownOutput() }
        }
    }
}
