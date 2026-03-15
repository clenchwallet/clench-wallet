package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDebug: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            Text("Electrum Server", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(12.dp))

            // Public vs custom server toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiState.useCustomServer,
                    onCheckedChange = { viewModel.setUseCustomServer(it) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Use custom server")
                    Text(
                        if (uiState.useCustomServer) "Your own Electrum node"
                        else "Public: ${uiState.publicServer}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.useCustomServer) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = uiState.customServerUrl,
                    onValueChange = { viewModel.setCustomServerUrl(it) },
                    label = { Text("Server hostname or IP") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("your.node.com or local IP") },
                    supportingText = { Text("Do not include ssl:// — use the SSL toggle below") },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.customServerPort,
                    onValueChange = { viewModel.setCustomServerPort(it) },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.useSSL,
                        onCheckedChange = { viewModel.setUseSsl(it) }
                    )
                    Text("Use SSL")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.saveServerSettings() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }

                    OutlinedButton(
                        onClick = { viewModel.testConnection() },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.testingConnection
                    ) {
                        if (uiState.testingConnection)
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        else
                            Text("Test")
                    }
                }

                uiState.connectionTestResult?.let { result ->
                    Spacer(modifier = Modifier.height(8.dp))
                    val isSuccess = result.startsWith("✓")
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSuccess)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = result,
                            color = if (isSuccess)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (uiState.savedSuccess) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "✓ Server settings saved",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                uiState.saveError?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(err, color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            Text("Wallets", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Wallet list placeholder
            uiState.wallets.forEach { wallet ->
                ListItem(
                    headlineContent = { Text(wallet.name) },
                    supportingContent = {
                        Text(if (wallet.isWatchOnly) "Watch-only" else "Full wallet")
                    }
                )
                HorizontalDivider()
            }

            // --- Mempool Explorer ---
            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Mempool Explorer", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiState.useCustomMempool,
                    onCheckedChange = { viewModel.setUseCustomMempool(it) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Use custom mempool explorer")
                    Text(
                        if (uiState.useCustomMempool) uiState.mempoolUrl
                        else "Default: mempool.space",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.useCustomMempool) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.mempoolUrl,
                    onValueChange = { viewModel.setMempoolUrl(it) },
                    label = { Text("Mempool URL") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://mempool.space") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.saveMempoolSettings() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Mempool URL") }
            }

            // --- Network (Testnet) ---
            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Network", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiState.useTestnet,
                    onCheckedChange = { viewModel.setUseTestnet(it) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Use Testnet")
                    Text(
                        if (uiState.useTestnet) "Testnet mode active — not real bitcoin"
                        else "Mainnet (real bitcoin)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.useTestnet) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.useTestnet) {
                Spacer(modifier = Modifier.height(8.dp))
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

            // --- Security ---
            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Security", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiState.biometricForSeed,
                    onCheckedChange = { viewModel.setBiometricForSeed(it) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Require biometric to view seed phrase")
                    Text(
                        "Authenticate before showing your seed words",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiState.biometricForSend,
                    onCheckedChange = { viewModel.setBiometricForSend(it) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Require biometric to send")
                    Text(
                        "Authenticate before building a transaction",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("App lock", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                FilterChip(
                    selected = uiState.appLockMode == "biometric",
                    onClick = { viewModel.setAppLockMode("biometric") },
                    label = { Text("Biometric / PIN") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = uiState.appLockMode == "none",
                    onClick = { viewModel.setAppLockMode("none") },
                    label = { Text("None") }
                )
            }
            Text(
                if (uiState.appLockMode == "biometric")
                    "App will require authentication after 30s in background"
                else
                    "No lock — app is accessible without authentication",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Debug section — always visible in this build to capture crash logs
            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Debug", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDebug,
                modifier = Modifier.fillMaxWidth()
            ) { Text("View Crash Log") }
        }
    }
}
