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
fun SecurityScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
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
            // App lock mode
            Text("App Lock", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.appLockMode == "biometric",
                    onClick = { viewModel.setAppLockMode("biometric") },
                    label = { Text("Biometric / PIN") }
                )
                FilterChip(
                    selected = uiState.appLockMode == "none",
                    onClick = { viewModel.setAppLockMode("none") },
                    label = { Text("None") }
                )
            }
            Text(
                if (uiState.appLockMode == "biometric")
                    "App will require authentication after being in background"
                else
                    "No lock — app is accessible without authentication",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Auto-lock timeout
            if (uiState.appLockMode == "biometric") {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Auto-lock Timeout", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                val timeoutOptions = listOf(
                    "30s" to "30 seconds",
                    "1min" to "1 minute",
                    "5min" to "5 minutes",
                    "never" to "Never"
                )
                Column {
                    timeoutOptions.forEach { (key, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = uiState.lockTimeoutKey == key,
                                onClick = { viewModel.setLockTimeout(key) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Biometric gates
            Text("Biometric Gates", style = MaterialTheme.typography.titleMedium)
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
        }
    }
}
