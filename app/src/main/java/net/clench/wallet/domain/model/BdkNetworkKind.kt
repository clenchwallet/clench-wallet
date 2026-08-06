package net.clench.wallet.domain.model

import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind

/**
 * Maps a concrete chain to the key/descriptor version-byte family required by BDK 3.
 *
 * BDK intentionally groups every non-mainnet chain under [NetworkKind.TEST]; concrete
 * network validation still uses [Network] when constructing wallets and addresses.
 */
internal fun Network.toNetworkKind(): NetworkKind = when (this) {
    Network.BITCOIN -> NetworkKind.MAIN
    Network.TESTNET,
    Network.TESTNET4,
    Network.SIGNET,
    Network.REGTEST -> NetworkKind.TEST
}
