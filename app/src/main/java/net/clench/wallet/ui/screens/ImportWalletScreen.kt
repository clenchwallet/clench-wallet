package net.clench.wallet.ui.screens

import android.app.Activity
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.MainActivity
import net.clench.wallet.ui.components.ColdcardNfcPayload
import net.clench.wallet.ui.components.HardwareWalletPickerSheet
import net.clench.wallet.ui.components.NfcDispatch
import net.clench.wallet.ui.components.NfcReaderModeFlags
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.components.SecureBip39WordEntry
import net.clench.wallet.ui.components.TapsignerNfcReader
import net.clench.wallet.ui.components.WalletFingerprint
import net.clench.wallet.ui.components.hasCameraAvailable
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.ImportWalletViewModel
import net.clench.wallet.security.InputLimits
import net.clench.wallet.security.readTextBounded
import net.clench.wallet.ui.picker.LocalPickerRoundTripHost
import net.clench.wallet.ui.picker.PickerDestination
import net.clench.wallet.ui.picker.PickerPurpose
import net.clench.wallet.ui.picker.PickerRequest

private enum class ImportEntryMode { SeedPhrase, PublicOrDescriptor }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    onWalletImported: (String) -> Unit,
    onBack: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onCreateMultisig: (() -> Unit)? = null,
    onConnectHardwareWallet: (() -> Unit)? = null,
    hardwareWalletMode: Boolean = false,
    viewModel: ImportWalletViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var showScanner by remember { mutableStateOf(false) }
    var cameraErrorMessage by remember { mutableStateOf<String?>(null) }
    var entryMode by remember(hardwareWalletMode) {
        mutableStateOf(if (hardwareWalletMode) ImportEntryMode.PublicOrDescriptor else ImportEntryMode.SeedPhrase)
    }
    var secureSeedWords by remember { mutableStateOf(emptyList<String>()) }
    var expectedSeedWordCount by remember { mutableIntStateOf(12) }

    // Check camera/NFC availability once
    val hasCamera = remember { hasCameraAvailable(context) }
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    var nfcReaderActive by remember { mutableStateOf(false) }
    var nfcStatus by remember { mutableStateOf<String?>(null) }
    var nfcError by remember { mutableStateOf<String?>(null) }
    var suppressPassiveTapsignerStatusUntil by remember { mutableStateOf(0L) }
    var tapsignerInitializeAvailable by remember { mutableStateOf(false) }
    var showTapsignerInitializeConfirm by remember { mutableStateOf(false) }
    var tapsignerBackupAvailable by remember { mutableStateOf(false) }
    var tapsignerBackupCount by remember { mutableStateOf<Long?>(null) }
    var tapsignerBackupFingerprint by remember { mutableStateOf<String?>(null) }
    var showTapsignerBackupConfirm by remember { mutableStateOf(false) }
    var tapsignerCvcInput by remember { mutableStateOf("") }
    var pendingTapsignerCvc by remember { mutableStateOf<CharArray?>(null) }
    var pendingTapsignerInitialize by remember { mutableStateOf(false) }
    var pendingTapsignerBackup by remember { mutableStateOf(false) }
    var pendingTapsignerBackupUri by remember { mutableStateOf<Uri?>(null) }
    val nfcProcessing = remember { AtomicBoolean(false) }
    val pickerHost = LocalPickerRoundTripHost.current
    val pickerResume by pickerHost.pickerResume.collectAsState()
    val pickerDestination = remember(hardwareWalletMode) {
        PickerDestination.WalletImport(hardwareWalletMode)
    }

    // HW wallet mode state
    var selectedDevice by remember { mutableStateOf<HardwareWalletType?>(null) }
    var showDevicePicker by remember { mutableStateOf(hardwareWalletMode) }

    LaunchedEffect(pickerResume?.requestId) {
        when (pickerResume?.purpose) {
            PickerPurpose.WALLET_SETUP_IMPORT -> {
                val result = pickerHost.consumePickerResult(
                    PickerPurpose.WALLET_SETUP_IMPORT,
                    pickerDestination
                )
                result?.uri?.let { uriString ->
                    try {
                        val text = context.contentResolver.openInputStream(Uri.parse(uriString))
                            ?.bufferedReader()?.use { reader ->
                                reader.readTextBounded(InputLimits.SECRET_TEXT_CHARS)
                            }
                        if (text.isNullOrBlank()) {
                            nfcError = "Selected file was empty"
                        } else {
                            viewModel.setInput(text.trim())
                            nfcStatus = if (hardwareWalletMode) {
                                "Loaded hardware wallet export file"
                            } else {
                                "Loaded wallet setup file"
                            }
                            nfcError = null
                        }
                    } catch (e: Exception) {
                        nfcError = "Could not read file: ${e.message}"
                    }
                }
            }
            PickerPurpose.TAPSIGNER_SETUP_BACKUP -> {
                val result = pickerHost.consumePickerResult(
                    PickerPurpose.TAPSIGNER_SETUP_BACKUP,
                    pickerDestination
                )
                if (result != null) {
                    tapsignerCvcInput = ""
                    selectedDevice = HardwareWalletType.TAPSIGNER
                    showDevicePicker = false
                    viewModel.setHardwareDeviceType(HardwareWalletType.TAPSIGNER.name)
                    if (result.uri == null) {
                        pendingTapsignerBackupUri = null
                        nfcStatus = "TAPSIGNER backup save cancelled"
                    } else {
                        pendingTapsignerBackupUri = Uri.parse(result.uri)
                        tapsignerBackupAvailable = true
                        nfcError = null
                        nfcStatus = "Backup destination selected. Re-enter the TAPSIGNER PIN, then tap the card to create and save the encrypted backup."
                    }
                }
            }
            else -> Unit
        }
    }

    val isSeedPhrase = uiState.detectedType == ImportWalletViewModel.DetectedType.SEED_12 ||
            uiState.detectedType == ImportWalletViewModel.DetectedType.SEED_24
    // Hardware import includes TAPSIGNER CVC entry and authenticated NFC operations.
    // Ref-counting prevents this route from clearing protection owned by an adjacent screen.
    SecureWindowEffect()

    LaunchedEffect(uiState.input, uiState.detectedType, hardwareWalletMode) {
        if (!hardwareWalletMode && isSeedPhrase) {
            val importedWords = uiState.input.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (importedWords.size == 12 || importedWords.size == 24) {
                expectedSeedWordCount = importedWords.size
                secureSeedWords = importedWords
                entryMode = ImportEntryMode.SeedPhrase
            }
        }
    }

    // Snackbar host for camera error messages
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when camera error occurs
    LaunchedEffect(cameraErrorMessage) {
        cameraErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            cameraErrorMessage = null
        }
    }

    fun clearPendingTapsignerCvc() {
        pendingTapsignerCvc?.fill('0')
        pendingTapsignerCvc = null
    }

    fun stopNfcReader(clearTapsignerPin: Boolean = false) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if (hostActivity != null && adapter != null) {
            adapter.disableReaderMode(hostActivity)
            if (selectedDevice?.usesCoinkiteTapProtocol != true) {
                NfcDispatch.disableForegroundDispatch(hostActivity, adapter)
            }
        }
        nfcReaderActive = false
        clearPendingTapsignerCvc()
        pendingTapsignerInitialize = false
        pendingTapsignerBackup = false
        pendingTapsignerBackupUri = null
        if (clearTapsignerPin) tapsignerCvcInput = ""
    }

    fun processHardwareNfcTag(
        tag: Tag,
        hostActivity: Activity,
        device: HardwareWalletType,
        cvc: CharArray?,
        initializeTapsigner: Boolean = false,
        backupTapsigner: Boolean = false
    ) {
        if (!nfcProcessing.compareAndSet(false, true)) return
        try {
            if (device.usesCoinkiteTapProtocol) {
                val readerCvc = cvc ?: error("Enter the TAPSIGNER PIN before importing over NFC")
                if (backupTapsigner) {
                    val backupUri = pendingTapsignerBackupUri ?: error("Choose a backup file first")
                    val backup = TapsignerNfcReader.createBackup(tag, readerCvc)
                    try {
                        try {
                            context.contentResolver.openOutputStream(backupUri)?.use { output ->
                                output.write(backup.data)
                            } ?: error("Could not open backup file")
                        } catch (writeError: Exception) {
                            error("TAPSIGNER backup was created, but Clench could not save the file: ${writeError.message}")
                        }
                    } finally {
                        backup.data.fill(0)
                    }
                    hostActivity.runOnUiThread {
                        tapsignerBackupAvailable = true
                        tapsignerBackupCount = backup.numberOfBackups
                        nfcStatus = "Encrypted TAPSIGNER backup saved. ${backup.summary}"
                        nfcError = null
                        stopNfcReader(clearTapsignerPin = true)
                    }
                } else {
                    val result = if (initializeTapsigner) {
                        TapsignerNfcReader.initializeAndReadAccountXpub(tag, readerCvc)
                    } else {
                        TapsignerNfcReader.readAccountXpub(tag, readerCvc)
                    }
                    hostActivity.runOnUiThread {
                        viewModel.setInput(result.originWrappedXpub)
                        nfcStatus = result.summary
                        nfcError = null
                        tapsignerInitializeAvailable = false
                        tapsignerBackupAvailable = true
                        tapsignerBackupFingerprint = result.masterFingerprint.lowercase()
                        stopNfcReader(clearTapsignerPin = true)
                    }
                }
            } else {
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
                    stopNfcReader()
                }
            }
        } catch (e: Exception) {
            hostActivity.runOnUiThread {
                val message = e.message ?: "NFC import failed"
                nfcError = message
                nfcStatus = null
                if (device.usesCoinkiteTapProtocol && message.contains("not been set up", ignoreCase = true)) {
                    tapsignerInitializeAvailable = true
                    tapsignerBackupAvailable = false
                }
                suppressPassiveTapsignerStatusUntil = System.currentTimeMillis() + 15_000L
                stopNfcReader()
            }
        } finally {
            nfcProcessing.set(false)
        }
    }

    fun processTapsignerStatusTag(tag: Tag, hostActivity: Activity) {
        if (!nfcProcessing.compareAndSet(false, true)) return
        try {
            if (System.currentTimeMillis() < suppressPassiveTapsignerStatusUntil) return
            val status = TapsignerNfcReader.readStatus(tag)
            hostActivity.runOnUiThread {
                if (nfcError == null) {
                    if (status.derivationPath == null) {
                        nfcStatus = null
                        tapsignerInitializeAvailable = true
                        tapsignerBackupAvailable = false
                        tapsignerBackupCount = null
                        nfcError = "${status.summary()}. This TAPSIGNER has not been set up yet. Initialize it with a TAPSIGNER-compatible wallet first, then return to Clench to import its xpub."
                    } else {
                        tapsignerInitializeAvailable = false
                        tapsignerBackupAvailable = true
                        tapsignerBackupCount = status.numberOfBackups
                        val singleSigPath = status.defaultTapsignerAccountPath
                        val pathNote = if (status.displayPath != singleSigPath) {
                            " Clench will switch this single-sig import to $singleSigPath before reading the xpub."
                        } else {
                            ""
                        }
                        nfcStatus = "${status.summary()}. Enter the TAPSIGNER PIN and tap NFC to import the xpub.$pathNote"
                    }
                }
            }
        } catch (e: Exception) {
            hostActivity.runOnUiThread {
                if (nfcError == null) {
                    nfcError = e.message ?: "TAPSIGNER NFC read failed"
                    nfcStatus = null
                }
            }
        } finally {
            nfcProcessing.set(false)
        }
    }

    fun startHardwareNfcReader(
        device: HardwareWalletType,
        cvc: CharArray?,
        initializeTapsigner: Boolean = false,
        backupTapsigner: Boolean = false
    ) {
        val hostActivity = activity ?: run {
            cvc?.fill('0')
            nfcError = "NFC reader is unavailable in this view"
            return
        }
        val adapter = nfcAdapter ?: run {
            cvc?.fill('0')
            nfcError = "This phone does not report NFC hardware"
            return
        }
        if (!adapter.isEnabled) {
            cvc?.fill('0')
            nfcError = "NFC is off in Android settings"
            return
        }

        val isCoinkiteTap = device.usesCoinkiteTapProtocol
        val flags = if (isCoinkiteTap) NfcReaderModeFlags.coinkiteTap else NfcReaderModeFlags.hardwareImport
        clearPendingTapsignerCvc()
        pendingTapsignerCvc = cvc
        pendingTapsignerInitialize = initializeTapsigner
        pendingTapsignerBackup = backupTapsigner

        try {
            if (isCoinkiteTap) {
                NfcDispatch.enableCoinkiteForegroundDispatch(hostActivity, adapter)
            }
            adapter.enableReaderMode(
                hostActivity,
                { tag ->
                    processHardwareNfcTag(tag, hostActivity, device, cvc, initializeTapsigner, backupTapsigner)
                },
                flags,
                null
            )
            nfcError = null
            nfcStatus = if (isCoinkiteTap) {
                when {
                    initializeTapsigner -> "Ready to initialize. Hold ${device.displayName} against the phone."
                    backupTapsigner -> "Ready to save encrypted backup. Hold ${device.displayName} against the phone again."
                    else -> "Ready to import. Hold ${device.displayName} against the phone."
                }
            } else {
                "Ready for NFC. Hold ${device.displayName} against the phone."
            }
            nfcReaderActive = true
        } catch (e: Exception) {
            clearPendingTapsignerCvc()
            pendingTapsignerInitialize = false
            pendingTapsignerBackup = false
            pendingTapsignerBackupUri = null
            nfcError = e.message ?: "Could not start NFC reader"
            nfcStatus = null
            nfcReaderActive = false
        }
    }

    fun beginTapsignerInitialize() {
        when {
            nfcAdapter == null -> nfcError = "This phone does not report NFC hardware"
            !nfcAdapter.isEnabled -> nfcError = "NFC is off in Android settings"
            activity == null -> nfcError = "NFC reader is unavailable in this view"
            selectedDevice == null -> nfcError = "Choose a hardware wallet first"
            tapsignerCvcInput.length !in 6..32 -> nfcError = "Enter the TAPSIGNER PIN"
            else -> {
                viewModel.clearError()
                nfcError = null
                suppressPassiveTapsignerStatusUntil = 0L
                startHardwareNfcReader(
                    device = selectedDevice!!,
                    cvc = tapsignerCvcInput.toCharArray(),
                    initializeTapsigner = true
                )
            }
        }
    }

    fun tapsignerBackupFilename(): String {
        val suffix = tapsignerBackupFingerprint?.takeIf { it.isNotBlank() } ?: "card"
        return "tapsigner-backup-$suffix-${LocalDate.now()}.aes"
    }

    fun requestTapsignerBackup() {
        when {
            nfcAdapter == null -> nfcError = "This phone does not report NFC hardware"
            !nfcAdapter.isEnabled -> nfcError = "NFC is off in Android settings"
            activity == null -> nfcError = "NFC reader is unavailable in this view"
            selectedDevice == null -> nfcError = "Choose a hardware wallet first"
            tapsignerCvcInput.length !in 6..32 -> nfcError = "Enter the TAPSIGNER PIN"
            else -> showTapsignerBackupConfirm = true
        }
    }

    // Hardware wallet onboarding is choice-based: select file, scan QR, or NFC explicitly.
    DisposableEffect(activity, nfcAdapter) {
        onDispose {
            stopNfcReader(clearTapsignerPin = true)
        }
    }

    val mainActivity = activity as? MainActivity
    LaunchedEffect(
        nfcReaderActive,
        selectedDevice,
        pendingTapsignerCvc,
        pendingTapsignerInitialize,
        pendingTapsignerBackup,
        pendingTapsignerBackupUri,
        mainActivity
    ) {
        val hostActivity = activity
        val device = selectedDevice
        if (hostActivity == null || mainActivity == null || device == null) {
            return@LaunchedEffect
        }
        if (!nfcReaderActive && !device.usesCoinkiteTapProtocol) return@LaunchedEffect
        mainActivity.nfcTagFlow.collect { tag ->
            if (nfcReaderActive) {
                processHardwareNfcTag(
                    tag,
                    hostActivity,
                    device,
                    pendingTapsignerCvc,
                    pendingTapsignerInitialize,
                    pendingTapsignerBackup
                )
            } else if (device.usesCoinkiteTapProtocol) {
                processTapsignerStatusTag(tag, hostActivity)
            }
        }
    }

    DisposableEffect(selectedDevice, activity, nfcAdapter) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if (selectedDevice?.usesCoinkiteTapProtocol == true && hostActivity != null && adapter != null && adapter.isEnabled) {
            runCatching { NfcDispatch.enableCoinkiteForegroundDispatch(hostActivity, adapter) }
            onDispose {
                NfcDispatch.disableForegroundDispatch(hostActivity, adapter)
            }
        } else {
            onDispose { }
        }
    }

    // Device picker bottom sheet
    if (showDevicePicker) {
        HardwareWalletPickerSheet(
            title = "Choose hardware wallet export",
            onDismiss = {
                showDevicePicker = false
                if (selectedDevice == null) onBack()
            },
            onDeviceSelected = { device ->
                selectedDevice = device
                showDevicePicker = false
                tapsignerCvcInput = ""
                tapsignerInitializeAvailable = false
                tapsignerBackupAvailable = false
                tapsignerBackupCount = null
                tapsignerBackupFingerprint = null
                stopNfcReader()
                // Store selected device in viewModel so it gets passed to importWatchOnly
                viewModel.setHardwareDeviceType(device.name)
            }
        )
    }

    if (showTapsignerInitializeConfirm) {
        AlertDialog(
            onDismissRequest = { showTapsignerInitializeConfirm = false },
            title = { Text("Initialize TAPSIGNER?") },
            text = {
                Text(
                    "Clench will create this card's wallet key using fresh app entropy and the card's secure random generator. Keep the card's printed backup key safe and create a backup before receiving funds."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTapsignerInitializeConfirm = false
                        beginTapsignerInitialize()
                    }
                ) { Text("Initialize") }
            },
            dismissButton = {
                TextButton(onClick = { showTapsignerInitializeConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTapsignerBackupConfirm) {
        AlertDialog(
            onDismissRequest = { showTapsignerBackupConfirm = false },
            title = { Text("Save TAPSIGNER Backup?") },
            text = {
                Text(
                    "Clench will ask where to save the encrypted .aes backup file. After you choose the file, hold the TAPSIGNER to the phone again; saving the backup is a separate authenticated NFC command that uses the TAPSIGNER PIN you entered. The file is encrypted by the AES backup key printed on your TAPSIGNER. Store the file and printed key separately."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTapsignerBackupConfirm = false
                        // The picker backgrounds Clench and mandatory cleanup disposes this
                        // screen. Never retain or reuse the PIN across that boundary.
                        stopNfcReader(clearTapsignerPin = true)
                        if (!pickerHost.launchPicker(
                                PickerRequest.TapsignerSetupBackup(
                                    hardwareWalletMode = hardwareWalletMode,
                                    filename = tapsignerBackupFilename()
                                )
                            )
                        ) {
                            nfcError = "Finish the current file selection first"
                        }
                    }
                ) { Text("Choose File") }
            },
            dismissButton = {
                TextButton(onClick = { showTapsignerBackupConfirm = false }) {
                    Text("Cancel")
                }
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
                        if (selectedDevice!!.isScreenlessSigner) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "TAPSIGNER is screenless. Verify wallet policy, addresses, and transaction details in Clench or another trusted coordinator before signing.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
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
                val canLoadFile = selectedDevice?.let { supportsHardwareImportFile(it) } == true
                val canScanQr = selectedDevice?.supportsQr == true
                val canUseNfc = selectedDevice?.supportsNfc == true
                val needsTapsignerCvc = selectedDevice?.usesCoinkiteTapProtocol == true
                Text(
                    "Choose how to import from ${selectedDevice?.displayName ?: "your hardware wallet"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (needsTapsignerCvc) {
                    OutlinedTextField(
                        value = tapsignerCvcInput,
                        onValueChange = {
                            tapsignerCvcInput = it.take(32)
                            nfcError = null
                            viewModel.clearError()
                        },
                        label = { Text("TAPSIGNER PIN") },
                        supportingText = {
                            Text("Use the current PIN. If unchanged, this is the Starting PIN Code printed on the card. Do not enter the AES backup key.")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canLoadFile) {
                        OutlinedButton(
                            onClick = {
                                if (!pickerHost.launchPicker(
                                        PickerRequest.WalletSetupImport(hardwareWalletMode)
                                    )
                                ) {
                                    nfcError = "Finish the current file selection first"
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Load File") }
                    }
                    if (canScanQr) {
                        Button(
                            onClick = { showScanner = true },
                            enabled = hasCamera,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan")
                        }
                    }
                    if (canUseNfc) {
                        OutlinedButton(
                            onClick = {
                                when {
                                    nfcAdapter == null -> nfcError = "This phone does not report NFC hardware"
                                    !nfcAdapter.isEnabled -> nfcError = "NFC is off in Android settings"
                                    activity == null -> nfcError = "NFC reader is unavailable in this view"
                                    selectedDevice == null -> nfcError = "Choose a hardware wallet first"
                                    needsTapsignerCvc && tapsignerCvcInput.length !in 6..32 -> {
                                        nfcError = "Enter the TAPSIGNER PIN"
                                    }
                                    else -> {
                                        viewModel.clearError()
                                        suppressPassiveTapsignerStatusUntil = 0L
                                        val cvc = if (needsTapsignerCvc) tapsignerCvcInput.toCharArray() else null
                                        startHardwareNfcReader(selectedDevice!!, cvc)
                                    }
                                }
                            },
                            enabled = !nfcReaderActive && (!needsTapsignerCvc || tapsignerCvcInput.length in 6..32),
                            modifier = Modifier.weight(1f)
                        ) { Text("NFC") }
                    }
                }
                if (needsTapsignerCvc && canUseNfc && tapsignerInitializeAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Uninitialized TAPSIGNER",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Initialize this card here, then Clench will import its account xpub. Use the printed Starting PIN Code unless you changed it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    when {
                                        nfcAdapter == null -> nfcError = "This phone does not report NFC hardware"
                                        !nfcAdapter.isEnabled -> nfcError = "NFC is off in Android settings"
                                        activity == null -> nfcError = "NFC reader is unavailable in this view"
                                        tapsignerCvcInput.length !in 6..32 -> nfcError = "Enter the TAPSIGNER PIN"
                                        else -> showTapsignerInitializeConfirm = true
                                    }
                                },
                                enabled = !nfcReaderActive
                            ) {
                                Text("Initialize TAPSIGNER")
                            }
                        }
                    }
                }
                if (needsTapsignerCvc && canUseNfc && tapsignerBackupAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "TAPSIGNER backup",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val count = tapsignerBackupCount
                            val backupCopy = when {
                                count == null -> "Save an encrypted backup file before receiving funds."
                                count == 0L -> "No encrypted backups are recorded. Save one before receiving funds."
                                count == 1L -> "1 encrypted backup is recorded. You can save another copy."
                                else -> "$count encrypted backups are recorded. You can save another copy."
                            }
                            Text(
                                "$backupCopy To save it, enter the TAPSIGNER PIN, choose where to save the .aes file, then tap the TAPSIGNER again when Clench starts NFC. Store the file separately from the AES backup key printed on the card.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (pendingTapsignerBackupUri == null) {
                                        requestTapsignerBackup()
                                    } else {
                                        when {
                                            nfcAdapter == null -> nfcError = "This phone does not report NFC hardware"
                                            !nfcAdapter.isEnabled -> nfcError = "NFC is off in Android settings"
                                            activity == null -> nfcError = "NFC reader is unavailable in this view"
                                            tapsignerCvcInput.length !in 6..32 -> nfcError = "Re-enter the TAPSIGNER PIN"
                                            else -> {
                                                viewModel.clearError()
                                                nfcError = null
                                                suppressPassiveTapsignerStatusUntil = 0L
                                                startHardwareNfcReader(
                                                    device = HardwareWalletType.TAPSIGNER,
                                                    cvc = tapsignerCvcInput.toCharArray(),
                                                    backupTapsigner = true
                                                )
                                            }
                                        }
                                    }
                                },
                                enabled = !nfcReaderActive
                            ) {
                                Text(
                                    if (pendingTapsignerBackupUri == null) {
                                        "Save encrypted backup"
                                    } else {
                                        "Re-enter PIN, then tap card"
                                    }
                                )
                            }
                        }
                    }
                }
                if (canScanQr && !hasCamera) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Camera unavailable — use file if supported, NFC if supported, or paste manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (nfcReaderActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            stopNfcReader()
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
                    label = { Text("xpub / descriptor / multisig config") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("xpub... or wsh(sortedmulti(...)) or BSMS/Coldcard config") }
                )
            } else {
                val hasMultisigInput = isLikelyMultisigConfig(uiState.input)

                // Standard import: complete backups here, signer assembly in Create Multisig.
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Multisig import",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Restore an existing multisig wallet from a complete descriptor, BSMS record, or coordinator backup. Use signer assembly for a new policy from device exports.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (!pickerHost.launchPicker(
                                            PickerRequest.WalletSetupImport(hardwareWalletMode)
                                        )
                                    ) {
                                        nfcError = "Finish the current file selection first"
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Load File")
                            }
                            Button(
                                onClick = { showScanner = true },
                                enabled = hasCamera,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan")
                            }
                        }
                        if (onCreateMultisig != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onCreateMultisig,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Assemble Signers")
                            }
                        }
                        if (onConnectHardwareWallet != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onConnectHardwareWallet,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Choose Device Export")
                            }
                        }
                        if (!hasCamera) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Camera unavailable — load a setup file or paste below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        nfcStatus?.let { status ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        nfcError?.let { error ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (hasMultisigInput) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Complete multisig configuration detected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (!hardwareWalletMode) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = entryMode == ImportEntryMode.SeedPhrase,
                            onClick = {
                                entryMode = ImportEntryMode.SeedPhrase
                                viewModel.setInput(secureSeedWords.joinToString(" "))
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("Seed phrase") }
                        SegmentedButton(
                            selected = entryMode == ImportEntryMode.PublicOrDescriptor,
                            onClick = {
                                entryMode = ImportEntryMode.PublicOrDescriptor
                                secureSeedWords = emptyList()
                                viewModel.setInput("")
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("Descriptor / xpub") }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (!hardwareWalletMode && entryMode == ImportEntryMode.SeedPhrase) {
                    SecureBip39WordEntry(
                        words = secureSeedWords,
                        expectedWordCount = expectedSeedWordCount,
                        onWordsChange = { words ->
                            secureSeedWords = words
                            viewModel.setInput(
                                if (words.size == expectedSeedWordCount) words.joinToString(" ") else ""
                            )
                        },
                        onExpectedWordCountChange = { count ->
                            expectedSeedWordCount = count
                            secureSeedWords = secureSeedWords.take(count)
                            viewModel.setInput(
                                if (secureSeedWords.size == count) secureSeedWords.joinToString(" ") else ""
                            )
                        }
                    )
                    if (hasCamera) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan SeedQR instead")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = uiState.input,
                        onValueChange = { viewModel.setInput(it) },
                        label = { Text("Enter xpub, descriptor, or multisig config") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = { Text("xpub... or wsh(sortedmulti(...))") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false
                        ),
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
                                    "• Check the fingerprint image below — it must match EVERY time you unlock.",
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
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false
                            ),
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
            var showNamePrompt by remember { mutableStateOf(false) }
            var namePromptText by remember { mutableStateOf("") }
            val suggestedName = suggestedImportWalletName(uiState, hardwareWalletMode, selectedDevice)

            if (showNamePrompt) {
                AlertDialog(
                    onDismissRequest = { showNamePrompt = false },
                    title = { Text("Name This Wallet") },
                    text = {
                        Column {
                            Text("Choose a name before adding this wallet to your list.")
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = namePromptText,
                                onValueChange = { namePromptText = it.take(64) },
                                label = { Text("Wallet name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            enabled = namePromptText.trim().isNotBlank(),
                            onClick = {
                                viewModel.setWalletName(namePromptText.trim())
                                showNamePrompt = false
                                if (isSeedPhrase && uiState.passphrase.isNotBlank() && !hardwareWalletMode) {
                                    showPassphraseConfirmDialog = true
                                } else {
                                    viewModel.importWallet(onWalletImported)
                                }
                            }
                        ) {
                            Text("Import")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                viewModel.setWalletName(suggestedName)
                                showNamePrompt = false
                                if (isSeedPhrase && uiState.passphrase.isNotBlank() && !hardwareWalletMode) {
                                    showPassphraseConfirmDialog = true
                                } else {
                                    viewModel.importWallet(onWalletImported)
                                }
                            }
                        ) {
                            Text("Use Default")
                        }
                    }
                )
            }

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

            val canConnectWallet = !uiState.isLoading &&
                (!hardwareWalletMode || uiState.detectedType != ImportWalletViewModel.DetectedType.NONE)

            Button(
                onClick = {
                    // [H-4] Require confirmation before importing passphrase wallets
                    if (uiState.walletName.isBlank()) {
                        namePromptText = suggestedName
                        showNamePrompt = true
                    } else if (isSeedPhrase && uiState.passphrase.isNotBlank() && !hardwareWalletMode) {
                        showPassphraseConfirmDialog = true
                    } else {
                        viewModel.importWallet(onWalletImported)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canConnectWallet
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

private fun supportsHardwareImportFile(device: HardwareWalletType): Boolean {
    return device.connectionMethod.contains("File") ||
        device.connectionMethod.contains("SD")
}

private fun isLikelyMultisigConfig(input: String): Boolean {
    val text = input.lowercase()
    return text.contains("sortedmulti(") ||
        text.contains("multi(") ||
        text.startsWith("bsms") ||
        text.contains("policy:") ||
        text.contains("derivation:")
}

private fun suggestedImportWalletName(
    uiState: ImportWalletViewModel.UiState,
    hardwareWalletMode: Boolean,
    selectedDevice: HardwareWalletType?
): String {
    if (hardwareWalletMode && selectedDevice != null) return "${selectedDevice.displayName} Wallet"
    val input = uiState.input.lowercase()
    return when {
        input.contains("sortedmulti(") || input.contains("multi(") -> "Imported Multisig"
        uiState.detectedType == ImportWalletViewModel.DetectedType.XPUB_WATCH_ONLY ||
            uiState.detectedType == ImportWalletViewModel.DetectedType.DESCRIPTOR -> "Watch-only Wallet"
        else -> "Imported Wallet"
    }
}

/**
 * Device-specific instructions for exporting the public key via QR/file/NFC.
 */
private fun getDeviceInstructions(device: HardwareWalletType): String {
    return when (device) {
        HardwareWalletType.COLDCARD_Q -> "On your Coldcard Q, export a descriptor or multisig wallet setup file by QR, NFC, or file. Clench accepts BIP-380 descriptors, BSMS descriptor records, and Coldcard multisig config text."
        HardwareWalletType.COLDCARD_MK4 -> "On your Coldcard Mk4, export a descriptor or multisig wallet setup file to microSD, or transfer it with an intentional NFC tap. Clench accepts BIP-380 descriptors, BSMS descriptor records, and Coldcard multisig config text."
        HardwareWalletType.COLDCARD_MK5 -> "On your Coldcard Mk5, export a descriptor or multisig wallet setup file to microSD, or transfer it with an intentional NFC tap. Clench accepts BIP-380 descriptors, BSMS descriptor records, and Coldcard multisig config text."
        HardwareWalletType.SEEDSIGNER -> "On your SeedSigner: Seeds → [Your Seed] → Export Xpub. Choose Native SegWit (BIP84) for single-sig, or Multisig (BIP48) for a cosigner export. SeedSigner displays an animated QR series for scanning."
        HardwareWalletType.KEYSTONE -> "On your Keystone, export a Sparrow-compatible wallet descriptor by QR or file. Clench accepts static/animated QR, UR account/output payloads, descriptors, and multisig wallet config text."
        HardwareWalletType.FOUNDATION_PASSPORT -> "On your Passport, export a wallet descriptor or account QR for a wallet such as Envoy/Sparrow. Clench accepts Passport UR account/output QR payloads, descriptors, and multisig wallet config text."
        HardwareWalletType.TAPSIGNER -> "Tap the card on this screen to read status. If it is not set up, Clench can initialize it after confirmation. To import or save an encrypted backup, enter your TAPSIGNER PIN, then tap NFC. If you never changed it, use the Starting PIN Code printed on the card. Do not enter the AES backup key."
        HardwareWalletType.JADE -> "On your Jade: Options → Wallet → Export Xpub. Jade displays the account xpub as an animated QR; scan it here."
    }
}
