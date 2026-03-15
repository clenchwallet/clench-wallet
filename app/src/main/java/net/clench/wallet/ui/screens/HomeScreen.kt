package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.domain.model.TxDirection
import net.clench.wallet.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    walletId: String,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onSettings: () -> Unit,
    onWalletList: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.walletName) },
                actions = {
                    IconButton(onClick = onWalletList) {
                        Icon(Icons.Default.Menu, contentDescription = "Wallets")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Balance card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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
                            text = "${uiState.balanceSat} sats",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
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

            // Send / Receive buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!uiState.isWatchOnly) {
                    Button(
                        onClick = onSend,
                        modifier = Modifier.weight(1f)
                    ) { Text("Send") }
                } else {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f)
                    ) { Text("Watch-only") }
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
                                Text(
                                    if (tx.direction == TxDirection.RECEIVED) "Received" else "Sent",
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            supportingContent = {
                                Text(tx.txid.take(16) + "…")
                            },
                            trailingContent = {
                                Text(
                                    "${if (tx.direction == TxDirection.RECEIVED) "+" else "-"}${tx.amountSat} sats",
                                    color = if (tx.direction == TxDirection.RECEIVED)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error
                                )
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
