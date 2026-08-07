package net.clench.wallet.domain.model

import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.junit.Assert.assertEquals
import org.junit.Test

class BdkNetworkKindTest {

    @Test
    fun `mainnet maps to main descriptor key family`() {
        assertEquals(NetworkKind.MAIN, Network.BITCOIN.toNetworkKind())
    }

    @Test
    fun `all test chains map to test descriptor key family`() {
        listOf(Network.TESTNET, Network.TESTNET4, Network.SIGNET, Network.REGTEST).forEach { network ->
            assertEquals(NetworkKind.TEST, network.toNetworkKind())
        }
    }
}
