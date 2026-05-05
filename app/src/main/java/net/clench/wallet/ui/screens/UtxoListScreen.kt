package net.clench.wallet.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import net.clench.wallet.ui.viewmodel.UtxoViewModel
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UtxoListScreen(
    walletId: String,
    onBack: () -> Unit,
    onSpendSelected: (walletId: String, outpoints: List<String>) -> Unit,
    viewModel: UtxoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reload on every resume — clears stale UTXO state from previous passphrase sessions.
    // LaunchedEffect(walletId) alone doesn't re-fire when the same wallet stays selected
    // but the passphrase was locked/re-entered between visits.
    LifecycleResumeEffect(walletId) {
        viewModel.load(walletId)
        onPauseOrDispose { viewModel.clear() }
    }

    // Label dialog
    if (uiState.showLabelDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLabelDialog() },
            title = { Text("Label UTXO") },
            text = {
                OutlinedTextField(
                    value = uiState.labelDialogText,
                    onValueChange = { viewModel.setLabelDialogText(it) },
                    label = { Text("Label (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("e.g. KYC exchange, gift, mining") }
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.saveLabel() }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLabelDialog() }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Coin Control") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.selectedCount > 0) {
                val fmt = NumberFormat.getNumberInstance(Locale.US)
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${uiState.selectedCount} UTXOs selected",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${fmt.format(uiState.selectedSats)} sats",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                onSpendSelected(walletId, uiState.selectedOutpoints)
                            }
                        ) {
                            Text("Spend Selected")
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else if (uiState.utxos.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No UTXOs found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.utxos, key = { it.outpoint }) { utxo ->
                    val fmt = NumberFormat.getNumberInstance(Locale.US)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (!utxo.isFrozen) {
                                        viewModel.toggleSelection(utxo.outpoint)
                                    }
                                },
                                onLongClick = {
                                    // Show options: freeze/label
                                    viewModel.showLabelDialog(utxo.outpoint)
                                }
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                utxo.isFrozen -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                utxo.isSelected -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Checkbox or frozen icon
                            if (utxo.isFrozen) {
                                Text("❄️", modifier = Modifier.size(24.dp))
                            } else {
                                Checkbox(
                                    checked = utxo.isSelected,
                                    onCheckedChange = { viewModel.toggleSelection(utxo.outpoint) },
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                // Amount and frozen badge
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${fmt.format(utxo.amountSat)} sats",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (utxo.isFrozen) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (utxo.isFrozen) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.errorContainer
                                        ) {
                                            Text(
                                                "Frozen",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Address (truncated)
                                utxo.address?.let { addr ->
                                    Text(
                                        if (addr.length > 14) addr.take(8) + "…" + addr.takeLast(6) else addr,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Outpoint
                                Text(
                                    "${utxo.txid.take(8)}…:${utxo.vout}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Label
                                utxo.label?.let { label ->
                                    Text(
                                        "🏷️ $label",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Confirmation badge
                            Column(horizontalAlignment = Alignment.End) {
                                val (badgeColor, badgeText) = when {
                                    utxo.confirmations == 0 -> Color(0xFFE53935) to "Unconf"
                                    utxo.confirmations < 3 -> Color(0xFFFFA726) to "${utxo.confirmations} conf"
                                    utxo.confirmations < 6 -> Color(0xFF66BB6A) to "${utxo.confirmations} conf"
                                    else -> Color(0xFF43A047) to "6+ conf"
                                }
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = badgeColor.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        badgeText,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Freeze button
                                TextButton(
                                    onClick = { viewModel.toggleFreeze(utxo.outpoint) },
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    Text(
                                        if (utxo.isFrozen) "Unfreeze" else "Freeze",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                // Help text at bottom
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap to select UTXOs for spending. Long-press to label. Frozen UTXOs are excluded from selection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        uiState.error?.let { err ->
            Snackbar(
                modifier = Modifier.padding(16.dp)
            ) { Text(err) }
        }
    }
}
