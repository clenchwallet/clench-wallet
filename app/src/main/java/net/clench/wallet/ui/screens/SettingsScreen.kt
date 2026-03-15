package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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

                Button(
                    onClick = { viewModel.saveServerSettings() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save") }

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
