package net.clench.wallet.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
class ServerSetupViewModel @Inject constructor(
    private val settingsManager: SettingsManager
) : ViewModel() {

    init {
        // If already configured, skip will be handled in the screen
    }

    fun isConfigured(): Boolean = settingsManager.isConfigured()

    fun getExistingConfig(): ElectrumConfig = settingsManager.loadElectrumConfig()

    fun saveAndProceed(config: ElectrumConfig, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsManager.saveElectrumConfig(config)
            onDone()
        }
    }
}

enum class SetupStep {
    CHOOSE_TYPE,
    PUBLIC_SERVER,
    OWN_NODE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSetupScreen(
    onServerConfigured: (ElectrumConfig) -> Unit,
    viewModel: ServerSetupViewModel = hiltViewModel()
) {
    var currentStep by remember { mutableStateOf(SetupStep.CHOOSE_TYPE) }
    var selectedServer by remember { mutableStateOf<PublicServer?>(null) }
    var customHost by remember { mutableStateOf("") }
    var customPort by remember { mutableStateOf("50002") }
    var customUseSsl by remember { mutableStateOf(true) }

    // Check if already configured and skip if so
    LaunchedEffect(Unit) {
        if (viewModel.isConfigured()) {
            onServerConfigured(viewModel.getExistingConfig())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Setup") },
                navigationIcon = {
                    if (currentStep != SetupStep.CHOOSE_TYPE) {
                        IconButton(onClick = { currentStep = SetupStep.CHOOSE_TYPE }) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (currentStep) {
                SetupStep.CHOOSE_TYPE -> ChooseTypeStep(
                    onPublicSelected = { currentStep = SetupStep.PUBLIC_SERVER },
                    onOwnNodeSelected = { currentStep = SetupStep.OWN_NODE }
                )

                SetupStep.PUBLIC_SERVER -> PublicServerStep(
                    selectedServer = selectedServer,
                    onServerSelected = { selectedServer = it },
                    onConnect = {
                        selectedServer?.let { server ->
                            val config = ElectrumConfig(
                                serverUrl = server.host,
                                port = server.port,
                                useSsl = server.useSsl,
                                isCustom = false
                            )
                            viewModel.saveAndProceed(config) {
                                onServerConfigured(config)
                            }
                        }
                    }
                )

                SetupStep.OWN_NODE -> OwnNodeStep(
                    host = customHost,
                    port = customPort,
                    useSsl = customUseSsl,
                    onHostChange = { customHost = it },
                    onPortChange = { customPort = it },
                    onSslChange = { customUseSsl = it },
                    onConnect = {
                        val config = ElectrumConfig(
                            serverUrl = customHost,
                            port = customPort.toIntOrNull() ?: 50002,
                            useSsl = customUseSsl,
                            isCustom = true
                        )
                        viewModel.saveAndProceed(config) {
                            onServerConfigured(config)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ChooseTypeStep(
    onPublicSelected: () -> Unit,
    onOwnNodeSelected: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "How do you want to connect?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Public Server option
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPublicSelected() },
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
                Text(
                    text = "🌐",
                    style = MaterialTheme.typography.displaySmall
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Public Server",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Easy setup. Less privacy.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Own Node option
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOwnNodeSelected() },
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
                Text(
                    text = "🏠",
                    style = MaterialTheme.typography.displaySmall
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "My Own Node",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Maximum privacy. Requires your own Electrum server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Info text
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "ℹ️",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Public servers can see your wallet addresses. For maximum privacy, run your own Bitcoin node.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun PublicServerStep(
    selectedServer: PublicServer?,
    onServerSelected: (PublicServer) -> Unit,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Choose a public server",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(PublicElectrumServers.list) { server ->
                val isSelected = selectedServer == server
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onServerSelected(server) },
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
        }

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedServer != null
        ) {
            Text("Connect")
        }
    }
}

@Composable
private fun OwnNodeStep(
    host: String,
    port: String,
    useSsl: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSslChange: (Boolean) -> Unit,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Connect to your node",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            label = { Text("Host / IP") },
            placeholder = { Text("example.com or 192.168.1.100") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = port,
            onValueChange = onPortChange,
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
            Text(
                text = "Use SSL",
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = useSsl,
                onCheckedChange = onSslChange
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = host.isNotBlank() && port.isNotBlank()
        ) {
            Text("Connect")
        }
    }
}
