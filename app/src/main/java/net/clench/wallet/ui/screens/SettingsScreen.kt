package net.clench.wallet.ui.screens

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
                SettingsSectionCard(
                    title = "Network",
                    subtitle = if (uiState.useTestnet) "Testnet" else "Mainnet",
                    onClick = onNetwork
                )
            }
            item {
                SettingsSectionCard(
                    title = "Security",
                    subtitle = if (uiState.appLockMode == "biometric") "Biometric lock enabled" else "No lock",
                    onClick = onSecurity
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
