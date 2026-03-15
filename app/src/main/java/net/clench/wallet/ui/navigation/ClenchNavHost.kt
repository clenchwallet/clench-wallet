package net.clench.wallet.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import net.clench.wallet.ui.screens.*
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
                onWalletList = { navController.navigate(Routes.WalletList.route) }
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
    }
}
