package net.clench.wallet.ui.screens

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.data.local.entity.SavedSignerEntity
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.domain.model.SignerAccountKeyParser
import net.clench.wallet.ui.components.HardwareWalletPickerSheet
import net.clench.wallet.ui.components.NfcDispatch
import net.clench.wallet.ui.components.NfcReaderModeFlags
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.components.TapsignerNfcReader
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.SignerVaultViewModel
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignerVaultScreen(
    onBack: () -> Unit,
    onWalletCreated: (String) -> Unit,
    onCreateMultisig: () -> Unit,
    viewModel: SignerVaultViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val clipboardManager = LocalClipboardManager.current
    var showScanner by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var nfcReaderActive by remember { mutableStateOf(false) }
    var nfcStatus by remember { mutableStateOf<String?>(null) }
    var nfcError by remember { mutableStateOf<String?>(null) }
    var tapsignerPinInput by remember { mutableStateOf("") }
    var pendingTapsignerPin by remember { mutableStateOf<CharArray?>(null) }
    var showTapsignerMultisigConfirm by remember { mutableStateOf(false) }
    var showAddSigner by remember { mutableStateOf(false) }
    val nfcProcessing = remember { AtomicBoolean(false) }
    val selectedDevice = uiState.deviceType
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { HardwareWalletType.valueOf(it) }.getOrNull() }

    SecureWindowEffect(enabled = tapsignerPinInput.isNotBlank())

    fun clearPendingTapsignerPin() {
        pendingTapsignerPin?.fill('0')
        pendingTapsignerPin = null
    }

    fun stopNfcReader(clearPin: Boolean = false) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if (hostActivity != null && adapter != null) {
            adapter.disableReaderMode(hostActivity)
            NfcDispatch.disableForegroundDispatch(hostActivity, adapter)
        }
        nfcReaderActive = false
        clearPendingTapsignerPin()
        if (clearPin) tapsignerPinInput = ""
    }

    LaunchedEffect(uiState.message) {
        if (showAddSigner && uiState.message?.startsWith("Saved ") == true) {
            stopNfcReader(clearPin = true)
            showAddSigner = false
        }
    }

    fun processTapsignerTag(tag: Tag, hostActivity: Activity, cvc: CharArray?) {
        if (!nfcProcessing.compareAndSet(false, true)) return
        try {
            val result = if (cvc == null) {
                val status = TapsignerNfcReader.readStatus(tag)
                hostActivity.runOnUiThread {
                    nfcStatus = status.summary()
                    nfcError = null
                    stopNfcReader()
                }
                return
            } else if (uiState.scriptType == SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT) {
                TapsignerNfcReader.readAccountXpub(tag, cvc)
            } else {
                TapsignerNfcReader.readMultisigAccountXpub(
                    tag = tag,
                    cvc = cvc,
                    isTestnet = uiState.isTestnet,
                    setPathIfNeeded = true,
                    initializeIfNeeded = true
                )
            }
            hostActivity.runOnUiThread {
                viewModel.importSignerText(
                    value = result.originWrappedXpub,
                    label = "TAPSIGNER",
                    deviceType = HardwareWalletType.TAPSIGNER.name
                )
                nfcStatus = result.summary
                nfcError = null
                stopNfcReader(clearPin = true)
            }
        } catch (t: Throwable) {
            hostActivity.runOnUiThread {
                nfcError = t.message ?: "TAPSIGNER NFC import failed"
                nfcStatus = null
                stopNfcReader()
            }
        } finally {
            nfcProcessing.set(false)
        }
    }

    fun startTapsignerNfcReader(cvc: CharArray?) {
        val hostActivity = activity
        val adapter = nfcAdapter
        when {
            hostActivity == null -> {
                nfcError = "NFC reader is unavailable in this view"
                return
            }
            adapter == null -> {
                nfcError = "This phone does not report NFC hardware"
                return
            }
            !adapter.isEnabled -> {
                nfcError = "NFC is off in Android settings"
                return
            }
            cvc != null && cvc.size !in 6..32 -> {
                cvc.fill('0')
                nfcError = "Enter the TAPSIGNER PIN"
                return
            }
        }
        stopNfcReader()
        pendingTapsignerPin = cvc
        nfcReaderActive = true
        nfcError = null
        nfcStatus = if (cvc == null) {
            "Ready to read TAPSIGNER status. Hold the card against the phone."
        } else {
            "Ready to import TAPSIGNER signer key. Hold the card against the phone."
        }
        NfcDispatch.enableCoinkiteForegroundDispatch(hostActivity, adapter)
        adapter.enableReaderMode(
            hostActivity,
            { tag -> processTapsignerTag(tag, hostActivity, pendingTapsignerPin) },
            NfcReaderModeFlags.coinkiteTap,
            null
        )
    }

    DisposableEffect(activity, nfcAdapter) {
        onDispose { stopNfcReader() }
    }

    val signerFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() }
                if (text.isNullOrBlank()) {
                    nfcError = "Selected signer export file was empty"
                } else {
                    viewModel.importSignerText(text.trim(), deviceType = uiState.deviceType.ifBlank { null })
                    nfcStatus = "Loaded signer export file"
                    nfcError = null
                }
            } catch (e: Exception) {
                nfcError = "Could not read signer export file: ${e.message}"
            }
        }
    }

    if (showDevicePicker) {
        HardwareWalletPickerSheet(
            title = "Choose signer device",
            onDismiss = { showDevicePicker = false },
            onDeviceSelected = { device ->
                viewModel.setDeviceType(device.name)
                if (uiState.label.isBlank()) viewModel.setLabel(device.displayName)
                showDevicePicker = false
            }
        )
    }

    if (showScanner) {
        QrScanner(
            onResult = { result ->
                viewModel.importSignerText(result)
                showScanner = false
            },
            onCancel = { showScanner = false },
            onError = { message -> nfcError = message }
        )
        return
    }

    if (showTapsignerMultisigConfirm) {
        AlertDialog(
            onDismissRequest = { showTapsignerMultisigConfirm = false },
            title = { Text("Import TAPSIGNER for multisig?") },
            text = {
                Text(
                    "Clench will authenticate with the TAPSIGNER PIN, then initialize or derive the multisig account key at ${uiState.derivationPath} if needed. Make sure the encrypted TAPSIGNER backup has been saved before funding."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTapsignerMultisigConfirm = false
                        startTapsignerNfcReader(tapsignerPinInput.toCharArray())
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTapsignerMultisigConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showAddSigner) "Add Signer" else "Signers") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (showAddSigner) {
                                stopNfcReader(clearPin = true)
                                showAddSigner = false
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!showAddSigner) {
                androidx.compose.material3.FloatingActionButton(onClick = { showAddSigner = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Signer")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showAddSigner) {
                item {
                    AddSignerCard(
                        uiState = uiState,
                        selectedDevice = selectedDevice,
                        tapsignerPinInput = tapsignerPinInput,
                        nfcReaderActive = nfcReaderActive,
                        nfcStatus = nfcStatus,
                        nfcError = nfcError,
                        onScriptTypeChanged = viewModel::setScriptType,
                        onLabelChanged = viewModel::setLabel,
                        onPublicKeyChanged = viewModel::setPublicKey,
                        onFingerprintChanged = viewModel::setFingerprint,
                        onDerivationPathChanged = viewModel::setDerivationPath,
                        onChooseDevice = { showDevicePicker = true },
                        onClearDevice = { viewModel.setDeviceType("") },
                        onScanQr = { showScanner = true },
                        onLoadFile = { signerFileLauncher.launch(arrayOf("text/*", "application/json", "application/octet-stream", "*/*")) },
                        onPaste = {
                            val text = clipboardManager.getText()?.text?.trim().orEmpty()
                            if (text.isBlank()) {
                                viewModel.setError("Clipboard is empty")
                            } else {
                                viewModel.importSignerText(text)
                            }
                        },
                        onTapsignerPinChanged = { tapsignerPinInput = it },
                        onReadTapsignerStatus = { startTapsignerNfcReader(null) },
                        onImportTapsigner = {
                            if (uiState.scriptType == SignerAccountKeyParser.SCRIPT_MULTISIG_NATIVE_SEGWIT) {
                                showTapsignerMultisigConfirm = true
                            } else {
                                startTapsignerNfcReader(tapsignerPinInput.toCharArray())
                            }
                        },
                        onCancelNfc = { stopNfcReader() },
                        onSave = { viewModel.saveManualSigner() }
                    )
                }
            } else {
                item {
                    Text("Saved Signers", fontWeight = FontWeight.Bold)
                }

                if (uiState.isLoading) {
                    item { CircularProgressIndicator() }
                } else if (uiState.savedSigners.isEmpty()) {
                    item {
                        Text(
                            "No saved signers yet. Tap + to add one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(uiState.savedSigners, key = { it.id }) { signer ->
                        SavedSignerCard(
                            signer = signer,
                            isCreatingWallet = uiState.isCreatingWallet,
                            onCreateWallet = { viewModel.createSingleSigWatchOnlyWallet(signer.id, onWalletCreated) },
                            onUseInMultisig = onCreateMultisig,
                            onDelete = { viewModel.deleteSigner(signer.id) }
                        )
                    }
                }
            }

            uiState.error?.let { error ->
                item { ErrorCard(error = error, onDismiss = viewModel::clearError) }
            }

            uiState.message?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSignerCard(
    uiState: SignerVaultViewModel.UiState,
    selectedDevice: HardwareWalletType?,
    tapsignerPinInput: String,
    nfcReaderActive: Boolean,
    nfcStatus: String?,
    nfcError: String?,
    onScriptTypeChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onPublicKeyChanged: (String) -> Unit,
    onFingerprintChanged: (String) -> Unit,
    onDerivationPathChanged: (String) -> Unit,
    onChooseDevice: () -> Unit,
    onClearDevice: () -> Unit,
    onScanQr: () -> Unit,
    onLoadFile: () -> Unit,
    onPaste: () -> Unit,
    onTapsignerPinChanged: (String) -> Unit,
    onReadTapsignerStatus: () -> Unit,
    onImportTapsigner: () -> Unit,
    onCancelNfc: () -> Unit,
    onSave: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Add Signer", fontWeight = FontWeight.Bold)
            Text(
                "Store reusable public signer account keys, then create single-sig watch-only wallets or build multisig wallets from saved signers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.scriptType == SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT,
                    onClick = { onScriptTypeChanged(SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT) },
                    label = { Text("Single-sig") }
                )
                FilterChip(
                    selected = uiState.scriptType == SignerAccountKeyParser.SCRIPT_MULTISIG_NATIVE_SEGWIT,
                    onClick = { onScriptTypeChanged(SignerAccountKeyParser.SCRIPT_MULTISIG_NATIVE_SEGWIT) },
                    label = { Text("Multisig") }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChooseDevice, modifier = Modifier.weight(1f)) {
                    Text(selectedDevice?.displayName ?: "Choose Device")
                }
                if (selectedDevice != null) {
                    TextButton(onClick = onClearDevice) { Text("Clear") }
                }
            }
            if (selectedDevice == HardwareWalletType.TAPSIGNER) {
                TapsignerSignerControls(
                    scriptType = uiState.scriptType,
                    expectedPath = uiState.derivationPath,
                    pinInput = tapsignerPinInput,
                    readerActive = nfcReaderActive,
                    nfcStatus = nfcStatus,
                    nfcError = nfcError,
                    onPinChanged = onTapsignerPinChanged,
                    onReadStatus = onReadTapsignerStatus,
                    onImport = onImportTapsigner,
                    onCancel = onCancelNfc
                )
            }
            OutlinedTextField(
                value = uiState.label,
                onValueChange = onLabelChanged,
                label = { Text("Label") },
                placeholder = { Text("e.g. TAPSIGNER, Coldcard, recovery key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.publicKey,
                onValueChange = onPublicKeyChanged,
                label = { Text("Public account key") },
                placeholder = { Text(accountKeyPlaceholder(uiState.scriptType)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Row {
                        IconButton(onClick = onLoadFile) {
                            Icon(Icons.Default.UploadFile, contentDescription = "Load file")
                        }
                        IconButton(onClick = onPaste) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                        IconButton(onClick = onScanQr) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                        }
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = uiState.fingerprint,
                    onValueChange = onFingerprintChanged,
                    label = { Text("Fingerprint") },
                    placeholder = { Text("8 hex") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = uiState.derivationPath,
                    onValueChange = onDerivationPathChanged,
                    label = { Text("Derivation path") },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("Save Signer")
            }
        }
    }
}

@Composable
private fun TapsignerSignerControls(
    scriptType: String,
    expectedPath: String,
    pinInput: String,
    readerActive: Boolean,
    nfcStatus: String?,
    nfcError: String?,
    onPinChanged: (String) -> Unit,
    onReadStatus: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("TAPSIGNER NFC", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(
                "This will import the ${if (scriptType == SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT) "single-sig" else "multisig"} account key at $expectedPath.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = pinInput,
                onValueChange = onPinChanged,
                label = { Text("TAPSIGNER PIN") },
                supportingText = { Text("If unchanged, use the Starting PIN Code printed on the card.") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReadStatus,
                    enabled = !readerActive,
                    modifier = Modifier.weight(1f)
                ) { Text("Read Status") }
                Button(
                    onClick = onImport,
                    enabled = !readerActive && pinInput.length in 6..32,
                    modifier = Modifier.weight(1f)
                ) { Text("Import") }
            }
            if (readerActive) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel NFC")
                }
            }
            nfcStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            nfcError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ErrorCard(error: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun SavedSignerCard(
    signer: SavedSignerEntity,
    isCreatingWallet: Boolean,
    onCreateWallet: () -> Unit,
    onUseInMultisig: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(signer.label, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            Text(
                "${SignerAccountKeyParser.displayNameForScript(signer.scriptType)} • ${signer.network} • ${signer.derivationPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Device: ${deviceDisplayName(signer.deviceType)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            signer.fingerprint?.let {
                Text(
                    "Fingerprint: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                signer.xpub.take(56) + if (signer.xpub.length > 56) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signer.scriptType == SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT) {
                    OutlinedButton(
                        onClick = onCreateWallet,
                        enabled = !isCreatingWallet,
                        modifier = Modifier.weight(1f)
                    ) { Text("Create Wallet") }
                } else {
                    OutlinedButton(onClick = onUseInMultisig, modifier = Modifier.weight(1f)) {
                        Text("Use in Multisig")
                    }
                }
            }
        }
    }
}

private fun accountKeyPlaceholder(scriptType: String): String {
    return if (scriptType == SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT) {
        "[fingerprint/84'/0'/0']xpub..."
    } else {
        "[fingerprint/48'/0'/0'/2']xpub..."
    }
}

private fun deviceDisplayName(deviceType: String?): String {
    if (deviceType.isNullOrBlank()) return "manual"
    return runCatching { HardwareWalletType.valueOf(deviceType).displayName }.getOrDefault(deviceType)
}
