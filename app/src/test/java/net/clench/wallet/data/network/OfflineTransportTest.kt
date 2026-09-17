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

    @Test fun `HTTP response arriving after offline is discarded and redirect is not followed`() {
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
