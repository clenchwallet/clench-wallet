package net.clench.wallet.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.PublicElectrumServers
import net.clench.wallet.domain.model.PublicServer
import javax.inject.Inject

@HiltViewModel
class ConnectionSetupViewModel @Inject constructor(
    private val settingsManager: SettingsManager
) : ViewModel() {

    fun saveServerConfig(config: ElectrumConfig, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsManager.saveElectrumConfig(config)
            settingsManager.setOnboarded()
            onDone()
        }
    }

    fun enableOfflineMode(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsManager.setOfflineMode(true)
            settingsManager.setOnboarded()
            onDone()
        }
    }
}

private enum class ConnectionOption {
    NONE, OWN_NODE, PUBLIC_SERVER, OFFLINE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSetupScreen(
    onComplete: () -> Unit,
    viewModel: ConnectionSetupViewModel = hiltViewModel()
) {
    var selectedOption by remember { mutableStateOf(ConnectionOption.NONE) }

    // Own node fields
    var customHost by remember { mutableStateOf("") }
    var customPort by remember { mutableStateOf("50002") }
    var customUseSsl by remember { mutableStateOf(true) }

    // Public server selection
    var selectedServer by remember { mutableStateOf<PublicServer?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection Setup") },
                navigationIcon = {
                    if (selectedOption != ConnectionOption.NONE) {
                        IconButton(onClick = { selectedOption = ConnectionOption.NONE }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedOption) {
                ConnectionOption.NONE -> {
                    Text(
                        text = "How do you want to connect?",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Own node
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedOption = ConnectionOption.OWN_NODE },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🖥️", style = MaterialTheme.typography.displaySmall)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "My own Bitcoin node",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Best for privacy. Connect to your Electrum server.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Public server
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedOption = ConnectionOption.PUBLIC_SERVER },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🌍", style = MaterialTheme.typography.displaySmall)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Public Electrum server",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Easy setup. Less private. Blockstream, Bitaroo, etc.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Offline mode
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedOption = ConnectionOption.OFFLINE },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "✈️", style = MaterialTheme.typography.displaySmall)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Offline mode",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "No network connection. Watch balances from cache.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                ConnectionOption.OWN_NODE -> {
                    Text(
                        text = "Connect to your node",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = customHost,
                        onValueChange = { customHost = it },
                        label = { Text("Host / IP") },
                        placeholder = { Text("example.com or local IP") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = customPort,
                        onValueChange = { customPort = it },
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Use SSL", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = customUseSsl, onCheckedChange = { customUseSsl = it })
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = {
                            val config = ElectrumConfig(
                                serverUrl = customHost,
                                port = customPort.toIntOrNull() ?: 50002,
                                useSsl = customUseSsl,
                                isCustom = true
                            )
                            viewModel.saveServerConfig(config) { onComplete() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = customHost.isNotBlank() && customPort.isNotBlank()
                    ) {
                        Text("Connect")
                    }
                }

                ConnectionOption.PUBLIC_SERVER -> {
                    Text(
                        text = "Choose a public server",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    PublicElectrumServers.list.forEach { server ->
                        val isSelected = selectedServer == server
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedServer = server },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                            border = if (isSelected)
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else
                                null
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = server.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${server.host}:${server.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            selectedServer?.let { server ->
                                val config = ElectrumConfig(
                                    serverUrl = server.host,
                                    port = server.port,
                                    useSsl = server.useSsl,
                                    isCustom = false
                                )
                                viewModel.saveServerConfig(config) { onComplete() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedServer != null
                    ) {
                        Text("Connect")
                    }
                }

                ConnectionOption.OFFLINE -> {
                    Text(
                        text = "Offline Mode",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "✈️ No network connections will be made",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "You can still:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "• Create and import wallets\n• Generate receive addresses\n• View cached balances and transactions",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "You can connect later in Settings → Electrum Server",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = {
                            viewModel.enableOfflineMode { onComplete() }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue in Offline Mode")
                    }
                }
            }
        }
    }
}
