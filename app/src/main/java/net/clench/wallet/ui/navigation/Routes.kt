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
    object Send         : Routes("send/{walletId}?utxo={utxo}") {
        fun build(walletId: String, utxo: String? = null): String {
            return if (utxo != null) "send/$walletId?utxo=$utxo"
            else "send/$walletId"
        }
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
    object SettingsHardwareWallet : Routes("settings/hardware_wallet")
    object SettingsLicenses  : Routes("settings/licenses")
    object SettingsPrivacy   : Routes("settings/privacy")
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
    object SeedVerification : Routes("seed_verification")
    object PassphraseConfirm : Routes("passphrase_confirm")
    object PassphraseUnlock : Routes("passphrase_unlock/{walletId}") {
        fun build(walletId: String) = "passphrase_unlock/$walletId"
    }
    object UtxoList : Routes("utxo_list/{walletId}") {
        fun build(walletId: String) = "utxo_list/$walletId"
    }
    object Backup : Routes("backup/{walletId}") {
        fun build(walletId: String) = "backup/$walletId"
    }
    object HardwarePsbt : Routes("hw_psbt/{walletId}/{deviceType}") {
        fun build(walletId: String, deviceType: String): String {
            return "hw_psbt/$walletId/$deviceType"
        }
    }
    object Sweep : Routes("sweep/{walletId}") {
        fun build(walletId: String) = "sweep/$walletId"
    }
    object SecurityOnboarding : Routes("security_onboarding")
    object CreateMultisig : Routes("create_multisig")
}
