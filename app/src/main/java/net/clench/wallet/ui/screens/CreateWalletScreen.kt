package net.clench.wallet.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.CreateWalletViewModel
import java.security.MessageDigest

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
            var showPassphrase by remember { mutableStateOf(false) }

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
                    // Warning card
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3E0)  // amber/orange
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "⚠️ A passphrase creates a completely different wallet. " +
                            "If you forget it, your bitcoin is LOST FOREVER. There is no recovery. " +
                            "Write it down separately from your seed phrase.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF5D4037)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.passphrase,
                        onValueChange = { viewModel.setPassphrase(it) },
                        label = { Text("Passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showPassphrase) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showPassphrase = !showPassphrase }) {
                                Text(if (showPassphrase) "Hide" else "Show")
                            }
                        }
                    )
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
                    "⚠️ Write these words down. Never share them.",
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

                // Visual wallet fingerprint
                Spacer(modifier = Modifier.height(12.dp))
                WalletFingerprint(uiState.mnemonic)

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { viewModel.confirmAndSave(onWalletCreated) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
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
 * Visual wallet fingerprint — a deterministic 2x4 grid of colored squares
 * derived from SHA-256 of the first mnemonic word list (used as a visual confirmation aid).
 */
@Composable
private fun WalletFingerprint(mnemonic: List<String>) {
    if (mnemonic.isEmpty()) return

    val colors = remember(mnemonic) {
        val palette = listOf(
            Color(0xFFE53935), // Red
            Color(0xFF1E88E5), // Blue
            Color(0xFF43A047), // Green
            Color(0xFFFDD835), // Yellow
            Color(0xFF8E24AA), // Purple
            Color(0xFFFF6F00), // Orange
            Color(0xFF00ACC1), // Cyan
            Color(0xFF6D4C41), // Brown
            Color(0xFFD81B60), // Pink
            Color(0xFF00897B), // Teal
            Color(0xFF3949AB), // Indigo
            Color(0xFF7CB342), // Light green
            Color(0xFFFFB300), // Amber
            Color(0xFF5E35B1), // Deep purple
            Color(0xFFF4511E), // Deep orange
            Color(0xFF546E7A)  // Blue grey
        )
        try {
            val input = mnemonic.joinToString(" ")
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            // Take first 8 bytes, map to colors
            (0 until 8).map { i ->
                val byteVal = digest[i].toInt() and 0xFF
                palette[byteVal % palette.size]
            }
        } catch (e: Exception) {
            List(8) { Color.Gray }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "Wallet fingerprint",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        // 2 rows x 4 columns
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.Center) {
                for (col in 0 until 4) {
                    val idx = row * 4 + col
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(1.dp)
                            .background(colors[idx], MaterialTheme.shapes.extraSmall)
                    )
                }
            }
        }
    }
}
