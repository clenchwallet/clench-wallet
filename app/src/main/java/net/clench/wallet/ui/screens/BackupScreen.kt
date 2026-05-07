package net.clench.wallet.ui.screens

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import net.clench.wallet.ui.util.copyToClipboardWithAutoClear
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.components.QrCodeImage
import net.clench.wallet.ui.components.WalletFingerprint
import net.clench.wallet.ui.util.BiometricHelper
import net.clench.wallet.ui.viewmodel.BackupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    walletId: String,
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // FLAG_SECURE when seed is revealed
    DisposableEffect(uiState.seedRevealed) {
        val activity = context as? Activity
        if (uiState.seedRevealed) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    val fragmentActivity = remember(context) {
        var ctx = context as? android.content.Context
        while (ctx != null) {
            if (ctx is FragmentActivity) return@remember ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Export") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ─── Option A: Export Public Key (always available) ───
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Export Public Key",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Share this with watch-only wallets or coordinators. This is NOT a backup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (uiState.accountXpub.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                uiState.xpubLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            QrCodeImage(
                                data = uiState.accountXpub,
                                modifier = Modifier.size(200.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                uiState.accountXpub.take(8) + "…" + uiState.accountXpub.takeLast(6),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = {
                                    copyToClipboardWithAutoClear(context, "Public Key", uiState.accountXpub)
                                }
                            ) {
                                Text("Copy ${uiState.xpubLabel}")
                            }
                        }
                    }
                }

                if (uiState.descriptor.isNotBlank() && uiState.changeDescriptor.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (uiState.isMultisig) "Multisig Descriptor Backup" else "Descriptor Backup",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (uiState.isMultisig) {
                                    "Public descriptors restore this wallet as watch-only. Preserve the threshold, every cosigner fingerprint/path/xpub, and compare the first receive address before funding."
                                } else {
                                    "Public descriptors restore this wallet as watch-only. They do not include seed phrases, passphrases, or private keys, but they can reveal wallet history."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "A descriptor backup is recovery metadata, not spend authority.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Receive descriptor", style = MaterialTheme.typography.labelMedium)
                            Text(
                                uiState.descriptor,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Change descriptor", style = MaterialTheme.typography.labelMedium)
                            Text(
                                uiState.changeDescriptor,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    copyToClipboardWithAutoClear(
                                        context,
                                        "Wallet descriptors",
                                        "receive=${uiState.descriptor}\nchange=${uiState.changeDescriptor}"
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Copy Descriptors") }
                        }
                    }
                }

                // ─── Option B: Backup Seed Phrase (hot wallets only) ───
                if (!uiState.isWatchOnly) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Backup Seed Phrase",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Your seed phrase is the master backup of your wallet. Write it down and store securely.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            if (!uiState.seedRevealed) {
                                // Reveal button with biometric auth
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "🔒 Your seed phrase gives FULL access to your bitcoin. Never share it with anyone.",
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        if (fragmentActivity != null && BiometricHelper.canAuthenticate(context)) {
                                            BiometricHelper.authenticate(
                                                activity = fragmentActivity,
                                                title = "Authenticate to view seed phrase",
                                                subtitle = "Verify your identity to access your backup",
                                                onSuccess = { viewModel.revealSeed() },
                                                onFailure = { /* user can retry */ },
                                                allowUiOnlyFallback = false
                                            )
                                        } else {
                                            viewModel.revealSeed()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Reveal Seed Phrase")
                                }
                            } else {
                                // Seed phrase revealed — show numbered grid
                                if (uiState.mnemonic.isNotEmpty()) {
                                    // Passphrase warning
                                    if (uiState.hasPassphrase) {
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = Color(0xFFFFF3E0)
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                "⚠️ This wallet was created with a passphrase.\n" +
                                                "Your seed phrase alone is NOT enough to recover your funds.\n" +
                                                "You need BOTH your seed phrase AND your passphrase.",
                                                modifier = Modifier.padding(12.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF5D4037)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                    }

                                    // Seed words grid (3 columns)
                                    val words = uiState.mnemonic
                                    val rows = (words.size + 2) / 3
                                    for (row in 0 until rows) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            for (col in 0 until 3) {
                                                val idx = row * 3 + col
                                                if (idx < words.size) {
                                                    Card(
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                                        ),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Row(modifier = Modifier.padding(8.dp)) {
                                                            Text(
                                                                "${idx + 1}.",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(words[idx], style = MaterialTheme.typography.bodyMedium)
                                                        }
                                                    }
                                                } else {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                        if (row < rows - 1) Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    // Wallet fingerprint
                                    if (!uiState.isMultisig) uiState.fingerprintBytes?.let { fpBytes ->
                                        Spacer(modifier = Modifier.height(16.dp))
                                        WalletFingerprint(
                                            fingerprintBytes = fpBytes,
                                            masterFingerprint = uiState.masterFingerprintBytes,
                                            label = "Wallet fingerprint"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                uiState.error?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
