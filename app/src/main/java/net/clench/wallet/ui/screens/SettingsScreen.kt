package net.clench.wallet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsSectionCard(title: String, subtitle: String = "", onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = if (subtitle.isNotBlank()) {
            { Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        } else null,
        trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDebug: () -> Unit = {},
    onElectrum: () -> Unit = {},
    onExplorer: () -> Unit = {},
    onNetwork: () -> Unit = {},
    onSecurity: () -> Unit = {},
    onAbout: () -> Unit = {},
    onHardwareWallet: () -> Unit = {},
    onRecoveryWizard: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportStateBackup(it) }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importStateBackup(it) }
    }

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                SettingsSectionCard(
                    title = "Electrum Server",
                    subtitle = if (uiState.useCustomServer) "Custom: ${uiState.customServerUrl}" else "Public: ${uiState.publicServer}",
                    onClick = onElectrum
                )
            }
            item {
                SettingsSectionCard(
                    title = "Explorer",
                    subtitle = if (uiState.useCustomMempool) uiState.mempoolUrl else "mempool.space",
                    onClick = onExplorer
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("USD Balance", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("Fetch BTC/USD price for balance display", style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        Switch(
                            checked = uiState.btcPriceEnabled,
                            onCheckedChange = { viewModel.setBtcPriceEnabled(it) }
                        )
                    },
                    modifier = Modifier.clickable { viewModel.setBtcPriceEnabled(!uiState.btcPriceEnabled) }
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text("External Fee Estimates", fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(
                            "Allow mempool.space fee fallback only when your Electrum server cannot estimate fees",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = uiState.externalFeeLookupEnabled,
                            onCheckedChange = { viewModel.setExternalFeeLookupEnabled(it) }
                        )
                    },
                    modifier = Modifier.clickable {
                        viewModel.setExternalFeeLookupEnabled(!uiState.externalFeeLookupEnabled)
                    }
                )
                HorizontalDivider()
            }
            item {
                SettingsSectionCard(
                    title = "Network",
                    subtitle = if (uiState.useTestnet) "Testnet" else "Mainnet",
                    onClick = onNetwork
                )
            }
            item {
                SettingsSectionCard(
                    title = "Security",
                    subtitle = when (uiState.appLockMode) {
                        "biometric" -> "Biometric lock enabled"
                        "pin" -> "Clench PIN enabled"
                        else -> "No lock"
                    },
                    onClick = onSecurity
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("State Backup", fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Export or restore wallets, descriptors, labels, UTXO notes, and non-secret settings. Seed phrases are not included.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/json", "*/*")) },
                            enabled = !uiState.isBackupBusy,
                            modifier = Modifier.weight(1f)
                        ) { Text("Import") }
                        Button(
                            onClick = { exportBackupLauncher.launch("clench-state-backup-${java.time.LocalDate.now()}.json") },
                            enabled = !uiState.isBackupBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isBackupBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Export")
                        }
                    }
                    uiState.backupStatus?.let { status ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel.clearBackupStatus() }) { Text("Dismiss") }
                    }
                }
                HorizontalDivider()
            }
            item {
                SettingsSectionCard(
                    title = "Recovery Wizard",
                    subtitle = "Restore from state backup, seed phrase, descriptor, or hardware wallet export",
                    onClick = onRecoveryWizard
                )
            }
            item {
                SettingsSectionCard(
                    title = "About",
                    subtitle = "Clench Wallet",
                    onClick = onAbout
                )
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                ListItem(
                    headlineContent = { Text("Debug", fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("View crash log", style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable(onClick = onDebug)
                )
                HorizontalDivider()
            }
        }
    }
}
