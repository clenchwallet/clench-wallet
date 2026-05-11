package net.clench.wallet.ui.screens

import android.app.Activity
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
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
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import net.clench.wallet.ui.MainActivity
import net.clench.wallet.ui.components.CoinkiteTapCardStatus
import net.clench.wallet.ui.components.NfcDispatch
import net.clench.wallet.ui.components.NfcReaderModeFlags
import net.clench.wallet.ui.components.QrCodeImage
import net.clench.wallet.ui.components.TapsignerAccountXpubResult
import net.clench.wallet.ui.components.TapsignerNfcReader
import net.clench.wallet.ui.components.WalletFingerprint
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.domain.model.PhoneSigner
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.WalletInfoViewModel

private enum class TapsignerWalletNfcAction {
    REFRESH_STATUS,
    VERIFY_CARD,
    SAVE_BACKUP
}

private data class TapsignerWalletLiveStatus(
    val firmwareVersion: String?,
    val birthHeight: Long?,
    val derivationPath: String?,
    val numberOfBackups: Long?,
    val authDelaySeconds: Long?,
    val isTestnet: Boolean?,
    val isTampered: Boolean?,
    val verifiedAt: String
)

private data class TapsignerCardMatchResult(
    val matches: Boolean,
    val message: String,
    val verifiedAt: String
)

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
    val isTapsignerWallet = !uiState.isMultisig && (
        uiState.importedViaDevice == HardwareWalletType.TAPSIGNER.name ||
            uiState.preferredHardwareWallet == HardwareWalletType.TAPSIGNER.name
        )
    val context = LocalContext.current
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showSeedImportSheet by remember { mutableStateOf(false) }
    var expandedXpub by remember { mutableStateOf(false) }
    var expandedDescriptor by remember { mutableStateOf(false) }
    var expandedKeystoreIndex by remember { mutableStateOf<Int?>(null) }
    var keystoreRenameTarget by remember { mutableStateOf<WalletInfoViewModel.MultisigKeystoreInfo?>(null) }
    var keystoreRenameText by remember { mutableStateOf("") }
    var showHardwareWalletMenu by remember { mutableStateOf(false) }
    var tapsignerPinInput by remember { mutableStateOf("") }
    var tapsignerNfcReaderActive by remember { mutableStateOf(false) }
    var tapsignerPendingAction by remember { mutableStateOf<TapsignerWalletNfcAction?>(null) }
    var tapsignerPendingCvc by remember { mutableStateOf<CharArray?>(null) }
    var tapsignerPendingBackupUri by remember { mutableStateOf<Uri?>(null) }
    var tapsignerNfcStatus by remember { mutableStateOf<String?>(null) }
    var tapsignerNfcError by remember { mutableStateOf<String?>(null) }
    var tapsignerLiveStatus by remember { mutableStateOf<TapsignerWalletLiveStatus?>(null) }
    var tapsignerCardMatch by remember { mutableStateOf<TapsignerCardMatchResult?>(null) }
    var showTapsignerBackupConfirm by remember { mutableStateOf(false) }
    val tapsignerNfcProcessing = remember { AtomicBoolean(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val labelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importLabels(it) }
    }

    SecureWindowEffect(enabled = isTapsignerWallet && tapsignerPinInput.isNotBlank())

    fun clearTapsignerPendingCvc() {
        tapsignerPendingCvc?.fill('0')
        tapsignerPendingCvc = null
    }

    fun stopTapsignerNfcReader(clearPin: Boolean = false) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if (hostActivity != null && adapter != null) {
            runCatching { adapter.disableReaderMode(hostActivity) }
        }
        tapsignerNfcReaderActive = false
        tapsignerPendingAction = null
        tapsignerPendingBackupUri = null
        clearTapsignerPendingCvc()
        if (clearPin) tapsignerPinInput = ""
    }

    fun updateLiveStatus(status: CoinkiteTapCardStatus) {
        tapsignerLiveStatus = status.toTapsignerWalletLiveStatus()
    }

    fun processTapsignerWalletTag(
        tag: Tag,
        hostActivity: Activity,
        action: TapsignerWalletNfcAction?,
        cvc: CharArray?
    ) {
        if (!tapsignerNfcProcessing.compareAndSet(false, true)) return
        try {
            when (action ?: TapsignerWalletNfcAction.REFRESH_STATUS) {
                TapsignerWalletNfcAction.REFRESH_STATUS -> {
                    val status = TapsignerNfcReader.readStatus(tag)
                    hostActivity.runOnUiThread {
                        updateLiveStatus(status)
                        tapsignerNfcStatus = "${status.summary()}. Card status refreshed."
                        tapsignerNfcError = null
                        stopTapsignerNfcReader()
                    }
                }
                TapsignerWalletNfcAction.VERIFY_CARD -> {
                    val readerCvc = cvc ?: error("Enter the TAPSIGNER PIN before verifying this card")
                    val result = TapsignerNfcReader.readAccountXpub(tag, readerCvc)
                    val matchResult = buildTapsignerCardMatchResult(result, uiState)
                    hostActivity.runOnUiThread {
                        tapsignerCardMatch = matchResult
                        tapsignerNfcStatus = if (matchResult.matches) {
                            "TAPSIGNER verified and matches this wallet."
                        } else {
                            "TAPSIGNER verified, but it does not match this wallet."
                        }
                        tapsignerNfcError = null
                        stopTapsignerNfcReader(clearPin = true)
                    }
                }
                TapsignerWalletNfcAction.SAVE_BACKUP -> {
                    val readerCvc = cvc ?: error("Enter the TAPSIGNER PIN before saving a backup")
                    val backupUri = tapsignerPendingBackupUri ?: error("Choose a backup file first")
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
                        val checkedAt = nowUtcLabel()
                        tapsignerLiveStatus = tapsignerLiveStatus?.copy(
                            numberOfBackups = backup.numberOfBackups,
                            verifiedAt = checkedAt
                        ) ?: TapsignerWalletLiveStatus(
                            firmwareVersion = null,
                            birthHeight = null,
                            derivationPath = null,
                            numberOfBackups = backup.numberOfBackups,
                            authDelaySeconds = null,
                            isTestnet = null,
                            isTampered = null,
                            verifiedAt = checkedAt
                        )
                        tapsignerNfcStatus = "Encrypted TAPSIGNER backup saved. ${backup.summary}"
                        tapsignerNfcError = null
                        stopTapsignerNfcReader(clearPin = true)
                    }
                }
            }
        } catch (e: Exception) {
            hostActivity.runOnUiThread {
                tapsignerNfcError = e.message ?: "TAPSIGNER NFC action failed"
                tapsignerNfcStatus = null
                stopTapsignerNfcReader()
            }
        } finally {
            cvc?.fill('0')
            tapsignerNfcProcessing.set(false)
        }
    }

    fun startTapsignerNfcReader(action: TapsignerWalletNfcAction) {
        val hostActivity = activity ?: run {
            tapsignerNfcError = "NFC reader is unavailable in this view"
            return
        }
        val adapter = nfcAdapter ?: run {
            tapsignerNfcError = "This phone does not report NFC hardware"
            return
        }
        if (!adapter.isEnabled) {
            tapsignerNfcError = "NFC is off in Android settings"
            return
        }
        if (action != TapsignerWalletNfcAction.REFRESH_STATUS && tapsignerPinInput.length !in 6..32) {
            tapsignerNfcError = "Enter the TAPSIGNER PIN"
            return
        }

        clearTapsignerPendingCvc()
        tapsignerPendingAction = action
        tapsignerPendingCvc = if (action == TapsignerWalletNfcAction.REFRESH_STATUS) null else tapsignerPinInput.toCharArray()
        try {
            NfcDispatch.enableCoinkiteForegroundDispatch(hostActivity, adapter)
            adapter.enableReaderMode(
                hostActivity,
                { tag -> processTapsignerWalletTag(tag, hostActivity, action, tapsignerPendingCvc) },
                NfcReaderModeFlags.coinkiteTap,
                null
            )
            tapsignerNfcReaderActive = true
            tapsignerNfcError = null
            tapsignerNfcStatus = when (action) {
                TapsignerWalletNfcAction.REFRESH_STATUS -> "Ready to refresh status. Hold TAPSIGNER against the phone."
                TapsignerWalletNfcAction.VERIFY_CARD -> "Ready to verify. Hold TAPSIGNER against the phone."
                TapsignerWalletNfcAction.SAVE_BACKUP -> "Ready to save backup. Hold TAPSIGNER against the phone."
            }
        } catch (e: Exception) {
            clearTapsignerPendingCvc()
            tapsignerPendingAction = null
            tapsignerPendingBackupUri = null
            tapsignerNfcReaderActive = false
            tapsignerNfcError = e.message ?: "Could not start NFC reader"
            tapsignerNfcStatus = null
        }
    }

    fun tapsignerBackupFilename(): String {
        val suffix = uiState.masterFingerprint?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "card"
        return "tapsigner-backup-$suffix-${LocalDate.now()}.aes"
    }

    val tapsignerBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        when {
            uri == null -> tapsignerNfcStatus = "TAPSIGNER backup save cancelled"
            tapsignerPinInput.length !in 6..32 -> tapsignerNfcError = "Enter the TAPSIGNER PIN"
            else -> {
                tapsignerPendingBackupUri = uri
                tapsignerNfcError = null
                startTapsignerNfcReader(TapsignerWalletNfcAction.SAVE_BACKUP)
            }
        }
    }

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    DisposableEffect(isTapsignerWallet, activity, nfcAdapter) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if (isTapsignerWallet && hostActivity != null && adapter != null && adapter.isEnabled) {
            runCatching { NfcDispatch.enableCoinkiteForegroundDispatch(hostActivity, adapter) }
            onDispose {
                runCatching { adapter.disableReaderMode(hostActivity) }
                NfcDispatch.disableForegroundDispatch(hostActivity, adapter)
                clearTapsignerPendingCvc()
            }
        } else {
            onDispose { clearTapsignerPendingCvc() }
        }
    }

    val mainActivity = activity as? MainActivity
    LaunchedEffect(isTapsignerWallet, mainActivity, tapsignerNfcReaderActive, tapsignerPendingAction, tapsignerPendingCvc) {
        val hostActivity = activity
        if (!isTapsignerWallet || hostActivity == null || mainActivity == null) return@LaunchedEffect
        mainActivity.nfcTagFlow.collect { tag ->
            processTapsignerWalletTag(tag, hostActivity, tapsignerPendingAction, tapsignerPendingCvc)
        }
    }

    LaunchedEffect(uiState.labelImportExportResult) {
        uiState.labelImportExportResult?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearLabelImportExportResult()
        }
    }

    if (showSeedImportSheet && uiState.isWatchOnly && !uiState.isMultisig && !isTapsignerWallet) {
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

    keystoreRenameTarget?.let { keystore ->
        AlertDialog(
            onDismissRequest = { keystoreRenameTarget = null },
            title = { Text("Rename Keystore") },
            text = {
                OutlinedTextField(
                    value = keystoreRenameText,
                    onValueChange = { keystoreRenameText = it },
                    label = { Text("Keystore name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameKeystore(keystore.keyId, keystoreRenameText)
                        keystoreRenameTarget = null
                    },
                    enabled = keystoreRenameText.trim().isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { keystoreRenameTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showTapsignerBackupConfirm) {
        AlertDialog(
            onDismissRequest = { showTapsignerBackupConfirm = false },
            title = { Text("Save TAPSIGNER Backup?") },
            text = {
                Text(
                    "Clench will save the encrypted backup file returned by the card. The file is encrypted by the AES backup key printed on your TAPSIGNER. Store the file and printed key separately."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTapsignerBackupConfirm = false
                        tapsignerBackupLauncher.launch(tapsignerBackupFilename())
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
                            val walletType = when {
                                uiState.isMultisig && uiState.isWatchOnly -> "Multisig Watch-Only"
                                uiState.isMultisig -> "Multisig"
                                isTapsignerWallet -> "TAPSIGNER Watch-Only"
                                uiState.isWatchOnly -> "Watch-Only"
                                else -> "Full Wallet"
                            }
                            Text(
                                walletType,
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

                        if (uiState.isMultisig) {
                            uiState.multisigPolicy?.let { policy ->
                                Row {
                                    Text("Policy: ", style = MaterialTheme.typography.labelMedium)
                                    Text("${policy.threshold} of ${policy.totalSigners}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            // Derivation path
                            Row {
                                Text("Derivation: ", style = MaterialTheme.typography.labelMedium)
                                Text(uiState.derivationPath, style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace
                                ))
                            }
                        }
                    }
                }

                uiState.multisigPolicy?.let { policy ->
                    MultisigConfigurationCard(
                        policy = policy,
                        expandedDescriptor = expandedDescriptor,
                        onToggleDescriptor = { expandedDescriptor = !expandedDescriptor },
                        expandedKeystoreIndex = expandedKeystoreIndex,
                        onToggleKeystore = { index ->
                            expandedKeystoreIndex = if (expandedKeystoreIndex == index) null else index
                        },
                        onRenameKeystore = { keystore ->
                            keystoreRenameTarget = keystore
                            keystoreRenameText = keystore.label
                        },
                        onCopy = { text, label -> viewModel.copyToClipboard(text, label) },
                        copied = uiState.copied
                    )
                }

                if (isTapsignerWallet) {
                    TapsignerSignerCard(
                        liveStatus = tapsignerLiveStatus,
                        cardMatch = tapsignerCardMatch,
                        nfcStatus = tapsignerNfcStatus,
                        nfcError = tapsignerNfcError,
                        nfcReaderActive = tapsignerNfcReaderActive,
                        pinInput = tapsignerPinInput,
                        onPinChange = { tapsignerPinInput = it },
                        onRefreshStatus = { startTapsignerNfcReader(TapsignerWalletNfcAction.REFRESH_STATUS) },
                        onVerifyCard = { startTapsignerNfcReader(TapsignerWalletNfcAction.VERIFY_CARD) },
                        onRequestBackup = {
                            if (tapsignerPinInput.length !in 6..32) {
                                tapsignerNfcError = "Enter the TAPSIGNER PIN"
                            } else {
                                showTapsignerBackupConfirm = true
                            }
                        }
                    )

                    TapsignerPublicWalletCard(
                        uiState = uiState,
                        expandedXpub = expandedXpub,
                        copied = uiState.copied,
                        onToggleXpub = { expandedXpub = !expandedXpub },
                        onCopyXpub = { viewModel.copyToClipboard(uiState.accountXpub, "Account Public Key") },
                        onShowQr = { showQrDialog = true }
                    )
                } else {
                    // ─── Hardware Wallet Info ───
                    // Only show if wallet was imported via a hardware wallet
                    if (uiState.importedViaDevice != null && !uiState.isMultisig) {
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
                                    Text(walletScriptType(uiState.descriptor), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    // ─── Per-wallet External Signer Preference ───
                    if (uiState.isWatchOnly && !uiState.isMultisig) Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "External Signer for Spending",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Choose the hardware signer Clench should use when this watch-only wallet creates PSBTs. Hot wallets sign on this device and do not use this setting.",
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
                                            text = { Text("${device.displayName} - ${device.connectionMethod}") },
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
                if (uiState.accountXpub.isNotEmpty() && !isTapsignerWallet) {
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
                if (!uiState.isMultisig && !isTapsignerWallet) uiState.fingerprintBytes?.let { fpBytes ->
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
                if (!uiState.isWatchOnly && !uiState.isMultisig) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Signing Method",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "This hot wallet signs with the seed phrase stored on this device. Hardware signer selection is hidden because spending authority is already local.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (uiState.isWatchOnly && !uiState.isMultisig && !isTapsignerWallet) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Convert to Hot Wallet",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Only add a seed phrase if you intentionally want this watch-only wallet to become a hot wallet on this device. Hardware-wallet signing remains the safer default for watch-only wallets.",
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
                    if (!isTapsignerWallet) {
                        Button(
                            onClick = onBackup,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Backup & Export") }
                    }
                    val showDescriptorExport = uiState.isMultisig || uiState.isWatchOnly
                    if (showDescriptorExport) {
                        OutlinedButton(
                            onClick = { viewModel.exportWalletDescriptorBackup() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when {
                                    uiState.isMultisig -> "Export Multisig Descriptor Backup"
                                    isTapsignerWallet -> "Export TAPSIGNER Watch-Only Descriptor"
                                    else -> "Export Watch-Only Descriptor"
                                }
                            )
                        }
                        Text(
                            when {
                                uiState.isMultisig -> {
                                    "Descriptor backups restore multisig policy and watch-only structure. They do not include seed phrases, passphrases, or private keys."
                                }
                                isTapsignerWallet -> {
                                    "Descriptor exports restore the public watch-only wallet in Clench. They do not replace the encrypted TAPSIGNER card backup."
                                }
                                else -> {
                                    "Descriptor exports restore watch-only structure. They do not include seed phrases, passphrases, or private keys."
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ─── View Seed Phrase — only for hot wallets ───
                if (!uiState.isWatchOnly && !uiState.isMultisig) {
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

@Composable
private fun TapsignerSignerCard(
    liveStatus: TapsignerWalletLiveStatus?,
    cardMatch: TapsignerCardMatchResult?,
    nfcStatus: String?,
    nfcError: String?,
    nfcReaderActive: Boolean,
    pinInput: String,
    onPinChange: (String) -> Unit,
    onRefreshStatus: () -> Unit,
    onVerifyCard: () -> Unit,
    onRequestBackup: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "TAPSIGNER",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "This wallet is watch-only in Clench. Spending, card verification, and encrypted card backups require the physical TAPSIGNER and its PIN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            InfoLine("Signer", "TAPSIGNER NFC")
            InfoLine("Wallet mode", "Watch-only until signing")

            liveStatus?.let { status ->
                Divider()
                Text("Card Status", style = MaterialTheme.typography.labelMedium)
                status.firmwareVersion?.let { InfoLine("Firmware", it, mono = true) }
                status.derivationPath?.let { InfoLine("Card path", it, mono = true) }
                status.birthHeight?.let { InfoLine("Birth height", it.toString()) }
                status.numberOfBackups?.let { count ->
                    InfoLine("Encrypted backups", count.toString())
                    if (count == 0L) {
                        Text(
                            "No encrypted card backups are recorded. Save one before receiving funds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                status.authDelaySeconds?.takeIf { it > 0 }?.let { InfoLine("Auth delay", "${it}s") }
                status.isTestnet?.let { InfoLine("Card network", if (it) "Testnet" else "Mainnet") }
                status.isTampered?.takeIf { it }?.let {
                    Text(
                        "Tamper warning reported by card.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                InfoLine("Last checked", status.verifiedAt)
            } ?: Text(
                "Tap TAPSIGNER to refresh firmware, path, backup count, auth delay, and tamper status.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            cardMatch?.let { match ->
                val colors = if (match.matches) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                }
                Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (match.matches) "Physical card matches wallet" else "Physical card does not match wallet",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(match.message, style = MaterialTheme.typography.bodySmall)
                        Text("Verified ${match.verifiedAt}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OutlinedTextField(
                value = pinInput,
                onValueChange = onPinChange,
                label = { Text("TAPSIGNER PIN") },
                supportingText = {
                    Text("Use the current PIN. If unchanged, this is the Starting PIN Code printed on the card. Do not enter the AES backup key.")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = onRefreshStatus,
                enabled = !nfcReaderActive,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Tap to Refresh Card Status") }
            Button(
                onClick = onVerifyCard,
                enabled = !nfcReaderActive,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Verify Card Matches Wallet") }
            OutlinedButton(
                onClick = onRequestBackup,
                enabled = !nfcReaderActive,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Encrypted TAPSIGNER Backup") }

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
private fun TapsignerPublicWalletCard(
    uiState: WalletInfoViewModel.UiState,
    expandedXpub: Boolean,
    copied: Boolean,
    onToggleXpub: () -> Unit,
    onCopyXpub: () -> Unit,
    onShowQr: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Public Wallet Identity",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Clench stores this public descriptor so the wallet can open, sync, and receive without tapping the card. The private key stays on TAPSIGNER.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            uiState.masterFingerprint?.let { InfoLine("Master fingerprint", it, mono = true) }
            uiState.storedDerivationPath?.let { InfoLine("Derivation", "m/$it", mono = true) }
            InfoLine("Script type", walletScriptType(uiState.descriptor))
            InfoLine("Network", if (uiState.network == "testnet") "Testnet" else "Mainnet")

            uiState.fingerprintBytes?.let { fpBytes ->
                WalletFingerprint(
                    fingerprintBytes = fpBytes,
                    masterFingerprint = uiState.masterFingerprintBytes,
                    label = "Master fingerprint visual check - local wallet identifier"
                )
            }

            if (uiState.accountXpub.isNotEmpty()) {
                Divider()
                Text(
                    "Account Public Key (${uiState.xpubLabel})",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    if (expandedXpub) uiState.accountXpub
                    else uiState.accountXpub.take(8) + "..." + uiState.accountXpub.takeLast(6),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = if (expandedXpub) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onToggleXpub) {
                        Text(if (expandedXpub) "Collapse" else "Expand")
                    }
                    TextButton(onClick = onCopyXpub) {
                        Text(if (copied) "Copied" else "Copy")
                    }
                    TextButton(onClick = onShowQr) {
                        Text("Show QR")
                    }
                }
            }
        }
    }
}

@Composable
private fun MultisigConfigurationCard(
    policy: WalletInfoViewModel.MultisigPolicyInfo,
    expandedDescriptor: Boolean,
    onToggleDescriptor: () -> Unit,
    expandedKeystoreIndex: Int?,
    onToggleKeystore: (Int) -> Unit,
    onRenameKeystore: (WalletInfoViewModel.MultisigKeystoreInfo) -> Unit,
    onCopy: (String, String) -> Unit,
    copied: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Multisig Configuration",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            InfoLine("Policy Type", policy.policyType)
            InfoLine("Script Type", policy.scriptType)
            InfoLine("M of N", "${policy.threshold} / ${policy.totalSigners}")

            Spacer(modifier = Modifier.height(12.dp))
            Text("Descriptor", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (expandedDescriptor) policy.descriptor else shortenMiddle(policy.descriptor, 28, 18),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = if (expandedDescriptor) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onToggleDescriptor) {
                    Text(if (expandedDescriptor) "Collapse" else "Expand")
                }
                TextButton(onClick = { onCopy(policy.descriptor, "Multisig Descriptor") }) {
                    Text(if (copied) "Copied" else "Copy")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("BSMS Round Trip", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                shortenMiddle(policy.bsmsDescriptorRecord, 28, 18),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { onCopy(policy.bsmsDescriptorRecord, "BSMS Descriptor Record") }) {
                Text(if (copied) "Copied" else "Copy BSMS")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Keystores",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                policy.keystores.forEachIndexed { index, keystore ->
                    KeystoreCard(
                        keystore = keystore,
                        expanded = expandedKeystoreIndex == index,
                        onToggle = { onToggleKeystore(index) },
                        onRename = { onRenameKeystore(keystore) },
                        onCopy = { onCopy(keystore.xpub, "${keystore.label} xpub") },
                        copied = copied
                    )
                }
            }

            if (policy.warnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Signer Warnings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                policy.warnings.forEach { warning ->
                    Text(
                        "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Recovery Drill",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            policy.recoveryChecklist.forEach { item ->
                Text("• $item", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                policy.keyReplacementWarning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun KeystoreCard(
    keystore: WalletInfoViewModel.MultisigKeystoreInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    copied: Boolean
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    keystore.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRename) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Rename keystore",
                        modifier = Modifier.size(18.dp)
                    )
                }
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Collapse" else "Details")
                }
            }

            keystore.masterFingerprint?.let { InfoLine("Master fingerprint", it, mono = true) }
            keystore.derivationPath?.let { InfoLine("Derivation", it, mono = true) }
            keystore.preferredHardwareWallet?.let { deviceName ->
                val device = runCatching { HardwareWalletType.valueOf(deviceName) }.getOrNull()
                InfoLine("Signing device", device?.displayName ?: PhoneSigner.displayName(deviceName))
            }

            if (keystore.checks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Checks", style = MaterialTheme.typography.labelMedium)
                keystore.checks.forEach { check ->
                    Text("✓ $check", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (keystore.warnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                keystore.warnings.forEach { warning ->
                    Text(
                        "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("xpub", style = MaterialTheme.typography.labelMedium)
            Text(
                if (expanded) keystore.xpub else shortenMiddle(keystore.xpub, 18, 12),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Hide xpub" else "Show xpub")
                }
                TextButton(onClick = onCopy) {
                    Text(if (copied) "Copied" else "Copy")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 112.dp)
        )
        Text(
            value,
            style = if (mono) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            modifier = Modifier.weight(1f)
        )
    }
}

private fun shortenMiddle(value: String, prefix: Int, suffix: Int): String {
    if (value.length <= prefix + suffix + 3) return value
    return value.take(prefix) + "..." + value.takeLast(suffix)
}

private fun walletScriptType(descriptor: String): String = when {
    descriptor.startsWith("wpkh(") -> "Native SegWit (P2WPKH)"
    descriptor.startsWith("tr(") -> "Taproot (P2TR)"
    descriptor.startsWith("sh(wpkh(") -> "Nested SegWit (P2SH-P2WPKH)"
    descriptor.startsWith("pkh(") -> "Legacy (P2PKH)"
    descriptor.startsWith("wsh(") -> "SegWit Multisig (P2WSH)"
    else -> "Unknown"
}

private fun CoinkiteTapCardStatus.toTapsignerWalletLiveStatus(): TapsignerWalletLiveStatus {
    return TapsignerWalletLiveStatus(
        firmwareVersion = version,
        birthHeight = birthHeight,
        derivationPath = displayPath,
        numberOfBackups = numberOfBackups,
        authDelaySeconds = authDelaySeconds,
        isTestnet = isTestnet,
        isTampered = isTampered,
        verifiedAt = nowUtcLabel()
    )
}

private fun buildTapsignerCardMatchResult(
    result: TapsignerAccountXpubResult,
    uiState: WalletInfoViewModel.UiState
): TapsignerCardMatchResult {
    val problems = mutableListOf<String>()
    val expectedFingerprint = uiState.masterFingerprint?.uppercase(Locale.US)
    val actualFingerprint = result.masterFingerprint.uppercase(Locale.US)
    if (expectedFingerprint != null && expectedFingerprint != actualFingerprint) {
        problems += "fingerprint expected $expectedFingerprint but card returned $actualFingerprint"
    }

    val expectedPath = normalizeDerivationPath(uiState.storedDerivationPath ?: uiState.derivationPath)
    val actualPath = normalizeDerivationPath(result.derivationPath)
    if (expectedPath.isNotBlank() && expectedPath != actualPath) {
        problems += "path expected m/$expectedPath but card returned m/$actualPath"
    }

    val descriptorContainsXpub = uiState.descriptor.contains(result.xpub) ||
        uiState.changeDescriptor.contains(result.xpub)
    if (!descriptorContainsXpub) {
        problems += "card account xpub is not in this wallet descriptor"
    }

    val now = nowUtcLabel()
    return if (problems.isEmpty()) {
        TapsignerCardMatchResult(
            matches = true,
            message = "Fingerprint, derivation path, and account xpub match the stored Clench descriptor.",
            verifiedAt = now
        )
    } else {
        TapsignerCardMatchResult(
            matches = false,
            message = problems.joinToString(separator = "; "),
            verifiedAt = now
        )
    }
}

private fun normalizeDerivationPath(value: String?): String {
    return value.orEmpty()
        .trim()
        .removePrefix("m/")
        .replace("h", "'")
        .replace("H", "'")
}

private fun nowUtcLabel(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSeedPhraseToWalletSheet(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (mnemonic: CharArray, passphrase: CharArray?) -> Unit
) {
    SecureWindowEffect()
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
