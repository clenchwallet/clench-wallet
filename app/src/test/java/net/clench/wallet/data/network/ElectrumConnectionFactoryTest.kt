package net.clench.wallet.data.network

import io.mockk.every
import io.mockk.mockk
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.ElectrumConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ElectrumConnectionFactoryTest {

    private fun factory(globalTorEnabled: Boolean = false): ElectrumConnectionFactory {
        val settingsManager = mockk<SettingsManager>()
        every { settingsManager.isTorEnabled() } returns globalTorEnabled
        every { settingsManager.getTorProxyHost() } returns "127.0.0.1"
        every { settingsManager.getTorProxyPort() } returns 9050
        return ElectrumConnectionFactory(settingsManager)
    }

    @Test
    fun `per-server useTor routes SSL clearnet server through Tor TLS`() {
        val resolved = factory(globalTorEnabled = false).resolveConnection(
            ElectrumConfig(
                serverUrl = "electrum.example.com",
                port = 50002,
                useSsl = true,
                useTor = true
            )
        )

        assertEquals(ConnectionMode.TOR_TLS, resolved.mode)
        assertEquals("electrum.example.com", resolved.host)
        assertEquals(50002, resolved.port)
        assertEquals("127.0.0.1", resolved.socksHost)
        assertEquals(9050, resolved.socksPort)
    }

    @Test
    fun `per-server useTor routes non-SSL clearnet server through Tor plain`() {
        val resolved = factory(globalTorEnabled = false).resolveConnection(
            ElectrumConfig(
                serverUrl = "tcp://electrum.example.com",
                port = 50001,
                useSsl = false,
                useTor = true
            )
        )

        assertEquals(ConnectionMode.TOR_PLAIN, resolved.mode)
        assertEquals("electrum.example.com", resolved.host)
        assertEquals(50001, resolved.port)
    }

    @Test
    fun `clearnet server without per-server or global Tor uses native TLS`() {
        val resolved = factory(globalTorEnabled = false).resolveConnection(
            ElectrumConfig(
                serverUrl = "ssl://electrum.example.com",
                port = 50002,
                useSsl = true,
                useTor = false
            )
        )

        assertEquals(ConnectionMode.TLS_SYSTEM, resolved.mode)
        assertEquals("electrum.example.com", resolved.host)
    }

    @Test
    fun `onion server always routes through Tor`() {
        val resolved = factory(globalTorEnabled = false).resolveConnection(
            ElectrumConfig(
                serverUrl = "exampleabcdefghijklmnop.onion",
                port = 50001,
                useSsl = false,
                useTor = false
            )
        )

        assertEquals(ConnectionMode.TOR_PLAIN, resolved.mode)
    }
}
