package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.CreateWalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassphraseConfirmScreen(
    onSuccess: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CreateWalletViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var confirmInput by remember { mutableStateOf("") }
    var confirmError by remember { mutableStateOf<String?>(null) }

    // Compute fingerprint for the confirm input dynamically
    val confirmFingerprintBytes = remember(confirmInput) {
        if (confirmInput.isNotEmpty()) {
            viewModel.computeFingerprintForPassphrase(confirmInput)
        } else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm Passphrase") },
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
                .imePadding()
        ) {
            // Info card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Enter your passphrase again to confirm you've written it correctly.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1565C0)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Confirm passphrase field
            OutlinedTextField(
                value = confirmInput,
                onValueChange = {
                    confirmInput = it
                    confirmError = null
                },
                label = { Text("Confirm passphrase") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = confirmError != null
            )

            confirmError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onBack) {
                    Text(
                        "Go back to re-enter your passphrase",
                        textDecoration = TextDecoration.Underline
                    )
                }
            }

            // Fingerprint display for the confirm input
            confirmFingerprintBytes?.let { bytes ->
                Spacer(modifier = Modifier.height(16.dp))
                WalletFingerprint(bytes)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "This fingerprint must match the one on the previous screen.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Create Wallet button
            Button(
                onClick = {
                    if (confirmInput == uiState.pendingPassphrase) {
                        viewModel.confirmAndSave(onSuccess)
                    } else {
                        confirmError = "Passphrases do not match"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = confirmInput.isNotEmpty() && !uiState.isLoading
            ) {
                if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text("Create Wallet")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Go Back button
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Go Back")
            }

            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
