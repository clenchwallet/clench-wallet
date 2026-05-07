package net.clench.wallet.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File
import net.clench.wallet.BuildConfig
import net.clench.wallet.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
    }

    val crashLog = remember {
        val internal = try { File(context.filesDir, "crash_log.txt").readText() } catch (_: Exception) { null }
        if (!internal.isNullOrBlank()) return@remember internal

        val external = try {
            File(context.getExternalFilesDir(null), "crash_log.txt").readText()
        } catch (_: Exception) { null }
        if (!external.isNullOrBlank()) return@remember external

        "No crash log found.\n\nThe app has not crashed yet, or the log was cleared."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val diagnosticText = buildString {
                            appendLine("Clench Wallet ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            appendLine("Network: ${if (uiState.useTestnet) "Testnet" else "Mainnet"}")
                            appendLine("Offline mode: ${if (uiState.offlineMode) "On" else "Off"}")
                            appendLine("Connection mode: ${uiState.connectionModeLabel.ifBlank { "Unknown" }}")
                            appendLine("Tor via Orbot: ${if (uiState.torEnabled || uiState.useServerTor) "On" else "Off"}")
                            appendLine("Last sync error: ${uiState.lastSyncError ?: "None recorded"}")
                            appendLine()
                            append(crashLog)
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Clench Wallet Diagnostics")
                            putExtra(Intent.EXTRA_TEXT, diagnosticText)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
                    }) { Text("Share") }

                    TextButton(onClick = {
                        try { File(context.filesDir, "crash_log.txt").delete() } catch (_: Exception) {}
                        try { File(context.getExternalFilesDir(null), "crash_log.txt").delete() } catch (_: Exception) {}
                    }) { Text("Clear") }
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("App Diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    DiagnosticLine("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    DiagnosticLine("Network", if (uiState.useTestnet) "Testnet" else "Mainnet")
                    DiagnosticLine("Offline mode", if (uiState.offlineMode) "On" else "Off")
                    DiagnosticLine("Electrum target", if (uiState.useCustomServer) "Custom server configured (host hidden)" else "Public server selected")
                    DiagnosticLine("Connection mode", uiState.connectionModeLabel.ifBlank { "Unknown" })
                    DiagnosticLine(
                        "Tor via Orbot",
                        if (uiState.torEnabled || uiState.useServerTor) {
                            "On (${uiState.torProxyHost}:${uiState.torProxyPort})"
                        } else {
                            "Off"
                        }
                    )
                    DiagnosticLine("Last sync error", uiState.lastSyncError ?: "None recorded")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Custom node hostnames are intentionally hidden on this screen.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Crash Log", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = crashLog,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "$label: ",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(0.42f)
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(0.58f)
            )
        }
        HorizontalDivider()
    }
}
