package net.clench.wallet.ui.navigation

sealed class Routes(val route: String) {
    object ServerSetup  : Routes("server_setup")
    object Welcome      : Routes("welcome")
    object CreateWallet : Routes("create_wallet")
    object ImportWallet : Routes("import_wallet")
    object Home         : Routes("home/{walletId}") {
        fun build(walletId: String) = "home/$walletId"
    }
    object Send         : Routes("send/{walletId}") {
        fun build(walletId: String) = "send/$walletId"
    }
    object Receive      : Routes("receive/{walletId}") {
        fun build(walletId: String) = "receive/$walletId"
    }
    object Settings     : Routes("settings")
    object SettingsElectrum  : Routes("settings/electrum")
    object SettingsExplorer  : Routes("settings/explorer")
    object SettingsNetwork   : Routes("settings/network")
    object SettingsSecurity  : Routes("settings/security")
    object SettingsAbout     : Routes("settings/about")
    object SettingsLicenses  : Routes("settings/licenses")
    object Debug        : Routes("debug")
    object WalletList   : Routes("wallet_list")
    object Addresses    : Routes("addresses/{walletId}") {
        fun build(walletId: String) = "addresses/$walletId"
    }
    object ViewSeedPhrase : Routes("view_seed_phrase/{walletId}") {
        fun build(walletId: String) = "view_seed_phrase/$walletId"
    }
    object TransactionDetail : Routes("tx_detail/{walletId}/{txid}") {
        fun build(walletId: String, txid: String) = "tx_detail/$walletId/$txid"
    }
    object HardwarePsbt : Routes("hw_psbt/{walletId}/{psbtBase64}/{deviceType}") {
        fun build(walletId: String, psbtBase64: String, deviceType: String): String {
            val encoded = java.net.URLEncoder.encode(psbtBase64, "UTF-8")
            return "hw_psbt/$walletId/$encoded/$deviceType"
        }
    }
}
