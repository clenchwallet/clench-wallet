package net.clench.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import net.clench.wallet.domain.model.TxDirection
import net.clench.wallet.ui.viewmodel.BalanceUnit
import net.clench.wallet.ui.viewmodel.HomeViewModel
import net.clench.wallet.domain.repository.WalletStateRecoveryPolicy
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    walletId: String,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onSettings: () -> Unit,
    onWalletList: () -> Unit = {},
    onAddresses: () -> Unit = {},
    onUtxoList: () -> Unit = {},
    onSweep: () -> Unit = {},
    onFundSatscard: () -> Unit = {},
    onSignerVault: () -> Unit = {},
    onRawTransaction: () -> Unit = {},
    onRecoveryWizard: () -> Unit = {},
    onConnect: () -> Unit = {},
    onTransactionDetail: (txid: String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var showAdvancedTools by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(walletId) { viewModel.load(walletId) }
    LaunchedEffect(uiState.walletStateRecoveryRequired) {
        if (uiState.walletStateRecoveryRequired) showRecoveryDialog = true
    }
    LifecycleResumeEffect(walletId) {
        viewModel.refreshUsdPriceIfVisible()
        onPauseOrDispose { }
    }

    if (showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isRecoveringWalletState) showRecoveryDialog = false },
            title = { Text("Wallet-State Recovery") },
            text = {
                WalletStateRecoveryCard(
                    recoveryRequired = uiState.walletStateRecoveryRequired,
                    isRecovering = uiState.isRecoveringWalletState,
                    stopGap = uiState.recoveryStopGap,
                    status = uiState.recoveryStatus,
                    quarantineFileCount = uiState.recoveryPreservedFileCount,
                    hasQuarantine = uiState.recoveryQuarantineId != null,
                    verificationConfirmed = uiState.recoveryVerificationConfirmed,
                    isDeleting = uiState.isDeletingRecoveryQuarantine,
                    isOfflineMode = uiState.isOfflineMode,
                    onStopGapChange = viewModel::setRecoveryStopGap,
                    onRecover = viewModel::recoverWalletState,
                    onConnect = onConnect,
                    onVerificationChange = viewModel::confirmRecoveryVerification,
                    onDeleteQuarantine = viewModel::deleteVerifiedRecoveryQuarantine,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showRecoveryDialog = false },
                    enabled = !uiState.isRecoveringWalletState && !uiState.isDeletingRecoveryQuarantine
                ) { Text("Done") }
            }
        )
    }

    if (showAdvancedTools) {
        AlertDialog(
            onDismissRequest = { showAdvancedTools = false },
            title = { Text("Advanced Tools") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "These tools can move funds or broadcast transactions with fewer built-in wallet checks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            showAdvancedTools = false
                            onSweep()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sweep External Seed")
                    }
                    OutlinedButton(
                        onClick = {
                            showAdvancedTools = false
                            onFundSatscard()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Fund SATSCARD")
                    }
                    OutlinedButton(
                        onClick = {
                            showAdvancedTools = false
                            onRawTransaction()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Broadcast Raw Transaction")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAdvancedTools = false }) {
                    Text("Close")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(uiState.walletName)
                        if (uiState.isTorEnabled) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("🧅", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onWalletList) {
                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Wallets")
                    }
                    IconButton(onClick = onSignerVault) {
                        Icon(Icons.Default.VpnKey, contentDescription = "Signers")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Wallet Info") },
                                onClick = {
                                    showMenu = false
                                    onAddresses()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Coin Control (UTXOs)") },
                                onClick = {
                                    showMenu = false
                                    onUtxoList()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Recovery Wizard") },
                                onClick = {
                                    showMenu = false
                                    onRecoveryWizard()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Advanced Tools") },
                                onClick = {
                                    showMenu = false
                                    showAdvancedTools = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        PullToRefreshBox(
            isRefreshing = uiState.isSyncing,
            onRefresh = { viewModel.reload(walletId) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Testnet banner
            if (uiState.isTestnet) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFF9800))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "⚠ TESTNET",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Offline mode banner
            if (uiState.isOfflineMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFC107))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📡 Offline — balance may be outdated and network actions are unavailable.",
                        color = Color.Black,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onConnect) { Text("Connect") }
                }
            }

            // Balance card — tap to cycle sats/BTC/USD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable { viewModel.cycleBalanceUnit() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Balance", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.isLoading) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            text = HomeViewModel.formatBalance(
                                uiState.balanceSat,
                                uiState.balanceUnit,
                                uiState.btcPriceUsd,
                                uiState.priceStale
                            ),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (uiState.balanceUnit == BalanceUnit.USD && uiState.btcPriceUsd == null) {
                            val usdStatus = when {
                                !uiState.btcPriceEnabled -> "Enable USD Balance in Settings"
                                uiState.isOfflineMode -> "Offline mode blocks BTC/USD price"
                                uiState.priceStale -> "BTC/USD price unavailable; pull to refresh"
                                else -> "Fetching BTC/USD price..."
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = usdStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                        // R7-19: Show pending amount separately if there's pending balance
                        if (uiState.pendingSat > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "(${HomeViewModel.formatBalance(uiState.pendingSat, uiState.balanceUnit, uiState.btcPriceUsd, uiState.priceStale)} pending)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (uiState.frozenUtxoCount > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "❄️ Frozen: ${HomeViewModel.formatBalance(uiState.frozenSat, uiState.balanceUnit, uiState.btcPriceUsd, uiState.priceStale)} (${uiState.frozenUtxoCount} UTXO${if (uiState.frozenUtxoCount == 1) "" else "s"})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Spendable: ${HomeViewModel.formatBalance((uiState.balanceSat - uiState.frozenSat).coerceAtLeast(0L), uiState.balanceUnit, uiState.btcPriceUsd, uiState.priceStale)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (uiState.isSyncing) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Syncing...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Sync error banner
            uiState.syncError?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "⚠\uFE0F $err",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            uiState.error?.let { err ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "⚠️ $err",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (uiState.walletStateRecoveryRequired || uiState.recoveryStatus != null || uiState.recoveryQuarantineId != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wallet recovery requires attention", fontWeight = FontWeight.Bold)
                            Text(
                                uiState.recoveryStatus ?: "The original state is preserved; choose a recovery scan depth.",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                        }
                        TextButton(onClick = { showRecoveryDialog = true }) { Text("Open") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Send / Receive buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!uiState.isWatchOnly) {
                    Button(
                        onClick = { if (uiState.isOfflineMode) onConnect() else onSend() },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (uiState.isOfflineMode) "Connect to Send" else "Send") }
                } else {
                    Button(
                        onClick = { if (uiState.isOfflineMode) onConnect() else onSend() },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (uiState.isOfflineMode) "Connect to Create PSBT" else "Send") }
                }

                Button(
                    onClick = onReceive,
                    modifier = Modifier.weight(1f)
                ) { Text("Receive") }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Transaction history
            Text(
                "Transactions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (uiState.transactions.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (uiState.isSyncing) "Syncing wallet..." else "No transactions yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(uiState.transactions) { tx ->
                        ListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (tx.direction == TxDirection.RECEIVED) "Received" else "Sent",
                                        fontWeight = FontWeight.Medium
                                    )
                                    // Confirmation indicator
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (tx.confirmations > 0) {
                                        Text(
                                            "✓",
                                            color = Color(0xFF43A047),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    } else {
                                        Text(
                                            "⚠",
                                            color = Color(0xFFE53935),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            },
                            supportingContent = {
                                Column {
                                    // Show label if present (truncated to 40 chars)
                                    tx.label?.let { label ->
                                        Text(
                                            text = if (label.length > 40) label.take(40) + "…" else label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1
                                        )
                                    }
                                    Text(tx.txid.take(8) + "…" + tx.txid.takeLast(6))
                                    // Show fee for sent transactions
                                    if (tx.direction == TxDirection.SENT && tx.feeSat != null) {
                                        Text(
                                            "Fee: ${NumberFormat.getNumberInstance(Locale.US).format(tx.feeSat)} sats",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                val fmt = NumberFormat.getNumberInstance(Locale.US)
                                Text(
                                    "${if (tx.direction == TxDirection.RECEIVED) "+" else "-"}${fmt.format(tx.amountSat)} sats",
                                    color = if (tx.direction == TxDirection.RECEIVED)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error
                                )
                            },
                            modifier = Modifier.clickable {
                                onTransactionDetail(tx.txid)
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        } // end PullToRefreshBox
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalletStateRecoveryCard(
    recoveryRequired: Boolean,
    isRecovering: Boolean,
    stopGap: Int,
    status: String?,
    quarantineFileCount: Int,
    hasQuarantine: Boolean,
    verificationConfirmed: Boolean,
    isDeleting: Boolean,
    isOfflineMode: Boolean,
    onStopGapChange: (Int) -> Unit,
    onRecover: () -> Unit,
    onConnect: () -> Unit,
    onVerificationChange: (Boolean) -> Unit,
    onDeleteQuarantine: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Guided Wallet-State Recovery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Clench preserves the unreadable database in app-private quarantine before rebuilding from the saved public descriptors. Previously revealed but unused addresses cannot be rediscovered from the blockchain.",
                style = MaterialTheme.typography.bodySmall
            )

            if (recoveryRequired && !isRecovering) {
                Text("Choose scan depth", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WalletStateRecoveryPolicy.recommendedStopGaps.forEach { gap ->
                        FilterChip(
                            selected = stopGap == gap,
                            onClick = { onStopGapChange(gap) },
                            label = { Text("$gap") }
                        )
                    }
                }
                Text(
                    "Larger gaps discover farther-used addresses but take longer and disclose more derived addresses to the configured Electrum server.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = { if (isOfflineMode) onConnect() else onRecover() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isOfflineMode) "Connect before recovery" else "Preserve state and scan with gap $stopGap")
                }
            }

            if (isRecovering) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(status ?: "Recovering wallet state…", style = MaterialTheme.typography.bodySmall)
                }
            } else if (status != null) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            if (hasQuarantine) {
                Text(
                    "$quarantineFileCount pre-recovery file${if (quarantineFileCount == 1) " is" else "s are"} still preserved.",
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(checked = verificationConfirmed, onCheckedChange = onVerificationChange)
                    Text(
                        "I verified the network, first receive address, balance, and transaction history against my backup or previous wallet.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                OutlinedButton(
                    onClick = onDeleteQuarantine,
                    enabled = verificationConfirmed && !isDeleting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isDeleting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Delete verified preserved state")
                }
            }
        }
    }
}
