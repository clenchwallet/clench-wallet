package net.clench.wallet.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import net.clench.wallet.ui.screens.*
import net.clench.wallet.ui.viewmodel.HomeViewModel
import net.clench.wallet.ui.viewmodel.StartupViewModel

@Composable
fun ClenchNavHost(navController: NavHostController) {
    val startupViewModel: StartupViewModel = hiltViewModel()
    val destination by startupViewModel.destination.collectAsState()

    NavHost(
        navController = navController,
        startDestination = "loading"
    ) {
        composable("loading") {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            LaunchedEffect(destination) {
                when (val dest = destination) {
                    is StartupViewModel.StartupDestination.Loading -> { /* still loading */ }
                    is StartupViewModel.StartupDestination.ServerSetup -> {
                        navController.navigate(Routes.ServerSetup.route) {
                            popUpTo("loading") { inclusive = true }
                        }
                    }
                    is StartupViewModel.StartupDestination.Welcome -> {
                        navController.navigate(Routes.Welcome.route) {
                            popUpTo("loading") { inclusive = true }
                        }
                    }
                    is StartupViewModel.StartupDestination.ExistingWallet -> {
                        navController.navigate(Routes.Home.build(dest.walletId)) {
                            popUpTo("loading") { inclusive = true }
                        }
                    }
                }
            }
        }

        composable(Routes.ServerSetup.route) {
            ServerSetupScreen(
                onServerConfigured = { config ->
                    navController.navigate(Routes.Welcome.route) {
                        popUpTo(Routes.ServerSetup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.Welcome.route) {
            WelcomeScreen(
                onCreateWallet = { navController.navigate(Routes.CreateWallet.route) },
                onImportWallet = { navController.navigate(Routes.ImportWallet.route) }
            )
        }

        composable(Routes.CreateWallet.route) {
            CreateWalletScreen(
                onWalletCreated = { walletId ->
                    navController.navigate(Routes.Home.build(walletId)) {
                        popUpTo(Routes.Welcome.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ImportWallet.route) {
            ImportWalletScreen(
                onWalletImported = { walletId ->
                    navController.navigate(Routes.Home.build(walletId)) {
                        popUpTo(Routes.Welcome.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.Home.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            HomeScreen(
                walletId = walletId,
                onSend = { navController.navigate(Routes.Send.build(walletId)) },
                onReceive = { navController.navigate(Routes.Receive.build(walletId)) },
                onSettings = { navController.navigate(Routes.Settings.route) },
                onWalletList = { navController.navigate(Routes.WalletList.route) },
                onAddresses = { navController.navigate(Routes.Addresses.build(walletId)) },
                onViewSeedPhrase = { navController.navigate(Routes.ViewSeedPhrase.build(walletId)) },
                onTransactionDetail = { txid ->
                    navController.navigate(Routes.TransactionDetail.build(walletId, txid))
                }
            )
        }

        composable(
            route = Routes.Send.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            SendScreen(
                walletId = walletId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.Receive.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            ReceiveScreen(
                walletId = walletId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onDebug = { navController.navigate(Routes.Debug.route) },
                onElectrum = { navController.navigate(Routes.SettingsElectrum.route) },
                onExplorer = { navController.navigate(Routes.SettingsExplorer.route) },
                onNetwork = { navController.navigate(Routes.SettingsNetwork.route) },
                onSecurity = { navController.navigate(Routes.SettingsSecurity.route) },
                onAbout = { navController.navigate(Routes.SettingsAbout.route) }
            )
        }

        composable(Routes.SettingsElectrum.route) {
            ElectrumServerScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SettingsExplorer.route) {
            ExplorerScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SettingsNetwork.route) {
            NetworkScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SettingsSecurity.route) {
            SecurityScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SettingsAbout.route) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onLicenses = { navController.navigate(Routes.SettingsLicenses.route) }
            )
        }

        composable(Routes.SettingsLicenses.route) {
            LicensesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.Debug.route) {
            DebugScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.Addresses.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            AddressesScreen(
                walletId = walletId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.ViewSeedPhrase.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            ViewSeedPhraseScreen(
                walletId = walletId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.WalletList.route) {
            WalletListScreen(
                onWalletSelected = { walletId ->
                    navController.navigate(Routes.Home.build(walletId)) {
                        popUpTo(Routes.WalletList.route) { inclusive = true }
                    }
                },
                onAddWallet = {
                    navController.navigate(Routes.Welcome.route)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.TransactionDetail.route,
            arguments = listOf(
                navArgument("walletId") { type = NavType.StringType },
                navArgument("txid") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            val txid = backStackEntry.arguments?.getString("txid") ?: return@composable

            // Get transaction data from the HomeViewModel's cached state
            val homeViewModel: HomeViewModel = hiltViewModel()
            val homeState by homeViewModel.uiState.collectAsState()

            // Load wallet data if not already loaded
            LaunchedEffect(walletId) { homeViewModel.load(walletId) }

            val transaction = remember(homeState.transactions, txid) {
                homeState.transactions.find { it.txid == txid }
            }

            TransactionDetailScreen(
                transaction = transaction,
                isWatchOnly = homeState.isWatchOnly,
                mempoolUrl = homeState.mempoolUrl,
                isTestnet = homeState.isTestnet,
                isOfflineMode = homeState.isOfflineMode,
                onBack = { navController.popBackStack() },
                onSpendUtxo = { utxoTxid ->
                    // Navigate to send with UTXO pre-selected
                    navController.navigate(Routes.Send.build(walletId) + "?utxoTxid=$utxoTxid")
                }
            )
        }
    }
}
