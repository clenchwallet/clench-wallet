package net.clench.wallet.ui.screens

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.util.BiometricHelper
import net.clench.wallet.ui.viewmodel.ViewSeedPhraseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSeedPhraseScreen(
    walletId: String,
    onBack: () -> Unit,
    viewModel: ViewSeedPhraseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // FLAG_SECURE — prevent screenshots
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    // Resolve FragmentActivity from Compose context (unwrap ContextWrapper chain)
    val fragmentActivity = remember(context) {
        var ctx = context as? android.content.Context
        while (ctx != null) {
            if (ctx is FragmentActivity) return@remember ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        null
    }

    // Warning dialog
    if (uiState.showWarning) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("⚠️ Security Warning") },
            text = {
                Text(
                    "Your seed phrase gives FULL access to your bitcoin. " +
                    "Never share it. Make sure no one can see your screen.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    // R7-7: Only show biometric if the setting is enabled
                    if (uiState.biometricForSeedEnabled && fragmentActivity != null && BiometricHelper.canAuthenticate(context)) {
                        BiometricHelper.authenticate(
                            activity = fragmentActivity,
                            title = "Authenticate to view seed phrase",
                            subtitle = "Verify your identity to access sensitive data",
                            onSuccess = { viewModel.confirmWarning() },
                            onFailure = { /* user can retry via the dialog */ }
                        )
                    } else {
                        // Biometric disabled or not available — allow access
                        viewModel.confirmWarning()
                    }
                }) {
                    Text("I understand, show seed phrase")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onBack) {
                    Text("Go back")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Seed Phrase") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.showWarning) {
            // Empty content while warning is shown
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        } else if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Warning banner
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "🔒 Do not share these words with anyone. " +
                        "Anyone with this seed phrase can steal all your bitcoin.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mnemonic grid
                if (uiState.mnemonic.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(uiState.mnemonic) { index, word ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
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
                }

                // Show passphrase if set
                uiState.passphrase?.let { pass ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Passphrase",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                pass,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
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
}
