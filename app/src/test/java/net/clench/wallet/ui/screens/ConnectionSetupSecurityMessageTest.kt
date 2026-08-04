package net.clench.wallet.ui.screens

import net.clench.wallet.data.network.ConnectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSetupSecurityMessageTest {
    @Test
    fun `only TLS modes are described as secure`() {
        val secureModes = listOf(
            ConnectionMode.TLS_SYSTEM,
            ConnectionMode.TLS_PINNED,
            ConnectionMode.TOR_TLS
        )
        val plaintextModes = listOf(ConnectionMode.PLAIN_TCP, ConnectionMode.TOR_PLAIN)

        secureModes.forEach { mode ->
            assertTrue(connectionTestSuccessMessage(mode, "node.example", 50002).contains("securely"))
        }
        plaintextModes.forEach { mode ->
            assertFalse(connectionTestSuccessMessage(mode, "node.example", 50001).contains("securely"))
        }
    }

    @Test
    fun `plain TCP warns that it is unencrypted and local trust only`() {
        val message = connectionTestSuccessMessage(ConnectionMode.PLAIN_TCP, "192.0.2.1", 50001)

        assertTrue(message.contains("without encryption"))
        assertTrue(message.contains("trusted local node"))
    }

    @Test
    fun `Tor plain is labeled without TLS`() {
        val message = connectionTestSuccessMessage(ConnectionMode.TOR_PLAIN, "node.onion", 50001)

        assertTrue(message.contains("through Tor"))
        assertTrue(message.contains("without TLS"))
    }
}
