package net.clench.wallet.domain.model

data class PublicServer(
    val name: String,
    val host: String,
    val port: Int,
    val useSsl: Boolean,
    val description: String
)

object PublicElectrumServers {
    val list = listOf(
        PublicServer(
            name = "Blockstream",
            host = "electrum.blockstream.info",
            port = 50002,
            useSsl = true,
            description = "Run by Blockstream. Well-known, reliable."
        ),
        // Tor .onion server removed — requires a Tor proxy that the app doesn't provide
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
}
