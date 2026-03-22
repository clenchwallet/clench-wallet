package net.clench.wallet.domain.model

data class PublicServer(
    val name: String,
    val host: String,
    val port: Int,
    val useSsl: Boolean,
    val description: String
)

object PublicElectrumServers {
    val mainnet = listOf(
        PublicServer(
            name = "Blockstream",
            host = "electrum.blockstream.info",
            port = 50002,
            useSsl = true,
            description = "Run by Blockstream. Well-known, reliable."
        ),
        PublicServer(
            name = "LunaNode",
            host = "electrum.lunanode.com",
            port = 50002,
            useSsl = true,
            description = "Community-run server by LunaNode."
        ),
        PublicServer(
            name = "Bitaroo",
            host = "electrum.bitaroo.net",
            port = 50002,
            useSsl = true,
            description = "Australian Bitcoin exchange server."
        ),
        PublicServer(
            name = "LTYK",
            host = "electrum.ltyk.net",
            port = 50002,
            useSsl = true,
            description = "Community-run server."
        ),
        PublicServer(
            name = "hodlister.co",
            host = "hodlister.co",
            port = 50002,
            useSsl = true,
            description = "Community-run server."
        ),
        PublicServer(
            name = "Bitcoin.de",
            host = "btc.bitcoinde.electrum.dragonflydb.io",
            port = 50002,
            useSsl = true,
            description = "Bitcoin.de community server."
        )
    )

    val testnet = listOf(
        PublicServer(
            name = "Aranguren Testnet",
            host = "testnet.aranguren.org",
            port = 51001,
            useSsl = false,
            description = "Community testnet server (TCP). Reliable and well-synced."
        ),
        PublicServer(
            name = "Blockstream Testnet (SSL)",
            host = "electrum.blockstream.info",
            port = 60002,
            useSsl = true,
            description = "Blockstream testnet server (SSL). May lag behind chain tip."
        ),
        PublicServer(
            name = "Blockstream Testnet (TCP)",
            host = "electrum.blockstream.info",
            port = 60001,
            useSsl = false,
            description = "Blockstream testnet server (TCP). May lag behind chain tip."
        )
    )

    /** Returns the server list for the given network. */
    fun forNetwork(isTestnet: Boolean): List<PublicServer> =
        if (isTestnet) testnet else mainnet

    /** @deprecated Use forNetwork() instead */
    val list get() = mainnet
}
