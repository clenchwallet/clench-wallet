package net.clench.wallet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.viewmodel.RecoveryWizardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryWizardScreen(
    onBack: () -> Unit,
    onRestoreSeed: () -> Unit,
    onImportDescriptor: () -> Unit,
    onImportHardwareWallet: () -> Unit,
    onCreateMultisig: () -> Unit,
    onOpenWalletList: () -> Unit,
    viewModel: RecoveryWizardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importStateBackup(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recovery Wizard") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RecoveryWarningCard()

            RecoverySection(
                title = "1. Choose Recovery Source",
                body = "Use the narrowest source that matches what you actually backed up. Clench state backups restore wallet metadata and labels, not spending authority."
            )

            RecoveryActionCard(
                title = "Clench State Backup",
                body = "Restores public descriptors, wallet metadata, labels, UTXO notes, and non-secret settings. Hot wallets come back watch-only until the matching seed phrase is restored.",
                primary = {
                    Button(
                        onClick = { backupImportLauncher.launch(arrayOf("application/json", "text/json", "*/*")) },
                        enabled = !uiState.isImportingBackup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.isImportingBackup) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        else Text("Import Clench Backup")
                    }
                }
            )

            RecoveryActionCard(
                title = "Seed Phrase or Passphrase Wallet",
                body = "Restores single-sig spend authority when the seed, script type, network, and optional passphrase match the original wallet.",
                primary = {
                    OutlinedButton(onClick = onRestoreSeed, modifier = Modifier.fillMaxWidth()) {
                        Text("Restore Seed Phrase")
                    }
                }
            )

            RecoveryActionCard(
                title = "Descriptor, Xpub, BSMS, or Multisig Config",
                body = "Restores watch-only wallet structure. For multisig, the file must preserve threshold, script type, and every cosigner fingerprint/path/xpub.",
                primary = {
                    OutlinedButton(onClick = onImportDescriptor, modifier = Modifier.fillMaxWidth()) {
                        Text("Import Descriptor or Config")
                    }
                }
            )

            RecoveryActionCard(
                title = "Hardware Wallet Public Export",
                body = "Use this when the spend key lives on a device. Clench imports public wallet policy and keeps signing on the hardware wallet.",
                primary = {
                    OutlinedButton(onClick = onImportHardwareWallet, modifier = Modifier.fillMaxWidth()) {
                        Text("Use Hardware Wallet Export")
                    }
                }
            )

            uiState.importStatus?.let { status ->
                StatusCard(
                    title = "Backup Import Complete",
                    body = status,
                    actionLabel = "Open Wallet List",
                    onAction = onOpenWalletList,
                    onDismiss = viewModel::clearStatus
                )
            }

            uiState.importError?.let { error ->
                StatusCard(
                    title = "Backup Import Failed",
                    body = error,
                    actionLabel = null,
                    onAction = null,
                    onDismiss = viewModel::clearStatus,
                    isError = true
                )
            }

            RecoverySection(
                title = "2. Verify Before Trusting",
                body = "Do not fund or spend from a recovered wallet until these checks match your original wallet."
            )
            Checklist(
                items = listOf(
                    "Network is correct: mainnet vs testnet.",
                    "First receive address matches the original wallet or coordinator.",
                    "Script type and derivation path match the original wallet.",
                    "Master fingerprint matches the seed or signer you expect.",
                    "Multisig threshold and every cosigner match before funding.",
                    "A small receive and spend rehearsal succeeds before meaningful funds move."
                )
            )

            RecoverySection(
                title = "3. Compatible Import Methods",
                body = "Clench works best with backups that preserve wallet policy and key-origin metadata. Prefer these methods over isolated xpubs whenever possible."
            )
            RecoveryMethodGuide()

            RecoveryActionCard(
                title = "Replacing a Multisig Signer",
                body = "Do not mutate an existing funded policy in place. Create a new multisig wallet with the replacement signer set, verify addresses, then migrate funds with a small test first.",
                primary = {
                    OutlinedButton(onClick = onCreateMultisig, modifier = Modifier.fillMaxWidth()) {
                        Text("Create New Multisig Wallet")
                    }
                }
            )
        }
    }
}

@Composable
private fun RecoveryWarningCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Recovery Is Verification",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "A backup that opens a wallet is not enough. Verify addresses, fingerprints, script type, and multisig policy before trusting it with funds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun RecoverySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecoveryActionCard(
    title: String,
    body: String,
    primary: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            primary()
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onDismiss: () -> Unit,
    isError: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actionLabel != null && onAction != null) {
                    Button(onClick = onAction) { Text(actionLabel) }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun Checklist(items: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                Text("• $item", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RecoveryMethodGuide() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecoveryMethodBlock(
                title = "Output Descriptor Backup",
                lines = listOf(
                    "Recommended for Clench imports and restores.",
                    "Preserves script type, derivation path, master fingerprint, and xpub structure.",
                    "Restores watch-only structure; seed phrases or hardware signers still control spending."
                )
            )
            HorizontalDivider()
            RecoveryMethodBlock(
                title = "BSMS or Multisig Configuration",
                lines = listOf(
                    "Use for multisig wallets when the file records threshold, script type, and every signer.",
                    "Verify every signer fingerprint, derivation path, and xpub before funding.",
                    "Policy features outside plain descriptor recovery are not recreated in Clench."
                )
            )
            HorizontalDivider()
            RecoveryMethodBlock(
                title = "Hardware Signer Public Export",
                lines = listOf(
                    "Use when the spend key lives on a hardware signer.",
                    "Clench imports public wallet policy and keeps signing on the signer.",
                    "Confirm the first receive address on the signer or original coordinator before receiving funds."
                )
            )
            HorizontalDivider()
            RecoveryMethodBlock(
                title = "Seed Phrase Recovery",
                lines = listOf(
                    "Use for hot single-sig wallets only.",
                    "Script type, network, derivation path, and optional passphrase must match.",
                    "A seed phrase alone is not a complete multisig backup."
                )
            )
        }
    }
}

@Composable
private fun RecoveryMethodBlock(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        lines.forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodySmall)
        }
    }
}
