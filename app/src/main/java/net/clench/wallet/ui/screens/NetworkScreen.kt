package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showWarningDialog by remember { mutableStateOf(false) }
    var pendingTestnet by remember { mutableStateOf(false) }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text("⚠️ Switch Network?") },
            text = {
                Text(
                    if (pendingTestnet)
                        "Switching to testnet. Existing mainnet wallets will not work. You'll need to create a new testnet wallet."
                    else
                        "Switching to mainnet. Existing testnet wallets will not work on mainnet."
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setUseTestnet(pendingTestnet)
                    showWarningDialog = false
                }) { Text("Switch") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWarningDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
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
            Text("Bitcoin Network", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiState.useTestnet,
                    onCheckedChange = {
                        pendingTestnet = it
                        showWarningDialog = true
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Use Testnet")
                    Text(
                        if (uiState.useTestnet) "Testnet mode — not real bitcoin"
                        else "Mainnet (real bitcoin)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.useTestnet) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.useTestnet) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "⚠️ Testnet wallets use fake bitcoin. Existing mainnet wallets will not work on testnet. " +
                        "Create a new wallet after switching networks.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
