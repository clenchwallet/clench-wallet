package net.clench.wallet.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import net.clench.wallet.ui.screens.*

@Composable
fun ClenchNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.ServerSetup.route
    ) {
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
                onSettings = { navController.navigate(Routes.Settings.route) }
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
    }
}
