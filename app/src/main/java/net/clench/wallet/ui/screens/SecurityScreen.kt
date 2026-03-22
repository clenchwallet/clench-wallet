package net.clench.wallet.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.util.BiometricHelper
import net.clench.wallet.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showPinSetup by remember { mutableStateOf(false) }
    var pinEntry by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
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
            Text("App Lock", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Choose how to protect access to your wallet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // None
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.appLockMode == "none")
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = { viewModel.setAppLockMode("none"); viewModel.clearPin() }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No lock", fontWeight = FontWeight.Bold)
                    Text("App is accessible without authentication",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Biometric
            val canBiometric = BiometricHelper.canAuthenticate(context)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.appLockMode == "biometric")
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = { if (canBiometric) { viewModel.setAppLockMode("biometric"); viewModel.clearPin() } }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Biometric", fontWeight = FontWeight.Bold)
                    Text(
                        if (canBiometric) "Use fingerprint or face unlock (OS-managed)"
                        else "No biometric enrolled on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canBiometric) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // PIN
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.appLockMode == "pin")
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = { showPinSetup = true }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Clench PIN", fontWeight = FontWeight.Bold)
                    Text(
                        "A numeric PIN managed by Clench. " + if (uiState.isPinSet) "PIN is active." else "No PIN set.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showPinSetup) {
                AlertDialog(
                    onDismissRequest = { showPinSetup = false; pinEntry = ""; pinConfirm = ""; pinError = null },
                    title = { Text("Set Clench PIN") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                                Text(
                                    "⚠ No data wipe on failed attempts. After 5 wrong attempts, " +
                                    "a time delay is enforced (30s, 60s, 120s… up to 30 min). " +
                                    "Choose a PIN you will remember.",
                                    modifier = Modifier.padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF5D4037)
                                )
                            }
                            OutlinedTextField(
                                value = pinEntry,
                                onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) pinEntry = it },
                                label = { Text("PIN (min 6 digits)") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = pinConfirm,
                                onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) pinConfirm = it },
                                label = { Text("Confirm PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            pinError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (pinEntry != pinConfirm) { pinError = "PINs do not match"; return@Button }
                            val err = viewModel.setPin(pinEntry.toCharArray())
                            if (err != null) { pinError = err } else {
                                viewModel.setAppLockMode("pin")
                                showPinSetup = false; pinEntry = ""; pinConfirm = ""; pinError = null
                            }
                        }) { Text("Set PIN") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showPinSetup = false; pinEntry = ""; pinConfirm = ""; pinError = null }) { Text("Cancel") }
                    }
                )
            }

            if (uiState.appLockMode != "none") {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Auto-lock Timeout", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                listOf("30s" to "30 seconds", "1min" to "1 minute", "5min" to "5 minutes", "never" to "Never").forEach { (key, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = uiState.lockTimeoutKey == key, onClick = { viewModel.setLockTimeout(key) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("Authentication Gates", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("These apply regardless of app lock setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = uiState.biometricForSeed, onCheckedChange = { viewModel.setBiometricForSeed(it) })
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Require authentication to view seed phrase")
                    Text("Authenticate before showing seed words",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = uiState.biometricForSend, onCheckedChange = { viewModel.setBiometricForSend(it) })
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Require authentication to send")
                    Text("Authenticate before building a transaction",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
