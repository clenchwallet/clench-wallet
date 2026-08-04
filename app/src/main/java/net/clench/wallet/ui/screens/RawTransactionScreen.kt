package net.clench.wallet.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.RawTransactionViewModel
import net.clench.wallet.security.InputLimits
import net.clench.wallet.security.readTextBounded
import net.clench.wallet.ui.picker.LocalPickerRoundTripHost
import net.clench.wallet.ui.picker.PickerDestination
import net.clench.wallet.ui.picker.PickerPurpose
import net.clench.wallet.ui.picker.PickerRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawTransactionScreen(
    walletId: String,
    onBack: () -> Unit,
    onConnect: () -> Unit = {},
    viewModel: RawTransactionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    SecureWindowEffect()
    val pickerHost = LocalPickerRoundTripHost.current
    val pickerResume by pickerHost.pickerResume.collectAsState()
    val pickerDestination = remember(walletId) { PickerDestination.RawTransaction(walletId) }
    LaunchedEffect(pickerResume?.requestId) {
        if (pickerResume?.purpose == PickerPurpose.RAW_TRANSACTION_IMPORT) {
            val result = pickerHost.consumePickerResult(
                PickerPurpose.RAW_TRANSACTION_IMPORT,
                pickerDestination
            )
            if (result?.uri != null) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(result.uri))?.bufferedReader()?.use {
                    it.readTextBounded(InputLimits.RAW_TRANSACTION_CHARS)
                }
                    ?: error("Could not read file")
            }.onSuccess { text ->
                viewModel.setInput(text)
                viewModel.preview()
            }.onFailure { e ->
                viewModel.setError("Could not read file: ${e.message ?: "unknown error"}")
            }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Raw Transaction") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Import or paste a fully signed Bitcoin transaction. Clench rejects recognizable weak signature flags, but a raw transaction does not include enough information to prove every signature is safe. Prefer the hardware-signer return screen whenever possible. Clench only broadcasts after explicit confirmation.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.input,
                onValueChange = { viewModel.setInput(it) },
                label = { Text("Raw transaction hex or base64") },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        if (!pickerHost.launchPicker(PickerRequest.RawTransactionImport(walletId))) {
                            viewModel.setError("Finish the current file selection first")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Load File") }
                Button(
                    onClick = { viewModel.preview() },
                    modifier = Modifier.weight(1f)
                ) { Text("Preview") }
            }

            uiState.preview?.let { preview ->
                var outputsReviewed by remember(preview.txid) { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Parsed Transaction", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("TXID", style = MaterialTheme.typography.labelMedium)
                        Text(
                            preview.txid,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Virtual size: ${preview.vsize} vB")
                        Text("Total size: ${preview.totalSize} bytes")
                        Text("Signals RBF: ${if (preview.isRbf) "yes" else "no"}")
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Outputs (${preview.outputs.size})", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        preview.outputs.forEach { output ->
                            Text("Output #${output.index + 1}: ${output.amountSat} sats")
                            Text(
                                output.address ?: "Unknown script: ${output.scriptPubkeyHex}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = outputsReviewed,
                                onCheckedChange = { outputsReviewed = it }
                            )
                            Text(
                                "I reviewed every output amount and destination",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { if (uiState.isOfflineMode) onConnect() else viewModel.broadcast() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = outputsReviewed && !uiState.isBroadcasting
                ) {
                    if (uiState.isBroadcasting) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text(if (uiState.isOfflineMode) "Connect to Broadcast" else "Broadcast Transaction")
                }
            }

            uiState.broadcastTxid?.let { txid ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Broadcast accepted: ${txid.take(12)}...${txid.takeLast(8)}",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
