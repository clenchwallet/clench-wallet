package net.clench.wallet.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.domain.model.PublicElectrumServers
import net.clench.wallet.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectrumServerScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.useTestnet) "Electrum Server (Testnet)" else "Electrum Server (Mainnet)")
                },
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
            // Offline mode toggle
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.offlineMode)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Switch(
                        checked = uiState.offlineMode,
                        onCheckedChange = { viewModel.setOfflineMode(it) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Offline mode", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Never connect to any server. Balance may be outdated.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (uiState.offlineMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "⚠️ Offline — sync and send are disabled",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ─── Tor Proxy ───
            Text("Tor Proxy", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.torEnabled)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = uiState.torEnabled,
                            onCheckedChange = { viewModel.setTorEnabled(it) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🧅", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Route through Tor", style = MaterialTheme.typography.titleSmall)
                            }
                            Text(
                                "Requires Orbot or another Tor SOCKS5 proxy app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (uiState.torEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = uiState.torProxyHost,
                                onValueChange = { viewModel.setTorProxyHost(it) },
                                label = { Text("SOCKS5 Host") },
                                modifier = Modifier.weight(2f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = uiState.torProxyPort,
                                onValueChange = { viewModel.setTorProxyPort(it) },
                                label = { Text("Port") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.saveTorSettings() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save Tor Settings") }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Note: BDK ElectrumClient does not natively support SOCKS5 proxy. " +
                            "Price API requests will be routed through Tor when enabled. " +
                            "For full Tor routing of Electrum traffic, use a .onion Electrum server with Tor's transparent proxy.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

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
                        else "Public server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!uiState.useCustomServer) {
                // Public server picker - show info card for testnet instead of servers
                if (uiState.useTestnet) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Testnet requires your own Electrum server.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Public servers are not offered for testnet. Enable 'Use custom server' above to configure your node. BDK supports plain TCP and TLS with trusted CA certificates. Self-signed certificates are not supported.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    // Auto-enable custom server for testnet
                    LaunchedEffect(Unit) {
                        if (!uiState.useCustomServer) {
                            viewModel.setUseCustomServer(true)
                        }
                    }
                } else {
                    // Show public servers for mainnet
                    Text("Select public server", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    PublicElectrumServers.forNetwork(uiState.useTestnet).forEach { server ->
                        val isSelected = uiState.publicServer == "${server.host}:${server.port}"
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            onClick = { viewModel.selectPublicServer(server) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(server.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${server.host}:${server.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                // Custom server fields
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
        }
    }
}
