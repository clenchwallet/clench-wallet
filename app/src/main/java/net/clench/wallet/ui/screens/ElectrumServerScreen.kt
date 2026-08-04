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
import net.clench.wallet.data.network.isOnionElectrumHost
import net.clench.wallet.domain.model.PublicElectrumServers
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectrumServerScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // If cert scanner is showing, render it full-screen
    if (uiState.showCertScanner) {
        QrScanner(
            onResult = { qrText ->
                val parsed = viewModel.parseCertQr(qrText)
                if (!parsed) {
                    viewModel.setShowCertScanner(false)
                }
            },
            onCancel = { viewModel.setShowCertScanner(false) }
        )
        return
    }

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
            // ─── Connection mode indicator ───
            if (uiState.connectionModeLabel.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            "Connection mode: ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            uiState.connectionModeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Server Health", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Checks the selected Electrum server using the effective Tor/TLS route and reports server version and tip height when available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { viewModel.runServerHealthCheck() },
                        enabled = !uiState.testingServerHealth && !uiState.offlineMode,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.testingServerHealth) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        else Text(if (uiState.offlineMode) "Offline" else "Run Health Check")
                    }
                    if (uiState.offlineMode) {
                        Text(
                            "Offline mode blocks active diagnostics.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    uiState.serverHealthResult?.let { result ->
                        val isSuccess = result.startsWith("✓")
                        Surface(
                            color = if (isSuccess)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    result,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSuccess)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onErrorContainer
                                )
                                TextButton(onClick = { viewModel.clearServerHealthResult() }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ─── Offline mode toggle ───
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

            // ─── Tor via Orbot ───
            Text("Tor via Orbot", style = MaterialTheme.typography.titleSmall)
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
                                Text("Use Tor via Orbot", style = MaterialTheme.typography.titleSmall)
                            }
                            Text(
                                "Clench does not bundle Tor. Install and start Orbot, then route Electrum traffic through Orbot's SOCKS5 proxy.",
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
                        ) { Text("Save Orbot Proxy") }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Default Orbot SOCKS5 is usually 127.0.0.1:9050. " +
                            ".onion addresses always require Orbot, even if the global Tor switch is off.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ─── Public vs custom server toggle ───
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

            val routeTargetLabel = if (uiState.useCustomServer) "private/custom node" else "public server"
            val activeServerHost = if (uiState.useCustomServer) {
                uiState.customServerUrl.trim()
            } else {
                uiState.publicServer.substringBefore(":").trim()
            }
            val activeHostIsOnion = isOnionElectrumHost(activeServerHost)

            // Auto-enable Tor for .onion nodes, public or private.
            LaunchedEffect(uiState.useCustomServer, activeServerHost) {
                if (activeHostIsOnion && !uiState.useServerTor) {
                    viewModel.setUseServerTor(true)
                }
            }

            // ─── Independent transport choice ───
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Checkbox(
                        checked = uiState.useServerTor || activeHostIsOnion,
                        onCheckedChange = { if (!activeHostIsOnion) viewModel.setUseServerTor(it) },
                        enabled = !activeHostIsOnion
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Connect over Tor via Orbot", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                activeHostIsOnion -> "Required for .onion $routeTargetLabel. Orbot must be installed and running."
                                uiState.useServerTor && uiState.useCustomServer -> "Use Orbot SOCKS5 for this private/custom node."
                                uiState.useServerTor -> "Hides your IP from this public server; the server can still see wallet queries."
                                else -> "Use clearnet/direct connection for this $routeTargetLabel."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Public Tor can mean Orbot-to-clearnet unless the selected server itself is .onion.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!uiState.useCustomServer) {
                // Public server picker for the active network.
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
                                "Public testnet servers are convenience defaults.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "They may be less private or lag behind chain tip. For serious testing, use a self-hosted Electrum server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

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
                            Text(
                                server.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // ─── Custom server fields ───
                OutlinedTextField(
                    value = uiState.customServerUrl,
                    onValueChange = { viewModel.setCustomServerUrl(it) },
                    label = { Text("Server hostname or IP") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("your.node.com, IP, or .onion address") },
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
                    Text("Use SSL/TLS")
                }

                // ─── TLS Certificate Pinning ───
                if (uiState.useSSL) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("🔒 Certificate Pinning", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Pin a specific TLS certificate for self-signed or private CA servers.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (uiState.pinnedCert != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Text(
                                            "✓ Certificate pinned (${uiState.pinnedCert!!.take(20)}…)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { viewModel.clearPinnedCert() }) {
                                            Text("Remove")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Cert paste field
                            var certPasteText by remember { mutableStateOf("") }
                            OutlinedTextField(
                                value = certPasteText,
                                onValueChange = { certPasteText = it },
                                label = { Text("Paste certificate (Base64 DER)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                maxLines = 3,
                                supportingText = { Text("Or scan a QR code below") }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (certPasteText.isNotBlank()) {
                                            viewModel.parseCertQr(certPasteText.trim())
                                            certPasteText = ""
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Pin Cert") }

                                OutlinedButton(
                                    onClick = { viewModel.setShowCertScanner(true) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("📷 Scan QR") }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "QR format: electrums://host:port?cert=BASE64",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
