package net.clench.wallet.ui.screens

import android.content.ContextWrapper
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
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.security.AuthenticationGate
import net.clench.wallet.ui.util.BiometricHelper
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    SecureWindowEffect()

    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Resolve FragmentActivity for biometric calls
    val fragmentActivity = remember(context) {
        var ctx = context as? android.content.Context
        while (ctx != null) {
            if (ctx is FragmentActivity) return@remember ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        null
    }

    var showPinSetup by remember { mutableStateOf(false) }
    var pinEntry by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    // State for verifying current auth before mode change
    var pendingModeChange by remember { mutableStateOf<String?>(null) }
    var verifyPinEntry by remember { mutableStateOf("") }
    var verifyPinError by remember { mutableStateOf<String?>(null) }
    var showVerifyPin by remember { mutableStateOf(false) }
    var securityError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelAuthenticationGateChange() }
    }

    fun changeGate(gate: AuthenticationGate, enabled: Boolean) {
        securityError = null
        viewModel.requestAuthenticationGateChange(gate, enabled, fragmentActivity) {
            securityError = it
        }
    }

    val canBiometric = BiometricHelper.canAuthenticate(context)

    // Helper: attempt a mode change, verifying current auth first if needed
    fun attemptModeChange(newMode: String) {
        securityError = null
        when (uiState.appLockMode) {
            "pin" -> {
                // Must verify current PIN before changing
                pendingModeChange = newMode
                showVerifyPin = true
            }
            "biometric" -> {
                // Must verify biometric before changing
                pendingModeChange = newMode
                if (fragmentActivity != null) {
                    BiometricHelper.authenticate(
                        activity = fragmentActivity,
                        title = "Verify identity",
                        subtitle = "Authenticate to change security settings",
                        onSuccess = {
                            val mode = pendingModeChange
                            pendingModeChange = null
                            when (mode) {
                                "none" -> { viewModel.setAppLockMode("none"); viewModel.clearPin() }
                                "pin" -> showPinSetup = true
                                // biometric → biometric: re-set same mode (no-op visually)
                                "biometric" -> { viewModel.setAppLockMode("biometric"); viewModel.clearPin() }
                            }
                        },
                        onFailure = { message ->
                            pendingModeChange = null
                            securityError = "Authentication failed: $message"
                        },
                        onCancel = { pendingModeChange = null }
                    )
                } else {
                    // Never turn an authentication failure into authorization.
                    pendingModeChange = null
                    securityError = BiometricHelper.authenticationUnavailableGuidance()
                }
            }
            else -> {
                // "none" — no current auth, allow direct change
                when (newMode) {
                    "none" -> { viewModel.setAppLockMode("none"); viewModel.clearPin() }
                    "biometric" -> if (canBiometric) { viewModel.setAppLockMode("biometric"); viewModel.clearPin() }
                    "pin" -> showPinSetup = true
                }
            }
        }
    }

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
            securityError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
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
                onClick = { attemptModeChange("none") }
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.appLockMode == "biometric")
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = { if (canBiometric) attemptModeChange("biometric") }
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
                onClick = { attemptModeChange("pin") }
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

            // PIN setup dialog (new PIN or change PIN)
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

            // PIN verification dialog (verify CURRENT pin before changing mode)
            if (showVerifyPin) {
                AlertDialog(
                    onDismissRequest = {
                        showVerifyPin = false
                        verifyPinEntry = ""
                        verifyPinError = null
                        pendingModeChange = null
                    },
                    title = { Text("Verify Current PIN") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Enter your current PIN to change security settings.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedTextField(
                                value = verifyPinEntry,
                                onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) verifyPinEntry = it },
                                label = { Text("Current PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            verifyPinError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (viewModel.verifyPin(verifyPinEntry.toCharArray())) {
                                showVerifyPin = false
                                val mode = pendingModeChange
                                verifyPinEntry = ""
                                verifyPinError = null
                                pendingModeChange = null
                                when (mode) {
                                    "none" -> { viewModel.setAppLockMode("none"); viewModel.clearPin() }
                                    "biometric" -> { viewModel.setAppLockMode("biometric"); viewModel.clearPin() }
                                    "pin" -> showPinSetup = true // Change PIN
                                }
                            } else {
                                verifyPinError = "Incorrect PIN"
                                verifyPinEntry = ""
                            }
                        }) { Text("Verify") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            showVerifyPin = false
                            verifyPinEntry = ""
                            verifyPinError = null
                            pendingModeChange = null
                        }) { Text("Cancel") }
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
            if (!canBiometric) {
                Text(
                    BiometricHelper.authenticationUnavailableGuidance() +
                        " Enabled protections stay on until you can authenticate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiState.biometricForSeed,
                    onCheckedChange = { changeGate(AuthenticationGate.SEED, it) },
                    enabled = canBiometric
                )
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
                Switch(
                    checked = uiState.biometricForSend,
                    onCheckedChange = { changeGate(AuthenticationGate.SEND, it) },
                    enabled = canBiometric
                )
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
