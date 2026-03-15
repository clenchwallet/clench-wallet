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
fun ExplorerScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Explorer") },
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
            Text("Mempool Explorer", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Used to view transaction details in a block explorer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

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
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.mempoolUrl,
                    onValueChange = { viewModel.setMempoolUrl(it) },
                    label = { Text("Mempool URL") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://mempool.space") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.saveMempoolSettings() },
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
                            "✓ Saved",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}
