package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityOnboardingScreen(
    onComplete: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    SecureWindowEffect()

    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showPinSetup by remember { mutableStateOf(false) }
    var pinEntry by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    val canBiometric = BiometricHelper.canAuthenticate(context)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Secure Your Wallet") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Choose how to lock access to your app. You can change this later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Biometric
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.appLockMode == "biometric")
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = {
                    if (canBiometric) {
                        viewModel.setAppLockMode("biometric")
                        viewModel.clearPin()
                    }
                }
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("🔐 Biometric Lock", fontWeight = FontWeight.Bold)
                    Text(
                        if (canBiometric) "Use fingerprint or face unlock (recommended)"
                        else "No biometric enrolled on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canBiometric) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            // Clench PIN
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.appLockMode == "pin")
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = { showPinSetup = true }
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("🔢 Clench PIN", fontWeight = FontWeight.Bold)
                    Text(
                        "A numeric PIN managed by Clench. " +
                        if (uiState.isPinSet) "PIN is set ✓" else "Tap to set a PIN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Skip
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.appLockMode == "none")
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = { viewModel.setAppLockMode("none"); viewModel.clearPin() }
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("🔓 No lock", fontWeight = FontWeight.Bold)
                    Text(
                        "App is accessible without authentication. Not recommended.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Warning if "none" selected
            if (uiState.appLockMode == "none") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                ) {
                    Text(
                        "⚠ Without a lock, anyone who picks up your phone can access your wallet.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5D4037)
                    )
                }
            }

            if (!canBiometric) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        BiometricHelper.authenticationUnavailableGuidance() +
                            " Continue will turn off seed-view and transaction-signing authentication " +
                            "gates so the wallet remains usable.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    viewModel.disableAuthenticationGatesWhenUnavailable(canBiometric)
                    onComplete()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue")
            }
        }
    }

    // PIN setup dialog
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
                    pinError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
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
                OutlinedButton(onClick = {
                    showPinSetup = false; pinEntry = ""; pinConfirm = ""; pinError = null
                }) { Text("Cancel") }
            }
        )
    }
}
