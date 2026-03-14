package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

                    OutlinedTextField(
                        value = uiState.passphrase,
                        onValueChange = { viewModel.setPassphrase(it) },
                        label = { Text("Passphrase (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                ImportWalletViewModel.ImportMode.DESCRIPTOR -> {
                    OutlinedTextField(
                        value = uiState.descriptorInput,
                        onValueChange = { viewModel.setDescriptorInput(it) },
                        label = { Text("Descriptor or xpub") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = { Text("wpkh([fingerprint/84'/0'/0']xpub.../0/*)") }
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
