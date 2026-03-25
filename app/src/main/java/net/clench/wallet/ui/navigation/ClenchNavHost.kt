package net.clench.wallet.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.screens.*
import net.clench.wallet.ui.viewmodel.CreateWalletViewModel
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

            // Re-evaluate startup destination every time we land on loading
            // (handles network switches, wallet deletion, fresh app start)
            LaunchedEffect(Unit) {
                startupViewModel.refresh()
            }

            LaunchedEffect(destination) {
                when (val dest = destination) {
                    is StartupViewModel.StartupDestination.Loading -> { /* still loading */ }
                    is StartupViewModel.StartupDestination.NetworkChoice -> {
                        navController.navigate(Routes.NetworkChoice.route) {
                            popUpTo("loading") { inclusive = true }
                        }
                    }
                    is StartupViewModel.StartupDestination.ServerSetup -> {
                        navController.navigate(Routes.ConnectionSetup.route) {
                            popUpTo("loading") { inclusive = true }
                        }
                    }
                    is StartupViewModel.StartupDestination.Welcome -> {
                        navController.navigate(Routes.Welcome.route) {
                            popUpTo("loading") { inclusive = true }
                        }
                    }
                    is StartupViewModel.StartupDestination.ExistingWallet -> {
                        if (dest.needsPassphrase) {
                            navController.navigate(Routes.PassphraseUnlock.build(dest.walletId)) {
                                popUpTo("loading") { inclusive = true }
                            }
                        } else {
                            navController.navigate(Routes.Home.build(dest.walletId)) {
                                popUpTo("loading") { inclusive = true }
                            }
                        }
                    }
                }
            }
        }

        composable(Routes.NetworkChoice.route) {
            NetworkChoiceScreen(
                onNetworkSelected = {
                    navController.navigate(Routes.ConnectionSetup.route)
                }
            )
        }

        composable(Routes.ConnectionSetup.route) {
            ConnectionSetupScreen(
                onComplete = {
                    navController.navigate(Routes.SecurityOnboarding.route) {
                        popUpTo(Routes.NetworkChoice.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.SecurityOnboarding.route) {
            SecurityOnboardingScreen(
                onComplete = {
                    navController.navigate(Routes.Welcome.route) {
                        popUpTo(Routes.SecurityOnboarding.route) { inclusive = true }
                    }
                }
            )
        }

        // Backwards-compat alias: ServerSetup → ConnectionSetup
        composable(Routes.ServerSetup.route) {
            ConnectionSetupScreen(
                onComplete = {
                    navController.navigate(Routes.Welcome.route) {
                        popUpTo(Routes.ServerSetup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.Welcome.route) {
            // Show back button if there's a previous destination to return to
            val canGoBack = navController.previousBackStackEntry != null

            WelcomeScreen(
                onCreateWallet = { navController.navigate(Routes.CreateWallet.route) },
                onImportWallet = { navController.navigate(Routes.ImportWallet.route) },
                onBack = if (canGoBack) {
                    { navController.popBackStack() }
                } else null,
                onSettings = { navController.navigate(Routes.Settings.route) },
                onCreateMultisig = { navController.navigate(Routes.CreateMultisig.route) }
            )
        }

        navigation(
            startDestination = "create_wallet_main",
            route = Routes.CreateWallet.route
        ) {
            composable("create_wallet_main") { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(Routes.CreateWallet.route)
                }
                val viewModel: CreateWalletViewModel = hiltViewModel(parentEntry)
                CreateWalletScreen(
                    onWalletCreated = { walletId ->
                        navController.navigate(Routes.Home.build(walletId)) {
                            popUpTo(Routes.Welcome.route) { inclusive = true }
                        }
                    },
                    onNavigateSeedVerification = {
                        navController.navigate(Routes.SeedVerification.route)
                    },
                    onBack = { navController.popBackStack() },
                    onSettings = { navController.navigate(Routes.Settings.route) },
                    viewModel = viewModel
                )
            }

            composable(Routes.SeedVerification.route) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(Routes.CreateWallet.route)
                }
                val viewModel: CreateWalletViewModel = hiltViewModel(parentEntry)
                SeedVerificationScreen(
                    onVerified = {
                        // No passphrase in create flow - save directly
                        viewModel.confirmAndSave { walletId ->
                            navController.navigate(Routes.Home.build(walletId)) {
                                popUpTo(Routes.Welcome.route) { inclusive = true }
                            }
                        }
                    },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }

            // PassphraseConfirm route removed - passphrase wallets can only be created via Import flow
        }

        composable(Routes.CreateMultisig.route) {
            CreateMultisigScreen(
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
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Routes.Settings.route) }
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
                onAddresses = { navController.navigate(Routes.WalletInfo.build(walletId)) },
                onViewSeedPhrase = { navController.navigate(Routes.ViewSeedPhrase.build(walletId)) },
                onUtxoList = { navController.navigate(Routes.UtxoList.build(walletId)) },
                onSweep = { navController.navigate(Routes.Sweep.build(walletId)) },
                onTransactionDetail = { txid ->
                    navController.navigate(Routes.TransactionDetail.build(walletId, txid))
                }
            )
        }

        composable(
            route = Routes.Send.route,
            arguments = listOf(
                navArgument("walletId") { type = NavType.StringType },
                navArgument("utxo") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            val utxoFromRoute = backStackEntry.arguments?.getString("utxo")
            val selectedUtxos = backStackEntry.savedStateHandle.get<String>("selectedUtxos")
            SendScreen(
                walletId = walletId,
                utxoOutpoint = utxoFromRoute,
                selectedUtxos = selectedUtxos,
                onBack = { navController.popBackStack() },
                onNavigateHardwarePsbt = { wId, _, deviceType ->
                    // PSBT already stored in PsbtStore by SendViewModel.storePsbtForNavigation()
                    navController.navigate(Routes.HardwarePsbt.build(wId, deviceType.name))
                }
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
                onAbout = { navController.navigate(Routes.SettingsAbout.route) },
                onHardwareWallet = { navController.navigate(Routes.SettingsHardwareWallet.route) }
            )
        }

        composable(Routes.SettingsElectrum.route) {
            ElectrumServerScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SettingsExplorer.route) {
            ExplorerScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SettingsNetwork.route) {
            NetworkScreen(
                onBack = { navController.popBackStack() },
                // R7-6: After network switch, navigate back to loading to re-evaluate startup
                onNetworkSwitched = {
                    navController.navigate("loading") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.SettingsSecurity.route) {
            SecurityScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SettingsHardwareWallet.route) {
            HardwareWalletSettingsScreen(onBack = { navController.popBackStack() })
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

        // Part 2: WalletInfo screen (replaces Addresses)
        composable(
            route = Routes.WalletInfo.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            WalletInfoScreen(
                walletId = walletId,
                onBack = { navController.popBackStack() },
                onViewAddresses = { navController.navigate(Routes.AddressList.build(walletId)) },
                onBackup = { navController.navigate(Routes.Backup.build(walletId)) }
            )
        }

        // Part 2: AddressList screen
        composable(
            route = Routes.AddressList.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            AddressListScreen(
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
                onWalletSelected = { walletId, needsPassphrase, identiconBytes ->
                    if (needsPassphrase) {
                        // Navigate to passphrase unlock screen
                        navController.navigate(Routes.PassphraseUnlock.build(walletId))
                    } else {
                        // Navigate directly to home
                        navController.navigate(Routes.Home.build(walletId)) {
                            popUpTo(Routes.WalletList.route) { inclusive = true }
                        }
                    }
                },
                onAddWallet = {
                    navController.navigate(Routes.Welcome.route)
                },
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Routes.Settings.route) },
                onNavigateWelcome = {
                    // Pop entire back stack so swiping back doesn't return to deleted wallet
                    navController.navigate(Routes.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateHome = { walletId ->
                    // After deleting a wallet when others remain — go to first remaining wallet
                    navController.navigate(Routes.Home.build(walletId)) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Passphrase unlock screen - shown when opening a passphrase wallet
        composable(
            route = Routes.PassphraseUnlock.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            
            // Get wallet info to pass to the unlock screen
            val startupViewModel: StartupViewModel = hiltViewModel()
            val wallets by startupViewModel.wallets.collectAsState()
            val wallet = wallets.find { it.id == walletId }

            PassphraseUnlockScreen(
                walletId = walletId,
                walletName = wallet?.name ?: "Wallet",
                storedIdenticonBytes = wallet?.identiconBytes,
                onUnlocked = {
                    // Pop PassphraseUnlock off the back stack so swiping back from Home
                    // goes to WalletList, not back to the passphrase entry screen.
                    navController.navigate(Routes.Home.build(walletId)) {
                        popUpTo(Routes.PassphraseUnlock.build(walletId)) { inclusive = true }
                    }
                },
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.WalletList.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onSwitchWallet = {
                    navController.navigate(Routes.WalletList.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
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

            // R7-9: Get transaction data from HomeViewModel without triggering a new sync
            val homeViewModel: HomeViewModel = hiltViewModel()
            val homeState by homeViewModel.uiState.collectAsState()

            // Only load if not already loaded (avoid redundant sync)
            LaunchedEffect(walletId) {
                if (homeState.transactions.isEmpty()) {
                    homeViewModel.load(walletId)
                }
            }

            val transaction = remember(homeState.transactions, txid) {
                homeState.transactions.find { it.txid == txid }
            }

            // RBF bump fee state
            var isBumping by remember { mutableStateOf(false) }
            var bumpError by remember { mutableStateOf<String?>(null) }
            val coroutineScope = rememberCoroutineScope()

            TransactionDetailScreen(
                transaction = transaction,
                isWatchOnly = homeState.isWatchOnly,
                mempoolUrl = homeState.mempoolUrl,
                isTestnet = homeState.isTestnet,
                isOfflineMode = homeState.isOfflineMode,
                isBumping = isBumping,
                bumpError = bumpError,
                onBack = { navController.popBackStack() },
                onSpendUtxo = { utxoTxid ->
                    // Navigate directly to Send with this UTXO pre-selected via route parameter
                    navController.navigate(Routes.Send.build(walletId, "$utxoTxid:0"))
                },
                onBumpFee = if (homeState.isWatchOnly) null else { bumpTxid, newFeeRate ->
                    coroutineScope.launch {
                        isBumping = true
                        bumpError = null
                        try {
                            val repo = homeViewModel.bitcoinRepository
                            val txHex = repo.bumpFee(walletId, bumpTxid, newFeeRate)
                            val config = homeViewModel.settingsManager.loadElectrumConfig()
                            repo.broadcastTransaction(config, txHex)
                            // Success — go back and refresh
                            navController.popBackStack()
                        } catch (e: Exception) {
                            bumpError = e.message ?: "Failed to bump fee"
                        } finally {
                            isBumping = false
                        }
                    }
                },
                onSaveLabel = { labelTxid, label ->
                    coroutineScope.launch {
                        homeViewModel.bitcoinRepository.setTransactionLabel(walletId, labelTxid, label)
                        // Refresh transactions to reflect the new label
                        homeViewModel.reload(walletId)
                    }
                }
            )
        }

        composable(
            route = Routes.UtxoList.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            UtxoListScreen(
                walletId = walletId,
                onBack = { navController.popBackStack() },
                onSpendSelected = { wId, outpoints ->
                    // Pass selected outpoints via route URL — savedStateHandle on currentBackStackEntry
                    // is not visible to the destination screen
                    val utxosParam = outpoints.joinToString(",")
                    navController.navigate(Routes.Send.build(wId) + "?utxo=$utxosParam")
                }
            )
        }

        composable(
            route = Routes.Backup.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            BackupScreen(
                walletId = walletId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.HardwarePsbt.route,
            arguments = listOf(
                navArgument("walletId") { type = NavType.StringType },
                navArgument("deviceType") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            val deviceTypeName = backStackEntry.arguments?.getString("deviceType") ?: return@composable

            val deviceType = try {
                HardwareWalletType.valueOf(deviceTypeName)
            } catch (_: Exception) {
                HardwareWalletType.SEEDSIGNER
            }

            // PSBT retrieved from PsbtStore in ViewModel (not from nav args)
            HardwareWalletPsbtScreen(
                walletId = walletId,
                deviceType = deviceType,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.Sweep.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: return@composable
            SweepScreen(
                walletId = walletId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
