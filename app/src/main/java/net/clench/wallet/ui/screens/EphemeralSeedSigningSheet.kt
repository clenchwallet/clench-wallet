package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.components.QrScanner

private fun deviceLabel(device: String): String = when (device) {
    "SEEDSIGNER" -> "SeedSigner"
    "KEYSTONE" -> "Keystone"
    "PASSPORT" -> "Foundation Passport"
    "COLDCARD_Q" -> "Coldcard Q"
    "COLDCARD_MK4" -> "Coldcard Mk4"
    "JADE" -> "Blockstream Jade"
    else -> device
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchOnlySendSheet(
    onDismiss: () -> Unit,
    onHardwareWallet: () -> Unit,
    onHardwareWalletDirect: ((HardwareWalletType) -> Unit)? = null,
    onSeedProvided: (mnemonic: CharArray, passphrase: CharArray?, saveAsHotWallet: Boolean) -> Unit,
    preferredDevice: String? = null
) {
    val context = LocalContext.current
    var showSeedEntry by remember { mutableStateOf(false) }
    var seedInput by remember { mutableStateOf("") }
    var passphraseInput by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var seedError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Send from Watch-Only Wallet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This is a watch-only wallet. To send, provide signing capability:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (!showSeedEntry) {
                // Option 1: Hardware wallet
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (preferredDevice != null) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    onClick = {
                        // If a preferred device is set and we have a direct callback, skip the picker
                        val directType = preferredDevice?.let {
                            // Stored keys may differ from enum names (e.g. "PASSPORT" → FOUNDATION_PASSPORT)
                            try { HardwareWalletType.valueOf(it) } catch (_: Exception) {
                                when (it) {
                                    "PASSPORT" -> HardwareWalletType.FOUNDATION_PASSPORT
                                    else -> null
                                }
                            }
                        }
                        if (directType != null && onHardwareWalletDirect != null) {
                            onHardwareWalletDirect(directType)
                        } else {
                            onHardwareWallet()
                        }
                    }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Sign with Hardware Wallet", fontWeight = FontWeight.Bold)
                        Text(
                            if (preferredDevice != null) "Tap to sign with ${deviceLabel(preferredDevice)}"
                            else "SeedSigner, Keystone, Passport, Coldcard, Jade",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (preferredDevice != null)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option 2: Seed phrase
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    onClick = { showSeedEntry = true }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Sign with Seed Phrase", fontWeight = FontWeight.Bold)
                        Text(
                            "Enter seed phrase once to sign. Option to save and convert to hot wallet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Seed entry UI
                Text("Enter Seed Phrase", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                // Security notice
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                    Text(
                        "⚠ Your seed phrase will be used to sign this transaction in memory. " +
                        "Key material is zeroed immediately after signing.",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5D4037)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = seedInput,
                    onValueChange = { seedInput = it; seedError = null },
                    label = { Text("Seed phrase (12 or 24 words)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("word1 word2 word3…") },
                    trailingIcon = {
                        IconButton(onClick = { showScanner = true }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Scan QR")
                        }
                    }
                )

                // QR scanner overlay
                if (showScanner) {
                    QrScanner(
                        onResult = { result ->
                            seedInput = result
                            showScanner = false
                        },
                        onCancel = { showScanner = false }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Optional passphrase
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = showPassphrase, onCheckedChange = { showPassphrase = it })
                    Text("BIP39 passphrase (optional)")
                }
                if (showPassphrase) {
                    OutlinedTextField(
                        value = passphraseInput,
                        onValueChange = { passphraseInput = it },
                        label = { Text("Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                seedError?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))

                val words = seedInput.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                val isValidWordCount = words.size == 12 || words.size == 24

                // Sign & discard button
                Button(
                    onClick = {
                        if (!isValidWordCount) { seedError = "Enter 12 or 24 words"; return@Button }
                        val mnemonic = seedInput.trim().toCharArray()
                        val passphrase = if (showPassphrase && passphraseInput.isNotBlank())
                            passphraseInput.toCharArray() else null
                        // Zero the string fields after converting to CharArray
                        seedInput = ""
                        passphraseInput = ""
                        onSeedProvided(mnemonic, passphrase, false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isValidWordCount
                ) { Text("Sign & Broadcast — Discard Seed") }

                Spacer(modifier = Modifier.height(8.dp))

                // Sign & save button
                OutlinedButton(
                    onClick = {
                        if (!isValidWordCount) { seedError = "Enter 12 or 24 words"; return@OutlinedButton }
                        val mnemonic = seedInput.trim().toCharArray()
                        val passphrase = if (showPassphrase && passphraseInput.isNotBlank())
                            passphraseInput.toCharArray() else null
                        seedInput = ""
                        passphraseInput = ""
                        onSeedProvided(mnemonic, passphrase, true)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isValidWordCount
                ) { Text("Sign & Broadcast — Save Seed (Convert to Hot Wallet)") }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showSeedEntry = false; seedInput = ""; passphraseInput = "" },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back")
                }
            }
        }
    }
}
