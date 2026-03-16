package net.clench.wallet.ui.navigation

sealed class Routes(val route: String) {
    object NetworkChoice    : Routes("network_choice")
    object ConnectionSetup  : Routes("connection_setup")
    object ServerSetup  : Routes("server_setup") // alias → ConnectionSetup
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
    // Part 2: Replaced Addresses with WalletInfo and AddressList
    object WalletInfo   : Routes("wallet_info/{walletId}") {
        fun build(walletId: String) = "wallet_info/$walletId"
    }
    object AddressList  : Routes("address_list/{walletId}") {
        fun build(walletId: String) = "address_list/$walletId"
    }
    object ViewSeedPhrase : Routes("view_seed_phrase/{walletId}") {
        fun build(walletId: String) = "view_seed_phrase/$walletId"
    }
    object TransactionDetail : Routes("tx_detail/{walletId}/{txid}") {
        fun build(walletId: String, txid: String) = "tx_detail/$walletId/$txid"
    }
    object HardwarePsbt : Routes("hw_psbt/{walletId}/{deviceType}") {
        fun build(walletId: String, deviceType: String): String {
            return "hw_psbt/$walletId/$deviceType"
        }
    }
}
