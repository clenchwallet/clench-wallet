package net.clench.wallet.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.CreateWalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWalletScreen(
    onWalletCreated: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CreateWalletViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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

            // Collapsible passphrase section
            var passphraseExpanded by remember { mutableStateOf(false) }

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { passphraseExpanded = !passphraseExpanded }
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
                            "  • Your 24-word seed phrase\n" +
                            "  • This exact passphrase\n\n" +
                            "Write your passphrase down separately from your seed phrase and store both in secure locations.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF5D4037)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Passphrase field — always plain text, no hide toggle
                    OutlinedTextField(
                        value = uiState.passphrase,
                        onValueChange = { viewModel.setPassphrase(it) },
                        label = { Text("Passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Confirm passphrase field
                    OutlinedTextField(
                        value = uiState.passphraseConfirm,
                        onValueChange = { viewModel.setPassphraseConfirm(it) },
                        label = { Text("Confirm passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = uiState.passphraseError != null
                    )
                    uiState.passphraseError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }

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
                        WalletFingerprint(bytes)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

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
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(uiState.mnemonic) { index, word ->
                        Card {
                            Row(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    "${index + 1}.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(word, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // "I've Written It Down" button — disabled if passphrase mismatch or confirm empty
                val passphraseValid = uiState.passphraseError == null &&
                    (uiState.passphrase.isEmpty() || uiState.passphraseConfirm.isNotEmpty())

                Button(
                    onClick = { viewModel.confirmAndSave(onWalletCreated) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading && passphraseValid
                ) {
                    if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("I've Written It Down — Continue")
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
                            modifier = androidx.compose.ui.Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Visual + text wallet fingerprint — a deterministic 2×4 grid of colored squares
 * derived from 8 bytes of SHA-256(masterFingerprint + passphrase).
 * Each byte maps to a hue-based color for perceptually meaningful changes.
 */
@Composable
private fun WalletFingerprint(fingerprintBytes: ByteArray) {
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
        // 2 rows × 4 columns
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
