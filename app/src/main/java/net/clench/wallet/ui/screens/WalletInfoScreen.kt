package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.components.QrCodeImage
import net.clench.wallet.ui.viewmodel.WalletInfoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletInfoScreen(
    walletId: String,
    onBack: () -> Unit,
    onViewAddresses: () -> Unit = {},
    onBackup: () -> Unit = {},
    viewModel: WalletInfoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showQrDialog by remember { mutableStateOf(false) }
    var expandedXpub by remember { mutableStateOf(false) }

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    // QR Dialog
    if (showQrDialog && uiState.accountXpub.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("${uiState.xpubLabel} — Account Public Key") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCodeImage(
                        data = uiState.accountXpub,
                        modifier = Modifier.size(250.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        uiState.accountXpub,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.copyToClipboard(uiState.accountXpub, "Account Public Key")
                    }) { Text("Copy") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { showQrDialog = false }) { Text("Close") }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.walletName.ifEmpty { "Wallet Info" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ─── Wallet Details Card ───
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Name row with edit
                        if (uiState.isEditing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = uiState.editName,
                                    onValueChange = { viewModel.setEditName(it) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("Wallet Name") }
                                )
                                IconButton(onClick = { viewModel.saveName() }) {
                                    Icon(Icons.Default.Check, contentDescription = "Save")
                                }
                                IconButton(onClick = { viewModel.cancelEditing() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Wallet Name", style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.startEditing() }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit name",
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                            Text(uiState.walletName, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Type
                        Row {
                            Text("Type: ", style = MaterialTheme.typography.labelMedium)
                            Text(
                                if (uiState.isWatchOnly) "Watch-Only" else "Full Wallet",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Network with pill badge
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Network: ", style = MaterialTheme.typography.labelMedium)
                            val isTestnet = uiState.network == "testnet"
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (isTestnet) Color(0xFFFF9800) else MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    if (isTestnet) "Testnet" else "Mainnet",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isTestnet) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Transaction count
                        Row {
                            Text("Transactions: ", style = MaterialTheme.typography.labelMedium)
                            Text("${uiState.transactionCount}", style = MaterialTheme.typography.bodyMedium)
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Derivation path
                        Row {
                            Text("Derivation: ", style = MaterialTheme.typography.labelMedium)
                            Text(uiState.derivationPath, style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ))
                        }
                    }
                }

                // ─── Extended Public Key ───
                if (uiState.accountXpub.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Extended Public Key (${uiState.xpubLabel})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                if (expandedXpub) uiState.accountXpub
                                else uiState.accountXpub.take(8) + "…" + uiState.accountXpub.takeLast(6),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { expandedXpub = !expandedXpub }) {
                                    Text(if (expandedXpub) "Collapse" else "Expand")
                                }
                                TextButton(onClick = {
                                    viewModel.copyToClipboard(uiState.accountXpub, "Public Key")
                                }) { Text(if (uiState.copied) "Copied ✓" else "Copy") }
                                TextButton(onClick = { showQrDialog = true }) {
                                    Text("Show QR")
                                }
                            }
                        }
                    }
                }

                // ─── Fingerprint ───
                uiState.fingerprintBytes?.let { fpBytes ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            net.clench.wallet.ui.components.WalletFingerprint(
                                fingerprintBytes = fpBytes,
                                masterFingerprint = uiState.masterFingerprintBytes,
                                label = if (uiState.hasPassphrase)
                                    "Wallet fingerprint — verify this matches when restoring with your passphrase"
                                else
                                    "Wallet fingerprint — unique visual identifier for this wallet"
                            )
                        }
                    }
                }

                // ─── Addresses ───
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("View Addresses", style = MaterialTheme.typography.titleSmall)
                        Button(onClick = onViewAddresses) { Text("View Addresses →") }
                    }
                }

                // ─── Backup & Export ───
                Button(
                    onClick = onBackup,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Backup & Export") }

                // Error
                uiState.error?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
