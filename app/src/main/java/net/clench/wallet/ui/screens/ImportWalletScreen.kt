package net.clench.wallet.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.components.WalletFingerprint
import net.clench.wallet.ui.viewmodel.ImportWalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    onWalletImported: (String) -> Unit,
    onBack: () -> Unit,
    onSettings: (() -> Unit)? = null,
    viewModel: ImportWalletViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    var showScanner by remember { mutableStateOf(false) }

    val isSeedPhrase = uiState.detectedType == ImportWalletViewModel.DetectedType.SEED_12 ||
            uiState.detectedType == ImportWalletViewModel.DetectedType.SEED_24

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Wallet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onSettings != null) {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                .verticalScroll(scrollState)
                .imePadding()
        ) {
            // Wallet name
            OutlinedTextField(
                value = uiState.walletName,
                onValueChange = { viewModel.setWalletName(it) },
                label = { Text("Wallet name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Single unified input field
            OutlinedTextField(
                value = uiState.input,
                onValueChange = { viewModel.setInput(it) },
                label = { Text("Enter seed phrase, xpub, zpub, or descriptor") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                placeholder = { Text("word1 word2 word3 … or zpub… or wpkh(…)") },
                trailingIcon = {
                    IconButton(onClick = { showScanner = true }) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Scan QR code"
                        )
                    }
                }
            )

            // Detection status line
            if (uiState.detectedLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    uiState.detectedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            // Collapsible passphrase section — only for seed phrases
            if (isSeedPhrase) {
                Spacer(modifier = Modifier.height(12.dp))

                var passphraseExpanded by remember { mutableStateOf(false) }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        passphraseExpanded = !passphraseExpanded
                        if (!passphraseExpanded) return@OutlinedCard
                        coroutineScope.launch {
                            delay(300)
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Advanced: Add passphrase (optional)",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (passphraseExpanded) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                }

                AnimatedVisibility(visible = passphraseExpanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        // Warning about passphrase for import
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFF3E0)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Any passphrase opens a valid wallet — there is no 'wrong passphrase' error by design. " +
                                "Check the fingerprint and identicon below match what you see every time you unlock.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF5D4037)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        var importPassphraseVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = uiState.passphrase,
                            onValueChange = { if (it.length <= 512) viewModel.setPassphrase(it) },
                            label = { Text("Passphrase") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        coroutineScope.launch {
                                            delay(400)
                                            scrollState.animateScrollTo(scrollState.maxValue)
                                        }
                                    }
                                },
                            singleLine = true,
                            visualTransformation = if (importPassphraseVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                androidx.compose.material3.IconButton(onClick = { importPassphraseVisible = !importPassphraseVisible }) {
                                    androidx.compose.material3.Text(if (importPassphraseVisible) "Hide" else "Show", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                                }
                            }
                        )

                        // Fingerprint — shown when valid seed is entered
                        uiState.fingerprintBytes?.let { fpBytes ->
                            Spacer(modifier = Modifier.height(16.dp))
                            WalletFingerprint(
                                fingerprintBytes = fpBytes,
                                masterFingerprint = uiState.masterFingerprintBytes,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
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

        // QR Scanner overlay
        AnimatedVisibility(visible = showScanner) {
            QrScanner(
                onResult = { result ->
                    viewModel.setInput(result)
                    showScanner = false
                },
                onCancel = { showScanner = false }
            )
        }
    }
}
