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
}
