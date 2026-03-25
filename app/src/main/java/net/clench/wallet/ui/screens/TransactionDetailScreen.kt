package net.clench.wallet.ui.screens

import android.content.Intent
import android.net.Uri
import net.clench.wallet.ui.util.copyToClipboardWithAutoClear
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.clench.wallet.domain.model.TransactionItem
import net.clench.wallet.domain.model.TxDirection
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    transaction: TransactionItem?,
    isWatchOnly: Boolean,
    mempoolUrl: String,
    isTestnet: Boolean,
    onBack: () -> Unit,
    onSpendUtxo: (txid: String) -> Unit = {},
    onBumpFee: ((txid: String, newFeeRate: Float) -> Unit)? = null,
    isOfflineMode: Boolean = false,
    isBumping: Boolean = false,
    bumpError: String? = null,
    priorityFeeRate: Float? = null,
    onSaveLabel: ((txid: String, label: String) -> Unit)? = null
) {
    val context = LocalContext.current
    var showFullTxid by remember { mutableStateOf(false) }
    var showBumpFeeDialog by remember { mutableStateOf(false) }
    var bumpFeeRate by remember { mutableStateOf("") }
    var labelText by remember(transaction?.label) { mutableStateOf(transaction?.label ?: "") }
    var labelSaved by remember { mutableStateOf(true) }

    // Bump fee dialog
    if (showBumpFeeDialog && transaction != null) {
        AlertDialog(
            onDismissRequest = { showBumpFeeDialog = false },
            title = { Text("Bump Fee (RBF)") },
            text = {
                Column {
                    if (transaction.feeSat != null) {
                        Text(
                            "Current fee: ${NumberFormat.getNumberInstance(Locale.US).format(transaction.feeSat)} sats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = bumpFeeRate,
                        onValueChange = { bumpFeeRate = it },
                        label = { Text("New fee rate (sat/vB)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Must be higher than the current fee rate. Transaction will be re-broadcast with the new fee.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    bumpError?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val rate = bumpFeeRate.toFloatOrNull()
                        if (rate != null && rate >= 1f) {
                            onBumpFee?.invoke(transaction.txid, rate)
                        }
                    },
                    enabled = !isBumping && bumpFeeRate.toFloatOrNull()?.let { it >= 1f } == true
                ) {
                    if (isBumping) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Bump Fee")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBumpFeeDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction Detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (transaction == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Transaction not found")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Direction
                val isReceived = transaction.direction == TxDirection.RECEIVED
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isReceived)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (isReceived) "Received" else "Sent",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val fmt = NumberFormat.getNumberInstance(Locale.US)
                        Text(
                            "${if (isReceived) "+" else "-"}${fmt.format(transaction.amountSat)} sats",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        // BTC equivalent
                        val btc = transaction.amountSat / 100_000_000.0
                        val btcFormatted = String.format(Locale.US, "%.8f", btc)
                            .trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
                        Text(
                            "$btcFormatted BTC",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Label / Note
                if (onSaveLabel != null) {
                    Text("Label", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = labelText,
                            onValueChange = {
                                labelText = it
                                labelSaved = false
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Add label…") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        if (!labelSaved) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    onSaveLabel(transaction!!.txid, labelText)
                                    labelSaved = true
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Save")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Confirmation badge
                val (badgeColor, badgeText) = when {
                    transaction.confirmations == 0 -> Color(0xFFE53935) to "Unconfirmed"
                    transaction.confirmations in 1..2 -> Color(0xFFFFA726) to "Confirming (${transaction.confirmations}/6)"
                    transaction.confirmations in 3..5 -> Color(0xFF66BB6A) to "Confirming (${transaction.confirmations}/6)"
                    else -> Color(0xFF43A047) to "Confirmed ✓"
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = badgeColor.copy(alpha = 0.15f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        badgeText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Transaction ID
                Text("Transaction ID", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (showFullTxid) {
                                copyToClipboardWithAutoClear(context, "Transaction ID", transaction.txid)
                            }
                            showFullTxid = !showFullTxid
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        if (showFullTxid) transaction.txid else "${transaction.txid.take(8)}…${transaction.txid.takeLast(6)}",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                Text(
                    "Tap to expand, tap again to copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Confirmations
                Text("Confirmations", style = MaterialTheme.typography.labelMedium)
                Text(
                    if (transaction.confirmations == 0) "Unconfirmed" else "${transaction.confirmations}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (transaction.confirmations == 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Date/time
                Text("Date", style = MaterialTheme.typography.labelMedium)
                Text(
                    transaction.timestamp?.let {
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.systemDefault())
                            .format(it)
                    } ?: "Pending",
                    style = MaterialTheme.typography.bodyMedium
                )

                // Address (if available)
                transaction.address?.let { addr ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (isReceived) "From address" else "To address",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        if (addr.length > 14) addr.take(8) + "…" + addr.takeLast(6) else addr,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }

                // Fee
                Spacer(modifier = Modifier.height(12.dp))
                Text("Fee", style = MaterialTheme.typography.labelMedium)
                if (transaction.feeSat != null) {
                    val fmtFee = NumberFormat.getNumberInstance(Locale.US)
                    Text("${fmtFee.format(transaction.feeSat)} sats", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("—", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Actions
                Button(
                    onClick = {
                        val mempoolBase = mempoolUrl.trimEnd('/')
                        val testnetPrefix = if (isTestnet) "/testnet" else ""
                        val url = "$mempoolBase$testnetPrefix/tx/${transaction.txid}"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("View in Explorer") }

                // Bump Fee button — unconfirmed sent transactions (RBF)
                if (!isReceived && transaction.confirmations == 0 && !isWatchOnly && onBumpFee != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            bumpFeeRate = priorityFeeRate?.toInt()?.coerceAtLeast(2)?.toString() ?: "10"
                            showBumpFeeDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isOfflineMode
                    ) {
                        Text("⚡ Bump Fee (RBF)")
                    }
                }

                // UTXO spend option for received transactions
                if (isReceived) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onSpendUtxo(transaction.txid) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isOfflineMode
                    ) {
                        Text(if (isWatchOnly) "Spend this UTXO (PSBT)" else "Spend this UTXO")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Close") }
            }
        }
    }
}
