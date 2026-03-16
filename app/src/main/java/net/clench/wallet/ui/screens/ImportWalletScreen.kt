package net.clench.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.ImportWalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    onWalletImported: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ImportWalletViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Wallet") },
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
            // Import type tabs
            TabRow(selectedTabIndex = uiState.importMode.ordinal) {
                Tab(
                    selected = uiState.importMode == ImportWalletViewModel.ImportMode.SEED,
                    onClick = { viewModel.setImportMode(ImportWalletViewModel.ImportMode.SEED) },
                    text = { Text("Seed Phrase") }
                )
                Tab(
                    selected = uiState.importMode == ImportWalletViewModel.ImportMode.DESCRIPTOR,
                    onClick = { viewModel.setImportMode(ImportWalletViewModel.ImportMode.DESCRIPTOR) },
                    text = { Text("Descriptor / xpub") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Wallet name
            OutlinedTextField(
                value = uiState.walletName,
                onValueChange = { viewModel.setWalletName(it) },
                label = { Text("Wallet name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (uiState.importMode) {
                ImportWalletViewModel.ImportMode.SEED -> {
                    OutlinedTextField(
                        value = uiState.seedInput,
                        onValueChange = { viewModel.setSeedInput(it) },
                        label = { Text("Enter 12 or 24 seed words") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = { Text("word1 word2 word3 ...") }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Passphrase field — always plain text
                    OutlinedTextField(
                        value = uiState.passphrase,
                        onValueChange = { if (it.length <= 512) viewModel.setPassphrase(it) },
                        label = { Text("Passphrase (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Warning about passphrase for import
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3E0)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "If this wallet was created with a passphrase, enter it here. " +
                            "If you enter the wrong passphrase, a different (empty) wallet will be created.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF5D4037)
                        )
                    }

                    // Fingerprint — shown when valid seed + passphrase are entered
                    uiState.fingerprintBytes?.let { bytes ->
                        Spacer(modifier = Modifier.height(16.dp))
                        ImportWalletFingerprint(bytes)
                    }
                }

                ImportWalletViewModel.ImportMode.DESCRIPTOR -> {
                    OutlinedTextField(
                        value = uiState.descriptorInput,
                        onValueChange = { viewModel.setDescriptorInput(it) },
                        label = { Text("Descriptor, xpub, or zpub") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = { Text("zpub... or xpub... or wpkh([fp/84'/0'/0']xpub.../0/*)") }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Accepted: zpub, ypub, xpub, or full descriptor string",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.importWallet(onWalletImported) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading
            ) {
                if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text("Import Wallet")
            }

            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Visual wallet fingerprint for import screen — helps verify correct passphrase.
 */
@Composable
private fun ImportWalletFingerprint(fingerprintBytes: ByteArray) {
    if (fingerprintBytes.size < 8) return

    val colors = remember(fingerprintBytes.toList()) {
        (0 until 8).map { i ->
            val byteVal = fingerprintBytes[i].toInt() and 0xFF
            val hue = byteVal * 360f / 256f
            Color.hsl(hue, 0.7f, 0.5f)
        }
    }

    val hexText = remember(fingerprintBytes.toList()) {
        fingerprintBytes.take(8).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "Wallet fingerprint — verify this matches when restoring",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        for (row in 0 until 2) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (col in 0 until 4) {
                    val idx = row * 4 + col
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(colors[idx], MaterialTheme.shapes.small)
                    )
                }
            }
            if (row == 0) Spacer(modifier = Modifier.height(4.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            hexText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
