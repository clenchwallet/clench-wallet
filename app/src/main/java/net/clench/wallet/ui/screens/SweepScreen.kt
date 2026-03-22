package net.clench.wallet.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.FeeTier
import net.clench.wallet.ui.viewmodel.SweepViewModel

/**
 * Sweep wallet screen: allows sweeping all funds from an external seed phrase
 * into the current wallet's receive address.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SweepScreen(
    walletId: String,
    onBack: () -> Unit,
    viewModel: SweepViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Seed phrase input state (kept local for security — never exposed to ViewModel until submit)
    var seedInput by remember { mutableStateOf("") }
    var passphraseInput by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var showPassphraseText by remember { mutableStateOf(false) }
    var seedError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    // Navigate back on successful broadcast
    LaunchedEffect(uiState.broadcastTxid) {
        if (uiState.broadcastTxid != null) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sweep to Wallet") },
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
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Sweep External Wallet", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Enter the seed phrase of the wallet you want to sweep FROM. " +
                        "All confirmed funds will be sent to this wallet's receive address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Destination address
            OutlinedTextField(
                value = uiState.destinationAddress,
                onValueChange = { viewModel.setDestinationAddress(it) },
                label = { Text("Destination address (this wallet)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Security warning
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Text(
                    "⚠ Your seed phrase is used in memory only. Key material is zeroed " +
                    "immediately after the sweep transaction is broadcast.",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5D4037)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Source seed phrase
            Text("Source Wallet Seed Phrase", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = seedInput,
                onValueChange = { seedInput = it; seedError = null },
                label = { Text("Seed phrase (12 or 24 words)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                placeholder = { Text("word1 word2 word3…") },
                isError = seedError != null
            )
            seedError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Optional passphrase
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showPassphrase, onCheckedChange = { showPassphrase = it })
                Text("BIP39 passphrase (optional)")
            }

            AnimatedVisibility(visible = showPassphrase) {
                OutlinedTextField(
                    value = passphraseInput,
                    onValueChange = { passphraseInput = it },
                    label = { Text("Passphrase") },
                    visualTransformation = if (showPassphraseText) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { showPassphraseText = !showPassphraseText }) {
                            Text(if (showPassphraseText) "Hide" else "Show")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fee section
            Text("Fee Rate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            val estimates = uiState.feeEstimates
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.ECONOMY,
                    onClick = { viewModel.selectFeeTier(FeeTier.ECONOMY) },
                    label = {
                        Column {
                            Text("Economy", fontWeight = FontWeight.Bold)
                            if (estimates != null) Text("${estimates.economy.toInt()} sat/vB", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.STANDARD,
                    onClick = { viewModel.selectFeeTier(FeeTier.STANDARD) },
                    label = {
                        Column {
                            Text("Standard", fontWeight = FontWeight.Bold)
                            if (estimates != null) Text("${estimates.standard.toInt()} sat/vB", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.PRIORITY,
                    onClick = { viewModel.selectFeeTier(FeeTier.PRIORITY) },
                    label = {
                        Column {
                            Text("Priority", fontWeight = FontWeight.Bold)
                            if (estimates != null) Text("${estimates.priority.toInt()} sat/vB", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.CUSTOM,
                    onClick = { viewModel.selectFeeTier(FeeTier.CUSTOM) },
                    label = { Text("Custom") }
                )
            }

            AnimatedVisibility(visible = uiState.selectedFeeTier == FeeTier.CUSTOM) {
                OutlinedTextField(
                    value = uiState.feeRate,
                    onValueChange = { viewModel.setFeeRate(it) },
                    label = { Text("Fee rate (sat/vB)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            if (uiState.selectedFeeTier != FeeTier.CUSTOM) {
                Text(
                    "Fee rate: ${uiState.feeRate} sat/vB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Validate seed button (check balance before sweeping)
            if (!uiState.seedValidated) {
                val words = seedInput.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                val isValidWordCount = words.size == 12 || words.size == 24

                Button(
                    onClick = {
                        if (!isValidWordCount) {
                            seedError = "Enter 12 or 24 words"
                            return@Button
                        }
                        val mnemonic = seedInput.trim().toCharArray()
                        val passphrase = if (showPassphrase && passphraseInput.isNotBlank())
                            passphraseInput.toCharArray() else null
                        // Note: chars zeroed inside ViewModel after use
                        viewModel.validateSeedAndFetchBalance(mnemonic, passphrase)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoadingBalance && isValidWordCount
                ) {
                    if (uiState.isLoadingBalance) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking balance…")
                    } else {
                        Text("Check Balance")
                    }
                }
            } else {
                // Show found balance and sweep button
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Source Wallet Balance", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Confirmed: ${uiState.sourceBalanceSat} sats",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.sourcePendingSat > 0) {
                            Text(
                                "Pending: ${uiState.sourcePendingSat} sats (not swept)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.sourceBalanceSat > 0) {
                    Button(
                        onClick = {
                            val words = seedInput.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                            if (words.size != 12 && words.size != 24) {
                                seedError = "Seed phrase was cleared — re-enter it"
                                return@Button
                            }
                            val mnemonic = seedInput.trim().toCharArray()
                            val passphrase = if (showPassphrase && passphraseInput.isNotBlank())
                                passphraseInput.toCharArray() else null
                            seedInput = ""
                            passphraseInput = ""
                            viewModel.sweep(mnemonic, passphrase)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSweeping
                    ) {
                        if (uiState.isSweeping) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sweeping…")
                        } else {
                            Text("Sweep ${uiState.sourceBalanceSat} sats → This Wallet")
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            "No confirmed funds found in source wallet.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
