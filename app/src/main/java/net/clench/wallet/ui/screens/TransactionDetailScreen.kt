package net.clench.wallet.ui.screens

import android.content.Intent
import android.net.Uri
import net.clench.wallet.ui.util.copyToClipboardWithAutoClear
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    isOfflineMode: Boolean = false
) {
    val context = LocalContext.current
    var showFullTxid by remember { mutableStateOf(false) }

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
                        if (showFullTxid) transaction.txid else "${transaction.txid.take(16)}…${transaction.txid.takeLast(8)}",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
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

                // Fee (R7-5: show fee from sync data, "—" if unavailable)
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
