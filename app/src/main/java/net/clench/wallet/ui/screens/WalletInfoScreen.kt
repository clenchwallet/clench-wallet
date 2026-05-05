package net.clench.wallet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.components.QrCodeImage
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.viewmodel.WalletInfoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletInfoScreen(
    walletId: String,
    onBack: () -> Unit,
    onViewAddresses: () -> Unit = {},
    onBackup: () -> Unit = {},
    onViewSeedPhrase: () -> Unit = {},
    viewModel: WalletInfoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showQrDialog by remember { mutableStateOf(false) }
    var showSeedImportSheet by remember { mutableStateOf(false) }
    var expandedXpub by remember { mutableStateOf(false) }
    var showHardwareWalletMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val labelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importLabels(it) }
    }

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    LaunchedEffect(uiState.labelImportExportResult) {
        uiState.labelImportExportResult?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearLabelImportExportResult()
        }
    }

    if (showSeedImportSheet) {
        AddSeedPhraseToWalletSheet(
            isLoading = uiState.isConvertingToHot,
            onDismiss = { showSeedImportSheet = false },
            onConfirm = { mnemonic, passphrase ->
                showSeedImportSheet = false
                viewModel.convertWatchOnlyToHot(mnemonic, passphrase)
            }
        )
    }

    if (uiState.convertedToHot) {
        AlertDialog(
            onDismissRequest = { viewModel.clearConversionSuccess() },
            title = { Text("Seed Phrase Added") },
            text = { Text("This wallet now has signing capability. Seed phrase entry remains a wallet-management action, not part of transaction signing.") },
            confirmButton = {
                Button(onClick = { viewModel.clearConversionSuccess() }) { Text("OK") }
            }
        )
    }

    // QR Dialog
    if (showQrDialog && uiState.accountXpub.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("${uiState.xpubLabel} — Account Public Key") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    QrCodeImage(
                        data = uiState.accountXpub,
                        modifier = Modifier.size(250.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        uiState.accountXpub,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.copyToClipboard(uiState.accountXpub, "Account Public Key")
                    }) { Text("Copy") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { showQrDialog = false }) { Text("Close") }
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(uiState.walletName.ifEmpty { "Wallet Info" }) },
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
                // ─── Wallet Details Card ───
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Name row with edit
                        if (uiState.isEditing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = uiState.editName,
                                    onValueChange = { viewModel.setEditName(it) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("Wallet Name") }
                                )
                                IconButton(onClick = { viewModel.saveName() }) {
                                    Icon(Icons.Default.Check, contentDescription = "Save")
                                }
                                IconButton(onClick = { viewModel.cancelEditing() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Wallet Name", style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.startEditing() }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit name",
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                            Text(uiState.walletName, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Type
                        Row {
                            Text("Type: ", style = MaterialTheme.typography.labelMedium)
                            Text(
                                if (uiState.isWatchOnly) "Watch-Only" else "Full Wallet",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Network with pill badge
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Network: ", style = MaterialTheme.typography.labelMedium)
                            val isTestnet = uiState.network == "testnet"
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (isTestnet) Color(0xFFFF9800) else MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    if (isTestnet) "Testnet" else "Mainnet",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isTestnet) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Transaction count
                        Row {
                            Text("Transactions: ", style = MaterialTheme.typography.labelMedium)
                            Text("${uiState.transactionCount}", style = MaterialTheme.typography.bodyMedium)
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Derivation path
                        Row {
                            Text("Derivation: ", style = MaterialTheme.typography.labelMedium)
                            Text(uiState.derivationPath, style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ))
                        }
                    }
                }


                // ─── Hardware Wallet Info ───
                // Only show if wallet was imported via a hardware wallet
                if (uiState.importedViaDevice != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Hardware Wallet",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            // Device name with connection method badge
                            val hwType = try {
                                HardwareWalletType.valueOf(uiState.importedViaDevice!!)
                            } catch (_: Exception) { null }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Device: ", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    hwType?.displayName ?: uiState.importedViaDevice!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (hwType != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            hwType.connectionMethod,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }

                            // Master fingerprint
                            uiState.masterFingerprint?.let { fp ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text("Master Fingerprint: ", style = MaterialTheme.typography.labelMedium)
                                    Text(fp,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace
                                        ))
                                }
                            }

                            // Derivation path (from stored origin, not derived)
                            uiState.storedDerivationPath?.let { path ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text("Origin Path: ", style = MaterialTheme.typography.labelMedium)
                                    Text("m/$path",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace
                                        ))
                                }
                            }

                            // Script type (derived from descriptor prefix)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row {
                                Text("Script Type: ", style = MaterialTheme.typography.labelMedium)
                                val scriptType = when {
                                    uiState.descriptor.startsWith("wpkh(") -> "Native SegWit (P2WPKH)"
                                    uiState.descriptor.startsWith("tr(") -> "Taproot (P2TR)"
                                    uiState.descriptor.startsWith("sh(wpkh(") -> "Nested SegWit (P2SH-P2WPKH)"
                                    uiState.descriptor.startsWith("pkh(") -> "Legacy (P2PKH)"
                                    uiState.descriptor.startsWith("wsh(") -> "SegWit Multisig (P2WSH)"
                                    else -> "Unknown"
                                }
                                Text(scriptType, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                // ─── Per-wallet Hardware Wallet Preference ───
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Hardware Wallet for Signing",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Choose the device this wallet should use when signing PSBTs. This is saved per wallet, which helps multisig setups use the right signer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val preferredType = uiState.preferredHardwareWallet?.let { device ->
                            runCatching { HardwareWalletType.valueOf(device) }.getOrNull()
                        }
                        ExposedDropdownMenuBox(
                            expanded = showHardwareWalletMenu,
                            onExpandedChange = { showHardwareWalletMenu = !showHardwareWalletMenu }
                        ) {
                            OutlinedTextField(
                                value = preferredType?.displayName ?: "None",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Preferred device") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showHardwareWalletMenu) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = showHardwareWalletMenu,
                                onDismissRequest = { showHardwareWalletMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None") },
                                    onClick = {
                                        viewModel.setPreferredHardwareWallet(null)
                                        showHardwareWalletMenu = false
                                    }
                                )
                                HardwareWalletType.entries.forEach { device ->
                                    DropdownMenuItem(
                                        text = { Text("${device.displayName} — ${device.connectionMethod}") },
                                        onClick = {
                                            viewModel.setPreferredHardwareWallet(device.name)
                                            showHardwareWalletMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ─── Labels ───
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Labels",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Import or export this wallet’s transaction labels using BIP-329 JSONL.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { labelImportLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Import Labels") }
                            Button(
                                onClick = { viewModel.exportLabels() },
                                modifier = Modifier.weight(1f)
                            ) { Text("Export Labels") }
                        }
                    }
                }

                // ─── Extended Public Key ───
                if (uiState.accountXpub.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Extended Public Key (${uiState.xpubLabel})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                if (expandedXpub) uiState.accountXpub
                                else uiState.accountXpub.take(8) + "…" + uiState.accountXpub.takeLast(6),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { expandedXpub = !expandedXpub }) {
                                    Text(if (expandedXpub) "Collapse" else "Expand")
                                }
                                TextButton(onClick = {
                                    viewModel.copyToClipboard(uiState.accountXpub, "Public Key")
                                }) { Text(if (uiState.copied) "Copied ✓" else "Copy") }
                                TextButton(onClick = { showQrDialog = true }) {
                                    Text("Show QR")
                                }
                            }
                        }
                    }
                }

                // ─── Fingerprint ───
                uiState.fingerprintBytes?.let { fpBytes ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            net.clench.wallet.ui.components.WalletFingerprint(
                                fingerprintBytes = fpBytes,
                                masterFingerprint = uiState.masterFingerprintBytes,
                                label = if (uiState.hasPassphrase)
                                    "Wallet fingerprint — verify this matches when restoring with your passphrase"
                                else
                                    "Wallet fingerprint — unique visual identifier for this wallet"
                            )
                        }
                    }
                }

                // ─── Signing Method / Seed Phrase Access ───
                if (uiState.isWatchOnly) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Signing Method",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "This watch-only wallet signs with its configured hardware wallet during Send. If you want to convert it to a hot wallet, add the matching seed phrase here instead of at signing time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { showSeedImportSheet = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !uiState.isConvertingToHot,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                if (uiState.isConvertingToHot) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                else Text("Add Seed Phrase")
                            }
                        }
                    }
                }

                // ─── Addresses ───
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("View Addresses", style = MaterialTheme.typography.titleSmall)
                        Button(onClick = onViewAddresses) { Text("View Addresses →") }
                    }
                }

                // ─── Backup & Export ───
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onBackup,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Backup & Export") }
                    OutlinedButton(
                        onClick = { viewModel.exportWalletDescriptorBackup() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (uiState.isMultisig) "Export Multisig Backup" else "Export Descriptor Backup")
                    }
                }

                // ─── View Seed Phrase — only for hot wallets ───
                if (!uiState.isWatchOnly) {
                    OutlinedButton(
                        onClick = onViewSeedPhrase,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("View Seed Phrase") }
                }

                // Error
                uiState.error?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSeedPhraseToWalletSheet(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (mnemonic: CharArray, passphrase: CharArray?) -> Unit
) {
    var seedInput by remember { mutableStateOf("") }
    var passphraseInput by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var seedError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Add Seed Phrase", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Text(
                    "Only enter the seed phrase if you intentionally want this watch-only wallet to become a hot wallet on this device. Clench will verify that the seed matches this wallet before saving it.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5D4037)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = seedInput,
                onValueChange = { seedInput = it; seedError = null },
                label = { Text("Seed phrase (12 or 24 words)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                placeholder = { Text("word1 word2 word3…") },
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = showPassphrase,
                    onCheckedChange = { showPassphrase = it },
                    enabled = !isLoading
                )
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
                    singleLine = true,
                    enabled = !isLoading
                )
            }

            seedError?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val words = seedInput.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    if (words.size != 12 && words.size != 24) {
                        seedError = "Enter 12 or 24 words"
                        return@Button
                    }
                    val mnemonic = seedInput.trim().toCharArray()
                    val passphrase = if (showPassphrase && passphraseInput.isNotBlank()) passphraseInput.toCharArray() else null
                    seedInput = ""
                    passphraseInput = ""
                    onConfirm(mnemonic, passphrase)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text("Verify & Add Seed Phrase")
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) { Text("Cancel") }
        }
    }
}
