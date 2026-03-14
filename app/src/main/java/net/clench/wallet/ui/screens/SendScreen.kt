package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.SendViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    walletId: String,
    onBack: () -> Unit,
    viewModel: SendViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send Bitcoin") },
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
        ) {
            OutlinedTextField(
                value = uiState.toAddress,
                onValueChange = { viewModel.setAddress(it) },
                label = { Text("Bitcoin address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    // TODO: QR scan button → launch ZXing scanner
                    TextButton(onClick = { /* TODO: open QR scanner */ }) {
                        Text("Scan")
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.amountSat,
                onValueChange = { viewModel.setAmount(it) },
                label = { Text("Amount (sats)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                suffix = { Text("sats") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = uiState.sendMax,
                    onCheckedChange = { viewModel.setSendMax(it) }
                )
                Text("Send max (drain wallet)")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.feeRate,
                onValueChange = { viewModel.setFeeRate(it) },
                label = { Text("Fee rate (sat/vB)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.txHex != null) {
                Text("Transaction ready to broadcast", color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.broadcast(onBack) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Broadcast Transaction") }
            } else {
                Button(
                    onClick = { viewModel.buildTx() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Review Transaction")
                }
            }

            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
