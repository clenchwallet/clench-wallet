package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import net.clench.wallet.ui.components.WalletFingerprint
import net.clench.wallet.ui.viewmodel.PassphraseUnlockViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassphraseUnlockScreen(
    walletId: String,
    walletName: String,
    storedIdenticonBytes: ByteArray?,
    onUnlocked: () -> Unit,
    onBack: () -> Unit,
    onSwitchWallet: (() -> Unit)? = null,
    viewModel: PassphraseUnlockViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(walletId) {
        viewModel.load(walletId, storedIdenticonBytes)
    }

    // Clear the passphrase field every time this screen becomes visible (ON_RESUME).
    // This prevents the passphrase from being visible when navigating back to this screen,
    // whether from the back stack or after a failed unlock attempt.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.clearPassphrase()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.isUnlocked) {
        if (uiState.isUnlocked) {
            onUnlocked()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unlock Wallet") },
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
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Wallet name
            Text(
                text = walletName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "This wallet uses a passphrase",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Duress wallet notice
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "⚠ Any passphrase opens a wallet — there is no \"wrong passphrase\" error by design. " +
                    "If you mistype, you will silently open a different (empty) wallet. " +
                    "Verify the fingerprint and identicon below match what you expect.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5D4037)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Passphrase field — masked by default with show/hide toggle
            var passphraseVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = uiState.passphrase,
                onValueChange = { viewModel.setPassphrase(it) },
                label = { Text("Passphrase") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = uiState.error != null,
                visualTransformation = if (passphraseVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                        Text(if (passphraseVisible) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )

            // Only show errors that are not passphrase validation (those are silent by design)
            uiState.error?.let { error ->
                if (!error.contains("passphrase", ignoreCase = true) &&
                    !error.contains("does not match", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Live fingerprint display
            uiState.fingerprintBytes?.let { fpBytes ->
                WalletFingerprint(
                    fingerprintBytes = fpBytes,
                    masterFingerprint = uiState.masterFingerprintBytes,
                    size = 72.dp,
                    label = if (storedIdenticonBytes != null) {
                        "Compare to your saved fingerprint"
                    } else {
                        "Your wallet fingerprint"
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // No match/mismatch indicator — any passphrase is valid by design (duress wallet)
                // User identifies the correct wallet by recognising the fingerprint and identicon
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Unlock button
            Button(
                onClick = { viewModel.unlock() },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.passphrase.isNotEmpty() && !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Unlock Wallet")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hint text
            Text(
                "Every passphrase opens a valid wallet. Verify the fingerprint matches before unlocking. Balance syncs from your Electrum server after unlock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // Switch Wallet button — escape hatch when user doesn't want to unlock
            if (onSwitchWallet != null) {
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(
                    onClick = onSwitchWallet,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Switch Wallet")
                }
            }
        }
    }
}
