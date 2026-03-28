package net.clench.wallet.ui.screens

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

    // Check camera availability once
    val hasCamera = remember { hasCameraAvailable(context) }

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

    // In HW wallet mode, auto-launch scanner after device selection — only if camera available
    LaunchedEffect(selectedDevice) {
        if (hardwareWalletMode && selectedDevice != null && uiState.input.isBlank()) {
            if (hasCamera) {
                delay(300) // Brief pause so user sees the instructions
                showScanner = true
            }
            // If no camera, we just stay on the manual input screen (with the note shown below)
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
                            "📷 No camera detected. Paste your key from your hardware wallet's export screen.",
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
                OutlinedTextField(
                    value = uiState.input,
                    onValueChange = { viewModel.setInput(it) },
                    label = { Text("Scan or paste xpub / zpub / descriptor") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("zpub… or xpub… or wpkh(…)") },
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

                Spacer(modifier = Modifier.height(8.dp))

                // Prominent scan button for HW wallet mode — only show if camera available
                if (hasCamera) {
                    Button(
                        onClick = { showScanner = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan QR Code from ${selectedDevice?.displayName ?: "Device"}")
                    }
                }
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
                        // Warning about passphrase for import
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFF3E0)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Any passphrase opens a valid wallet — there is no 'wrong passphrase' error by design. " +
                                "Check the fingerprint and identicon below match what you see every time you unlock.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF5D4037)
                            )
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

            Button(
                onClick = { viewModel.importWallet(onWalletImported) },
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
        HardwareWalletType.COLDCARD_Q -> "On your Coldcard Q:\nSettings → Wallet → Export → QR Code\n\nThis will display a QR code containing your wallet's extended public key (xpub/zpub)."
        HardwareWalletType.COLDCARD_MK4 -> "On your Coldcard Mk4:\nAdvanced/Tools → Export Wallet → Generic JSON\n\nSave to SD card, then paste the xpub/zpub from the file."
        HardwareWalletType.SEEDSIGNER -> "On your SeedSigner:\nExport Xpub → Select wallet format (Native SegWit recommended)\n\nScan the animated QR code displayed on screen."
        HardwareWalletType.KEYSTONE -> "On your Keystone:\nMenu (☰) → Multisig Wallet or watch-only setup\n\nThe device will display a QR code with your extended public key."
        HardwareWalletType.FOUNDATION_PASSPORT -> "On your Passport:\nManage Account → Connect Wallet → QR Code\n\nScan the animated QR code displayed on screen."
        HardwareWalletType.JADE -> "On your Jade:\nOptions → Xpub Export\n\nScan the QR code displayed on screen."
    }
}
