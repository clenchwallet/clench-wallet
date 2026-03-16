package net.clench.wallet.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import net.clench.wallet.ui.components.WalletFingerprint
import net.clench.wallet.ui.viewmodel.CreateWalletViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CreateWalletScreen(
    onWalletCreated: (String) -> Unit,
    onNavigateConfirmPassphrase: () -> Unit,
    onBack: () -> Unit,
    viewModel: CreateWalletViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val buttonBringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Prevent screenshots when mnemonic is visible
    val context = androidx.compose.ui.platform.LocalContext.current
    if (uiState.mnemonic.isNotEmpty()) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            val activity = context as? android.app.Activity
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Wallet") },
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
                .verticalScroll(scrollState)
                .imePadding()
        ) {
            OutlinedTextField(
                value = uiState.walletName,
                onValueChange = { viewModel.setWalletName(it) },
                label = { Text("Wallet name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Word count selector
            Text("Seed phrase length", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = uiState.wordCount == 12,
                    onClick = { viewModel.setWordCount(12) },
                    label = { Text("12 words") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = uiState.wordCount == 24,
                    onClick = { viewModel.setWordCount(24) },
                    label = { Text("24 words") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Generate button
            if (uiState.mnemonic.isEmpty()) {
                Button(
                    onClick = { viewModel.generateWallet() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Generate Seed Phrase")
                }
            }

            // Mnemonic display
            if (uiState.mnemonic.isNotEmpty()) {
                Text(
                    "⚠\uFE0F Write these words down. Never share them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Fixed-height grid instead of LazyVerticalGrid (can't nest lazy in scrollable)
                val words = uiState.mnemonic
                val columns = 3
                val rows = (words.size + columns - 1) / columns
                for (row in 0 until rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (col in 0 until columns) {
                            val index = row * columns + col
                            if (index < words.size) {
                                Card(modifier = Modifier.weight(1f)) {
                                    Row(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            "${index + 1}.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(words[index], style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (row < rows - 1) Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Collapsible passphrase section
                var passphraseExpanded by remember { mutableStateOf(false) }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        passphraseExpanded = !passphraseExpanded
                        if (!passphraseExpanded) return@OutlinedCard
                        coroutineScope.launch {
                            delay(300) // wait for AnimatedVisibility expand animation
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
                    Column(modifier = Modifier
                        .padding(top = 8.dp)
                        .bringIntoViewRequester(bringIntoViewRequester)
                    ) {
                        // Warning card 1 — amber
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFF3E0)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "⚠\uFE0F Your passphrase is NEVER stored — not even on this device.\n\n" +
                                "To restore this wallet you need BOTH:\n" +
                                "  • Your ${uiState.wordCount}-word seed phrase\n" +
                                "  • This exact passphrase\n\n" +
                                "Write your passphrase down separately from your seed phrase and store both in secure locations.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5D4037)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Passphrase field — always plain text, no hide toggle, no confirm field
                        OutlinedTextField(
                            value = uiState.passphrase,
                            onValueChange = { if (it.length <= 512) viewModel.setPassphrase(it) },
                            label = { Text("Passphrase") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        coroutineScope.launch {
                                            delay(400) // wait for keyboard to finish rising
                                            scrollState.animateScrollTo(scrollState.maxValue)
                                        }
                                    }
                                },
                            singleLine = true
                        )

                        // Warning card 2 — red (only when passphrase is non-empty)
                        if (uiState.passphrase.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFFFEBEE)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "\uD83D\uDD34 If you forget this passphrase, your bitcoin cannot be recovered by anyone — not even us. There is no reset.",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFC62828)
                                )
                            }
                        }

                        // Dynamic wallet fingerprint — updates with passphrase
                        uiState.fingerprintBytes?.let { bytes ->
                            Spacer(modifier = Modifier.height(16.dp))
                            WalletFingerprint(
                                fingerprintBytes = bytes,
                                masterFingerprint = uiState.masterFingerprintBytes
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Continue button
                Button(
                    onClick = {
                        if (uiState.passphrase.isNotEmpty()) {
                            viewModel.setPendingPassphrase()
                            onNavigateConfirmPassphrase()
                        } else {
                            viewModel.confirmAndSave(onWalletCreated)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(buttonBringIntoViewRequester),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text(if (uiState.passphrase.isNotEmpty()) "Continue" else "I've Written It Down — Continue")
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
}


