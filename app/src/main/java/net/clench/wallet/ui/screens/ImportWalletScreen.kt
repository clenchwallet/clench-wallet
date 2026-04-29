package net.clench.wallet.ui.screens

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.components.ColdcardNfcPayload
import net.clench.wallet.ui.components.HardwareWalletPickerSheet
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.components.WalletFingerprint
import net.clench.wallet.ui.components.hasCameraAvailable
import net.clench.wallet.ui.viewmodel.ImportWalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    onWalletImported: (String) -> Unit,
    onBack: () -> Unit,
    onSettings: (() -> Unit)? = null,
    hardwareWalletMode: Boolean = false,
    viewModel: ImportWalletViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var showScanner by remember { mutableStateOf(false) }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }

    // Check camera/NFC availability once
    val hasCamera = remember { hasCameraAvailable(context) }
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    var nfcReaderActive by remember { mutableStateOf(false) }
    var nfcStatus by remember { mutableStateOf<String?>(null) }
    var nfcError by remember { mutableStateOf<String?>(null) }

    val hardwareFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() }
                if (text.isNullOrBlank()) {
                    nfcError = "Selected file was empty"
                } else {
                    viewModel.setInput(text.trim())
                    nfcStatus = "Loaded hardware wallet export file"
                    nfcError = null
                }
            } catch (e: Exception) {
                nfcError = "Could not read file: ${e.message}"
            }
        }
    }

    // HW wallet mode state
    var selectedDevice by remember { mutableStateOf<HardwareWalletType?>(null) }
    var showDevicePicker by remember { mutableStateOf(hardwareWalletMode) }

    val isSeedPhrase = uiState.detectedType == ImportWalletViewModel.DetectedType.SEED_12 ||
            uiState.detectedType == ImportWalletViewModel.DetectedType.SEED_24

    // Snackbar host for camera error messages
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when camera error occurs
    LaunchedEffect(cameraErrorMessage) {
        cameraErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            cameraErrorMessage = null
        }
    }

    // Hardware wallet onboarding is choice-based: select file, scan QR, or NFC explicitly.
    DisposableEffect(nfcReaderActive, selectedDevice) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if (!nfcReaderActive || hostActivity == null || adapter == null || !adapter.isEnabled) {
            onDispose { }
        } else {
            val flags = NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
            adapter.enableReaderMode(
                hostActivity,
                { tag ->
                    try {
                        val ndef = Ndef.get(tag) ?: error("NFC tag does not expose an NDEF message")
                        ndef.connect()
                        val message = try {
                            ndef.ndefMessage ?: ndef.cachedNdefMessage
                        } finally {
                            ndef.close()
                        } ?: error("No NDEF payload found on NFC tag")
                        val payload = ColdcardNfcPayload.extractTextPayload(message)
                            ?: error("NFC payload did not contain an xpub, descriptor, or readable text export")
                        hostActivity.runOnUiThread {
                            viewModel.setInput(payload.trim())
                            nfcStatus = "Loaded hardware wallet data from NFC"
                            nfcError = null
                            nfcReaderActive = false
                        }
                    } catch (e: Exception) {
                        hostActivity.runOnUiThread {
                            nfcError = e.message ?: "NFC import failed"
                            nfcStatus = null
                        }
                    }
                },
                flags,
                null
            )
            onDispose { adapter.disableReaderMode(hostActivity) }
        }
    }

    // Device picker bottom sheet
    if (showDevicePicker) {
        HardwareWalletPickerSheet(
            onDismiss = {
                showDevicePicker = false
                if (selectedDevice == null) onBack()
            },
            onDeviceSelected = { device ->
                selectedDevice = device
                showDevicePicker = false
                // Store selected device in viewModel so it gets passed to importWatchOnly
                viewModel.setHardwareDeviceType(device.name)
            }
        )
    }

    val screenTitle = if (hardwareWalletMode && selectedDevice != null) {
        "Connect ${selectedDevice!!.displayName}"
    } else if (hardwareWalletMode) {
        "Connect Hardware Wallet"
    } else {
        "Import Wallet"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onSettings != null) {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
                .imePadding()
        ) {
            // HW wallet mode: device-specific instructions
            if (hardwareWalletMode && selectedDevice != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Export your public key from ${selectedDevice!!.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            getDeviceInstructions(selectedDevice!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // No camera warning — shown in HW wallet mode when camera unavailable
                if (!hasCamera) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3E0)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "📷 No camera detected. Paste your xpub or descriptor below.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF5D4037)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Change device link
                TextButton(onClick = { showDevicePicker = true }) {
                    Text("Change device")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Wallet name
            OutlinedTextField(
                value = uiState.walletName,
                onValueChange = { viewModel.setWalletName(it) },
                label = { Text("Wallet name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // In HW wallet mode, hide the seed phrase label and show xpub-focused label
            if (hardwareWalletMode) {
                Text(
                    "Choose how to import from ${selectedDevice?.displayName ?: "your hardware wallet"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { hardwareFileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Load File") }
                    Button(
                        onClick = { showScanner = true },
                        enabled = hasCamera,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan")
                    }
                    OutlinedButton(
                        onClick = {
                            when {
                                nfcAdapter == null -> nfcError = "This phone does not report NFC hardware"
                                !nfcAdapter.isEnabled -> nfcError = "NFC is off in Android settings"
                                activity == null -> nfcError = "NFC reader is unavailable in this view"
                                else -> {
                                    nfcError = null
                                    nfcStatus = "Ready for NFC. Hold ${selectedDevice?.displayName ?: "the device"} against the phone."
                                    nfcReaderActive = true
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("NFC") }
                }
                if (!hasCamera) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Camera unavailable — use file, NFC, or paste manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (nfcReaderActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            nfcReaderActive = false
                            nfcStatus = null
                            nfcError = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cancel NFC") }
                }
                nfcStatus?.let { status ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                nfcError?.let { error ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = { viewModel.setInput(it) },
                    label = { Text("xpub / zpub / descriptor") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("zpub… or xpub… or wpkh(…)") }
                )
            } else {
                // Standard import: single unified input field
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = { viewModel.setInput(it) },
                    label = { Text("Enter seed phrase, xpub, zpub, or descriptor") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("word1 word2 word3 … or zpub… or wpkh(…)") },
                    trailingIcon = {
                        if (hasCamera) {
                            IconButton(onClick = { showScanner = true }) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = "Scan QR code"
                                )
                            }
                        }
                    }
                )
            }

            // Detection status line
            if (uiState.detectedLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    uiState.detectedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            // Collapsible passphrase section — only for seed phrases, hidden in HW wallet mode
            if (isSeedPhrase && !hardwareWalletMode) {
                Spacer(modifier = Modifier.height(12.dp))

                var passphraseExpanded by remember { mutableStateOf(false) }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        passphraseExpanded = !passphraseExpanded
                        if (!passphraseExpanded) return@OutlinedCard
                        coroutineScope.launch {
                            delay(300)
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Advanced: Add passphrase (optional)",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (passphraseExpanded) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                }

                AnimatedVisibility(visible = passphraseExpanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        // [H-4] Strengthened passphrase warning for import screen
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFEBEE)  // Red tint — more alarming than orange
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "⚠️  WARNING: Passphrase wallets cannot be recovered.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB71C1C)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "• Any passphrase opens a valid wallet — there is NO wrong passphrase error by design.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF5D4037)
                                )
                                Text(
                                    "• Your funds can ONLY be accessed with the exact same passphrase.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF5D4037)
                                )
                                Text(
                                    "• If you forget the passphrase, ALL FUNDS ARE PERMANENTLY LOST.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB71C1C)
                                )
                                Text(
                                    "• Clench NEVER stores your passphrase for you.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF5D4037)
                                )
                                Text(
                                    "• This is NOT a password reset feature — a different passphrase creates a different wallet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF5D4037)
                                )
                                Text(
                                    "• Check the fingerprint and identicon below — they must match EVERY time you unlock.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF5D4037)
                                )
                                Text(
                                    "• Store the passphrase separately from the seed phrase.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF5D4037)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        var importPassphraseVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = uiState.passphrase,
                            onValueChange = { if (it.length <= 512) viewModel.setPassphrase(it) },
                            label = { Text("Passphrase") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        coroutineScope.launch {
                                            delay(400)
                                            scrollState.animateScrollTo(scrollState.maxValue)
                                        }
                                    }
                                },
                            singleLine = true,
                            visualTransformation = if (importPassphraseVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                androidx.compose.material3.IconButton(onClick = { importPassphraseVisible = !importPassphraseVisible }) {
                                    androidx.compose.material3.Text(if (importPassphraseVisible) "Hide" else "Show", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                                }
                            }
                        )

                        // Fingerprint — shown when valid seed is entered
                        uiState.fingerprintBytes?.let { fpBytes ->
                            Spacer(modifier = Modifier.height(16.dp))
                            WalletFingerprint(
                                fingerprintBytes = fpBytes,
                                masterFingerprint = uiState.masterFingerprintBytes,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // [H-4] Confirmation dialog for passphrase wallet imports
            var showPassphraseConfirmDialog by remember { mutableStateOf(false) }

            if (showPassphraseConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showPassphraseConfirmDialog = false },
                    title = { Text("Import Passphrase Wallet?") },
                    text = {
                        Column {
                            Text("You are about to import a wallet with a passphrase.")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "⚠️  This wallet cannot be recovered without the passphrase.",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Make sure you:")
                            Text("• Understand Clench will NOT store this passphrase")
                            Text("• Have securely stored the passphrase separately")
                            Text("• Know this is NOT a password reset flow")
                            Text("• Understand a different passphrase opens a different wallet")
                            Text("• Understand there is NO passphrase recovery")
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showPassphraseConfirmDialog = false
                                viewModel.importWallet(onWalletImported)
                            }
                        ) {
                            Text("I Understand — Import")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPassphraseConfirmDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            Button(
                onClick = {
                    // [H-4] Require confirmation before importing passphrase wallets
                    if (isSeedPhrase && uiState.passphrase.isNotBlank() && !hardwareWalletMode) {
                        showPassphraseConfirmDialog = true
                    } else {
                        viewModel.importWallet(onWalletImported)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading
            ) {
                if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text(if (hardwareWalletMode) "Connect Wallet" else "Import Wallet")
            }

            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }

        // QR Scanner overlay
        AnimatedVisibility(visible = showScanner) {
            QrScanner(
                onResult = { result ->
                    viewModel.setInput(result)
                    showScanner = false
                },
                onCancel = { showScanner = false },
                onError = { errorMsg ->
                    cameraErrorMessage = errorMsg
                }
            )
        }
    }
}

/**
 * Device-specific instructions for exporting the public key via QR.
 */
private fun getDeviceInstructions(device: HardwareWalletType): String {
    return when (device) {
        HardwareWalletType.COLDCARD_Q -> "On your Coldcard Q, export the public key using whichever method is easiest: QR code, a Generic JSON file, or NFC if your firmware exposes the account export over NFC. Then choose Load File, Scan, or NFC below."
        HardwareWalletType.COLDCARD_MK4 -> "On your Coldcard Mk4:\nAdvanced/Tools → Export Wallet → Generic JSON\n\nSave to SD card, then paste the xpub/zpub from the file."
        HardwareWalletType.COLDCARD_MK5 -> "On your Coldcard Mk5:\nAdvanced/Tools → Export Wallet → Generic JSON\n\nSave to SD card or virtual disk, then paste the xpub/zpub from the file."
        HardwareWalletType.SEEDSIGNER -> "On your SeedSigner:\nExport Xpub → Select wallet format (Native SegWit recommended)\n\nScan the animated QR code displayed on screen."
        HardwareWalletType.KEYSTONE -> "On your Keystone:\nMenu (☰) → Multisig Wallet or watch-only setup\n\nThe device will display a QR code with your extended public key."
        HardwareWalletType.FOUNDATION_PASSPORT -> "On your Passport:\nManage Account → Connect Wallet → QR Code\n\nScan the animated QR code displayed on screen."
        HardwareWalletType.JADE -> "On your Jade:\nOptions → Xpub Export\n\nScan the QR code displayed on screen."
    }
}
