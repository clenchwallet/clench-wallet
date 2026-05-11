package net.clench.wallet.ui.navigation

import android.net.Uri

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
    object Send         : Routes("send/{walletId}?utxo={utxo}&cpfp={cpfp}&address={address}&label={label}") {
        fun build(
            walletId: String,
            utxo: String? = null,
            cpfp: Boolean = false,
            address: String? = null,
            label: String? = null
        ): String {
            val params = mutableListOf<String>()
            if (utxo != null) params += "utxo=${Uri.encode(utxo)}"
            if (cpfp) params += "cpfp=true"
            if (!address.isNullOrBlank()) params += "address=${Uri.encode(address)}"
            if (!label.isNullOrBlank()) params += "label=${Uri.encode(label)}"
            return if (params.isNotEmpty()) "send/$walletId?${params.joinToString("&")}"
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
    object PhoneSignerPsbt : Routes("phone_psbt/{walletId}") {
        fun build(walletId: String) = "phone_psbt/$walletId"
    }
    object Sweep : Routes("sweep/{walletId}") {
        fun build(walletId: String) = "sweep/$walletId"
    }
    object FundSatscard : Routes("fund_satscard/{walletId}") {
        fun build(walletId: String) = "fund_satscard/$walletId"
    }
    object RawTransaction : Routes("raw_tx/{walletId}") {
        fun build(walletId: String) = "raw_tx/$walletId"
    }
    object RecoveryWizard : Routes("recovery_wizard")
    object SecurityOnboarding : Routes("security_onboarding")
    object CreateMultisig : Routes("create_multisig")
    object ImportHardwareWallet : Routes("import_wallet_hw")
    object SignerVault : Routes("signer_vault")
}
