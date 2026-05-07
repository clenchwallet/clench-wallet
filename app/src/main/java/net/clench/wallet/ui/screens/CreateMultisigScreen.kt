package net.clench.wallet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.domain.model.PhoneSigner
import net.clench.wallet.ui.components.HardwareWalletPickerSheet
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.CreateMultisigViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMultisigScreen(
    onWalletCreated: (walletId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: CreateMultisigViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    if (uiState.signers.any { it.isLocalKey }) {
        SecureWindowEffect()
    }
    var devicePickerTargetIndex by remember { mutableStateOf<Int?>(null) }
    var fileImportTargetIndex by remember { mutableStateOf<Int?>(null) }
    val signerFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val index = fileImportTargetIndex
        fileImportTargetIndex = null
        if (uri != null && index != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text.isNullOrBlank()) {
                    viewModel.setError("Selected signer export file was empty")
                } else {
                    viewModel.updateSigner(index, xpub = text.trim())
                }
            } catch (e: Exception) {
                viewModel.setError("Could not read signer export file: ${e.message}")
            }
        }
    }

    devicePickerTargetIndex?.let { targetIndex ->
        HardwareWalletPickerSheet(
            title = "Choose signer device",
            onDismiss = { devicePickerTargetIndex = null },
            onDeviceSelected = { device ->
                viewModel.setSignerDevice(targetIndex, device)
                devicePickerTargetIndex = null
            }
        )
    }

    // QR Scanner overlay
    if (uiState.showQrScanner) {
        QrScanner(
            onResult = { result -> viewModel.onQrScanned(result) },
            onCancel = { viewModel.hideQrScanner() },
            onError = { message -> viewModel.setError(message) }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (uiState.currentStep) {
                            1 -> "Multisig Configuration"
                            2 -> "Add Cosigner Keys"
                            3 -> "Review & Create"
                            else -> "Create Multisig"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.currentStep > 1) viewModel.previousStep()
                        else onBack()
                    }) {
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
        ) {
            // Step indicator
            LinearProgressIndicator(
                progress = { uiState.currentStep / 3f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                "Step ${uiState.currentStep} of 3",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Error display
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            uiState.warning?.let { warning ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(
                        warning,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Step content
            when (uiState.currentStep) {
                1 -> ConfigurationStep(
                    threshold = uiState.threshold,
                    totalSigners = uiState.totalSigners,
                    onSetThreshold = { viewModel.setThreshold(it) },
                    onSetTotalSigners = { viewModel.setTotalSigners(it) },
                    onPreset = { m, n -> viewModel.setPreset(m, n) },
                    onNext = {
                        if (viewModel.validateCurrentStep()) viewModel.nextStep()
                    },
                    modifier = Modifier.weight(1f)
                )
                2 -> SignersStep(
                    signers = uiState.signers,
                    onUpdateSigner = { index, label, xpub -> viewModel.updateSigner(index, label, xpub) },
                    onSetDevice = { index -> devicePickerTargetIndex = index },
                    onClearDevice = { index -> viewModel.setSignerDevice(index, null) },
                    onUsePhoneSigner = { index -> viewModel.generatePhoneSigner(index) },
                    onClearPhoneSigner = { index -> viewModel.clearPhoneSigner(index) },
                    onPhoneSignerBackupChanged = { index, backedUp -> viewModel.setPhoneSignerBackedUp(index, backedUp) },
                    onRemoveSigner = { viewModel.removeSigner(it) },
                    onScanQr = { viewModel.showQrScanner(it) },
                    onLoadFile = { index ->
                        fileImportTargetIndex = index
                        signerFileLauncher.launch(arrayOf("*/*"))
                    },
                    onNext = {
                        if (viewModel.validateCurrentStep()) viewModel.nextStep()
                    },
                    onBack = { viewModel.previousStep() },
                    showPhoneSignerOptions = uiState.showPhoneSignerOptions,
                    generatingPhoneSignerIndex = uiState.generatingPhoneSignerIndex,
                    modifier = Modifier.weight(1f)
                )
                3 -> ReviewStep(
                    walletName = uiState.walletName,
                    threshold = uiState.threshold,
                    totalSigners = uiState.totalSigners,
                    signers = uiState.signers,
                    descriptorPreview = viewModel.buildDescriptorPreview(),
                    isCreating = uiState.isCreating,
                    onSetWalletName = { viewModel.setWalletName(it) },
                    onCreate = { viewModel.createMultisigWallet(onWalletCreated) },
                    onBack = { viewModel.previousStep() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ConfigurationStep(
    threshold: Int,
    totalSigners: Int,
    onSetThreshold: (Int) -> Unit,
    onSetTotalSigners: (Int) -> Unit,
    onPreset: (Int, Int) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Choose your multisig configuration",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Quick presets
        Text(
            "Quick presets",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip("2-of-3", selected = threshold == 2 && totalSigners == 3) {
                onPreset(2, 3)
            }
            PresetChip("3-of-5", selected = threshold == 3 && totalSigners == 5) {
                onPreset(3, 5)
            }
            PresetChip("2-of-2", selected = threshold == 2 && totalSigners == 2) {
                onPreset(2, 2)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Total signers
        Text(
            "Total signers (N)",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = { onSetTotalSigners(totalSigners - 1) },
                enabled = totalSigners > 2
            ) { Text("−") }
            Text(
                "$totalSigners",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            FilledTonalButton(
                onClick = { onSetTotalSigners(totalSigners + 1) },
                enabled = totalSigners < 7
            ) { Text("+") }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Threshold
        Text(
            "Required signatures (M)",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = { onSetThreshold(threshold - 1) },
                enabled = threshold > 1
            ) { Text("−") }
            Text(
                "$threshold",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            FilledTonalButton(
                onClick = { onSetThreshold(threshold + 1) },
                enabled = threshold < totalSigners
            ) { Text("+") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                "$threshold-of-$totalSigners multisig",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Requires $threshold signature${if (threshold > 1) "s" else ""} out of $totalSigners cosigners to spend funds. " +
                "Uses BIP-48 derivation (P2WSH native segwit) for maximum compatibility.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Next: Add Signers") }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun SignersStep(
    signers: List<CreateMultisigViewModel.SignerInfo>,
    onUpdateSigner: (Int, String?, String?) -> Unit,
    onSetDevice: (Int) -> Unit,
    onClearDevice: (Int) -> Unit,
    onUsePhoneSigner: (Int) -> Unit,
    onClearPhoneSigner: (Int) -> Unit,
    onPhoneSignerBackupChanged: (Int, Boolean) -> Unit,
    onRemoveSigner: (Int) -> Unit,
    onScanQr: (Int) -> Unit,
    onLoadFile: (Int) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    showPhoneSignerOptions: Boolean,
    generatingPhoneSignerIndex: Int?,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(signers) { index, signer ->
                SignerCard(
                    index = index,
                    signer = signer,
                    onLabelChanged = { onUpdateSigner(index, it, null) },
                    onXpubChanged = { onUpdateSigner(index, null, it) },
                    onSetDevice = { onSetDevice(index) },
                    onClearDevice = { onClearDevice(index) },
                    onUsePhoneSigner = { onUsePhoneSigner(index) },
                    onClearPhoneSigner = { onClearPhoneSigner(index) },
                    onPhoneSignerBackupChanged = { backedUp -> onPhoneSignerBackupChanged(index, backedUp) },
                    onPaste = {
                        clipboardManager.getText()?.text?.let { text ->
                            onUpdateSigner(index, null, text.trim())
                        }
                    },
                    onScanQr = { onScanQr(index) },
                    onLoadFile = { onLoadFile(index) },
                    showPhoneSignerOptions = showPhoneSignerOptions,
                    isGeneratingPhoneSigner = generatingPhoneSignerIndex == index,
                    canRemove = signers.size > 2,
                    onRemove = { onRemoveSigner(index) }
                )
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) { Text("Back") }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f)
            ) { Text("Next: Review") }
        }
    }
}

@Composable
private fun SignerCard(
    index: Int,
    signer: CreateMultisigViewModel.SignerInfo,
    onLabelChanged: (String) -> Unit,
    onXpubChanged: (String) -> Unit,
    onSetDevice: () -> Unit,
    onClearDevice: () -> Unit,
    onUsePhoneSigner: () -> Unit,
    onClearPhoneSigner: () -> Unit,
    onPhoneSignerBackupChanged: (Boolean) -> Unit,
    onPaste: () -> Unit,
    onScanQr: () -> Unit,
    onLoadFile: () -> Unit,
    showPhoneSignerOptions: Boolean,
    isGeneratingPhoneSigner: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit
) {
    val device = signer.deviceType?.let { runCatching { HardwareWalletType.valueOf(it) }.getOrNull() }
    val canLoadFile = device == null || supportsMultisigSignerFile(device)
    val canScanQr = device?.supportsQr != false

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Signer ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (canRemove) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove signer",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Signer device",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        when {
                            signer.isLocalKey -> "${PhoneSigner.DISPLAY_NAME} — encrypted seed on this phone"
                            device != null -> "${device.displayName} — ${device.connectionMethod}"
                            else -> "Choose the hardware signer or coordinator export for this slot."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (signer.isLocalKey) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Advanced hot signer: this phone can sign as this cosigner. Keep the wallet threshold higher than the number of phone signers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (device?.isScreenlessSigner == true) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Screenless signer: verify policy and first receive address in another trusted coordinator before funding.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (signer.isLocalKey) {
                            TextButton(onClick = onClearPhoneSigner) { Text("Remove Phone Signer") }
                        } else {
                            TextButton(onClick = onSetDevice) {
                                Text(if (device == null) "Choose Device" else "Change Device")
                            }
                        }
                        if (device != null && !signer.isLocalKey) {
                            TextButton(onClick = onClearDevice) { Text("Clear") }
                        }
                    }
                    if (showPhoneSignerOptions && !signer.isLocalKey) {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = onUsePhoneSigner,
                            enabled = !isGeneratingPhoneSigner,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isGeneratingPhoneSigner) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Generating...")
                            } else {
                                Text("Use Clench Phone Signer")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Label
            OutlinedTextField(
                value = signer.label,
                onValueChange = onLabelChanged,
                label = { Text("Label") },
                placeholder = { Text("e.g. Coldcard, SeedSigner") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Xpub input
            OutlinedTextField(
                value = signer.xpub,
                onValueChange = onXpubChanged,
                label = { Text("Signer public key with origin") },
                placeholder = { Text("[fingerprint/48'/0'/0'/2']xpub...") },
                maxLines = 3,
                readOnly = signer.isLocalKey,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (!signer.isLocalKey) Row {
                        if (canLoadFile) {
                            IconButton(onClick = onLoadFile) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Load file")
                            }
                        }
                        IconButton(onClick = onPaste) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                        IconButton(onClick = onScanQr, enabled = canScanQr) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                        }
                    }
                }
            )

            Text(
                if (signer.isLocalKey) {
                    "Clench generated this cosigner key on-device from the seed phrase below."
                } else {
                    signerImportHint(device)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (signer.isLocalKey) {
                PhoneSignerBackupCard(
                    words = signer.phoneSignerSeedWords,
                    backedUp = signer.phoneSignerBackedUp,
                    onBackedUpChanged = onPhoneSignerBackupChanged
                )
            }

            // Show fingerprint and derivation path if available
            if (signer.fingerprint.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Fingerprint: ${signer.fingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                "Derivation: ${signer.derivationPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun supportsMultisigSignerFile(device: HardwareWalletType): Boolean {
    return device.connectionMethod.contains("File") ||
        device.connectionMethod.contains("SD") ||
        device.connectionMethod.contains("Virtual Disk")
}

@Composable
private fun PhoneSignerBackupCard(
    words: List<String>,
    backedUp: Boolean,
    onBackedUpChanged: (Boolean) -> Unit
) {
    if (words.isEmpty()) return
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Phone signer seed phrase",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Back up this seed separately. The wallet descriptor alone cannot recover this signer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    words.chunked(3).forEachIndexed { rowIndex, rowWords ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowWords.forEachIndexed { columnIndex, word ->
                                val number = rowIndex * 3 + columnIndex + 1
                                Text(
                                    "$number. $word",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            repeat(3 - rowWords.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = backedUp,
                    onCheckedChange = onBackedUpChanged
                )
                Text(
                    "I backed up this phone signer seed phrase",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun signerImportHint(device: HardwareWalletType?): String {
    return when (device) {
        null -> "Scan, paste, or load a signer export. Full multisig descriptors should be imported from Import Wallet instead."
        HardwareWalletType.COLDCARD_Q,
        HardwareWalletType.COLDCARD_MK4,
        HardwareWalletType.COLDCARD_MK5 -> "Use the Coldcard multisig/xpub export for this cosigner. Clench accepts origin-wrapped keys such as [fingerprint/path]xpub..."
        HardwareWalletType.SEEDSIGNER -> "Use SeedSigner multisig BIP48 export. Animated QR is preferred; paste is available as fallback."
        HardwareWalletType.KEYSTONE,
        HardwareWalletType.FOUNDATION_PASSPORT -> "Use the device's multisig account/export QR or file for this signer."
        HardwareWalletType.JADE -> "Use Jade's account xpub export for the BIP48 multisig path."
        HardwareWalletType.TAPSIGNER -> "Direct CVC-authenticated Tapsigner xpub export is not enabled yet; paste an origin-wrapped xpub from a trusted coordinator."
    }
}

@Composable
private fun ReviewStep(
    walletName: String,
    threshold: Int,
    totalSigners: Int,
    signers: List<CreateMultisigViewModel.SignerInfo>,
    descriptorPreview: String,
    isCreating: Boolean,
    onSetWalletName: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Wallet name
        OutlinedTextField(
            value = walletName,
            onValueChange = onSetWalletName,
            label = { Text("Wallet Name") },
            placeholder = { Text("$threshold-of-$totalSigners Multisig") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Summary
        Text("Configuration", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "$threshold-of-$totalSigners multisig",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "P2WSH (Native SegWit) · BIP-48",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Signers list
        Text("Cosigners", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        signers.forEachIndexed { index, signer ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        signer.label.ifBlank { "Signer ${index + 1}" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    signer.deviceType
                        ?.let { deviceName ->
                            runCatching { HardwareWalletType.valueOf(deviceName) }.getOrNull()?.displayName
                                ?: PhoneSigner.displayName(deviceName)
                        }
                        ?.let { displayName ->
                            Text(
                                "Device: $displayName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    if (signer.isLocalKey) {
                        Text(
                            "Phone signer seed backed up: ${if (signer.phoneSignerBackedUp) "yes" else "no"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (signer.phoneSignerBackedUp) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    if (signer.fingerprint.isNotEmpty()) {
                        Text(
                            "Fingerprint: ${signer.fingerprint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        signer.xpub.take(40) + "...",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Full descriptor preview
        Text("Descriptor", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        SelectionContainer {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    descriptorPreview,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Create button
        Button(
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isCreating
        ) {
            if (isCreating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Creating...")
            } else {
                Text("Create Wallet")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Back") }
    }
}
