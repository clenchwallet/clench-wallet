package net.clench.wallet.data.network

import android.util.Log
import io.mockk.*
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.ElectrumConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OfflineTransportTest {
    private var offline = false
    private val gate = NetworkAccessGate { offline }
    private val settings = mockk<SettingsManager>()

    @Before fun setup() {
        every { settings.networkAccess } returns gate
        every { settings.isTorEnabled() } returns false
        every { settings.getTorProxyHost() } returns "127.0.0.1"
        every { settings.getTorProxyPort() } returns 9050
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }
    @After fun cleanup() = unmockkAll()

    @Test fun `offline rejects HTTP raw and native Electrum before DNS or connection`() {
        offline = true
        val config = ElectrumConfig(serverUrl = "must-not-resolve.invalid", useSsl = false)
        assertTrue(runCatching { TorAwareHttpClient(settings).fetchText("https://must-not-resolve.invalid") }.exceptionOrNull() is IOException)
        assertTrue(runCatching { ElectrumConnectionFactory(settings).createRawSocket(config) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { ElectrumConnectionFactory(settings).createConnection(config) }.exceptionOrNull() is IOException)
    }

    @Test fun `reused raw socket cannot write after offline and online recovers`() {
        ServerSocket(0).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val received = worker.submit<Int> {
                    server.accept().use { peer ->
                        assertEquals(7, peer.getInputStream().read())
                        peer.getInputStream().read()
                    }
                }
                val config = ElectrumConfig(serverUrl = "127.0.0.1", port = server.localPort, useSsl = false)
                ElectrumConnectionFactory(settings).createRawSocket(config).use { socket ->
                    val output = socket.getOutputStream()
                    output.write(7)
                    gate.changeMode { offline = true }
                    assertTrue(runCatching { output.write(8) }.isFailure)
                    gate.changeMode { offline = false }
                    assertTrue(runCatching { output.write(9) }.isFailure)
                }
                assertEquals(-1, received.get(5, TimeUnit.SECONDS))
                val again = worker.submit<Int> { server.accept().use { it.getInputStream().read() } }
                ElectrumConnectionFactory(settings).createRawSocket(config).use { it.getOutputStream().write(10) }
                assertEquals(10, again.get(5, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun `delayed URLConnection cannot connect remotely after tunnel cancellation`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 250
            val target = java.net.URL("http://127.0.0.1:${server.localPort}/")
            val lease = gate.begin()
            val tunnel = CancellableHttpTunnel(target, lease, null, 1000, 1000)
            val connection = target.openConnection(tunnel.proxy) as java.net.HttpURLConnection
            lease.register { connection.disconnect() }
            gate.changeMode { offline = true }
            gate.changeMode { offline = false }
            // This is the old race: disconnect before responseCode. It may reconnect only
            // to the already-closed loopback listener, never to the Internet destination.
            assertTrue(runCatching { connection.responseCode }.isFailure)
            assertTrue(runCatching { server.accept() }.exceptionOrNull() is java.net.SocketTimeoutException)
        }
    }

    @Test fun `already admitted delayed connect cannot start after offline returns`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 250
            val target = java.net.URL("http://127.0.0.1:${server.localPort}/")
            val lease = gate.begin()
            val atConnect = java.util.concurrent.CountDownLatch(1)
            val releaseConnect = java.util.concurrent.CountDownLatch(1)
            val tunnel = CancellableHttpTunnel(target, lease, null, 1000, 1000) {
                atConnect.countDown(); releaseConnect.await(5, TimeUnit.SECONDS)
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val request = worker.submit<Boolean> { runCatching { (target.openConnection(tunnel.proxy) as java.net.HttpURLConnection).responseCode }.isFailure }
                assertTrue(atConnect.await(5, TimeUnit.SECONDS))
                gate.changeMode { offline = true }
                releaseConnect.countDown()
                assertTrue(request.get(5, TimeUnit.SECONDS))
                assertTrue(runCatching { server.accept() }.exceptionOrNull() is java.net.SocketTimeoutException)
            } finally { releaseConnect.countDown(); lease.close(); worker.shutdownNow() }
        }
    }

    @Test fun `HTTP redirects cannot launch an unreviewed follow-up request`() {
        ServerSocket(0).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val served = worker.submit {
                    server.accept().use { peer ->
                        val reader = peer.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        peer.getOutputStream().write("HTTP/1.1 302 Found\r\nContent-Length: 0\r\nLocation: http://must-not-resolve.invalid/\r\n\r\n".toByteArray())
                    }
                }
                assertTrue(runCatching { TorAwareHttpClient(settings).fetchText("http://127.0.0.1:${server.localPort}/") }.isFailure)
                served.get(5, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun `online HTTP response succeeds through cancellable tunnel`() {
        ServerSocket(0).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val served = worker.submit {
                    server.accept().use { peer ->
                        val reader = peer.getInputStream().bufferedReader()
                        assertTrue(reader.readLine().startsWith("GET "))
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        peer.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n".toByteArray())
                    }
                }
                assertEquals("ok", TorAwareHttpClient(settings).fetchText("http://127.0.0.1:${server.localPort}/"))
                served.get(5, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun `HTTP Tor mode uses SOCKS domain routing without direct fallback`() {
        ServerSocket(0).use { proxy ->
            every { settings.isTorEnabled() } returns true
            every { settings.getTorProxyPort() } returns proxy.localPort
            val worker = Executors.newSingleThreadExecutor()
            try {
                val served = worker.submit {
                    proxy.accept().use { peer ->
                        val input = java.io.DataInputStream(peer.getInputStream())
                        val output = peer.getOutputStream()
                        assertEquals(5, input.readUnsignedByte())
                        repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                        output.write(byteArrayOf(5, 0)); output.flush()
                        assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                        input.readUnsignedByte(); assertEquals(3, input.readUnsignedByte())
                        val host = ByteArray(input.readUnsignedByte()); input.readFully(host)
                        assertEquals("must-not-resolve.invalid", host.toString(Charsets.US_ASCII))
                        assertEquals(80, input.readUnsignedShort())
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); output.flush()
                        val reader = input.bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\ntor".toByteArray())
                    }
                }
                assertEquals("tor", TorAwareHttpClient(settings).fetchText("http://must-not-resolve.invalid/"))
                served.get(5, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun `HTTPS tunnel preserves certificate trust and original hostname verification`() {
        // Public test-only key/certificate, generated solely for this loopback fixture.
        val store = java.security.KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/network/public-localhost-fixture.p12")!!.use {
            store.load(it, "public-test-fixture".toCharArray())
        }
        val keys = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, "public-test-fixture".toCharArray())
        val trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        val tls = javax.net.ssl.SSLContext.getInstance("TLS")
        tls.init(keys.keyManagers, trust.trustManagers, null)
        val originalFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
        try {
            (tls.serverSocketFactory.createServerSocket(0) as javax.net.ssl.SSLServerSocket).use { server ->
                val worker = Executors.newSingleThreadExecutor()
                try {
                    val served = worker.submit {
                        server.accept().use { peer ->
                            val reader = peer.getInputStream().bufferedReader()
                            while (reader.readLine()?.isNotEmpty() == true) { }
                            peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\ntls".toByteArray())
                        }
                    }
                    assertEquals("tls", TorAwareHttpClient(settings).fetchText("https://localhost:${server.localPort}/"))
                    served.get(5, TimeUnit.SECONDS)
                    val rejected = worker.submit { runCatching { server.accept().use { it.getInputStream().read() } } }
                    // The proxy uses loopback too; verification must still use original URL host.
                    assertTrue(runCatching { TorAwareHttpClient(settings).fetchText("https://127.0.0.1:${server.localPort}/") }.isFailure)
                    rejected.get(5, TimeUnit.SECONDS)
                } finally { worker.shutdownNow() }
            }
        } finally { javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory) }
    }

    @Test fun `HTTP response arriving after offline is discarded`() {
        ServerSocket(0).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val served = worker.submit {
                    server.accept().use { peer ->
                        val reader = peer.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        gate.changeMode { offline = true }
                        runCatching { peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()) }
                    }
                }
                assertTrue(runCatching { TorAwareHttpClient(settings).fetchText("http://127.0.0.1:${server.localPort}/") }.isFailure)
                served.get(5, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }
}
