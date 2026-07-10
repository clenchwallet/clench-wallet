package net.clench.wallet.ui.screens

import android.nfc.NfcAdapter
import android.app.Activity
import android.util.Base64
import android.view.WindowManager
import android.widget.Toast
import android.nfc.tech.Ndef
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.MainActivity
import net.clench.wallet.ui.components.AnimatedQrCode
import net.clench.wallet.ui.components.ColdcardNfcPayload
import net.clench.wallet.ui.components.HardwareWalletPickerSheet
import net.clench.wallet.ui.components.NfcReaderModeFlags
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.components.TapsignerNfcReader
import net.clench.wallet.ui.components.encodePsbtForDevice
import net.clench.wallet.ui.components.psbtQrFrameDelayMs
import net.clench.wallet.ui.viewmodel.HardwareWalletPsbtViewModel
import net.clench.wallet.security.InputLimits
import net.clench.wallet.security.readBytesBounded

private enum class ColdcardNfcMode { Idle, SendUnsigned, ReceiveSigned }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareWalletPsbtScreen(
    walletId: String,
    initialDeviceType: HardwareWalletType,
    onBack: () -> Unit,
    viewModel: HardwareWalletPsbtViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var deviceType by remember { mutableStateOf(initialDeviceType) }
    var showScanner by remember { mutableStateOf(false) }
    var showSignerPicker by remember { mutableStateOf(false) }
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val coldcardSupportsNfc = deviceType == HardwareWalletType.COLDCARD_Q ||
        deviceType == HardwareWalletType.COLDCARD_MK4 ||
        deviceType == HardwareWalletType.COLDCARD_MK5
    val isTapsigner = deviceType == HardwareWalletType.TAPSIGNER
    var nfcMode by remember { mutableStateOf(ColdcardNfcMode.Idle) }
    var nfcStatus by remember { mutableStateOf<String?>(null) }
    var nfcError by remember { mutableStateOf<String?>(null) }
    var tapsignerReaderActive by remember { mutableStateOf(false) }
    var tapsignerStatus by remember { mutableStateOf<String?>(null) }
    var tapsignerError by remember { mutableStateOf<String?>(null) }

    if (showSignerPicker) {
        HardwareWalletPickerSheet(
            title = "Continue with signer",
            onDismiss = { showSignerPicker = false },
            onDeviceSelected = { selected ->
                deviceType = selected
                showSignerPicker = false
                nfcMode = ColdcardNfcMode.Idle
                nfcStatus = null
                nfcError = null
                tapsignerReaderActive = false
                tapsignerStatus = null
                tapsignerError = null
            }
        )
    }

    // R7-20: FLAG_SECURE — prevent screenshots of PSBT data
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Initialize PSBT from in-memory store (not nav args)
    val storeData = remember { viewModel.initFromStore() }
    val psbtBase64 = uiState.psbtBase64

    DisposableEffect(nfcMode, psbtBase64, walletId, deviceType) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if (nfcMode == ColdcardNfcMode.Idle || hostActivity == null || adapter == null || !adapter.isEnabled) {
            onDispose { }
        } else {
            adapter.enableReaderMode(
                hostActivity,
                { tag ->
                    try {
                        when (nfcMode) {
                            ColdcardNfcMode.SendUnsigned -> {
                                val ndef = Ndef.get(tag) ?: error("NFC tag does not support NDEF writes")
                                ndef.connect()
                                try {
                                    if (!ndef.isWritable) error("Coldcard NFC tag is not writable right now")
                                    val message = ColdcardNfcPayload.unsignedPsbtMessage(psbtBase64)
                                    if (ndef.maxSize > 0 && message.toByteArray().size > ndef.maxSize) {
                                        error("PSBT is too large for this NFC transfer; use SD card or QR instead")
                                    }
                                    ndef.writeNdefMessage(message)
                                } finally {
                                    ndef.close()
                                }
                                hostActivity.runOnUiThread {
                                    nfcError = null
                                    nfcStatus = "PSBT sent. Review and sign on ${deviceType.displayName}, then tap Receive Signed NFC Return when Coldcard is sharing the signed payload."
                                    nfcMode = ColdcardNfcMode.Idle
                                }
                            }
                            ColdcardNfcMode.ReceiveSigned -> {
                                val ndef = Ndef.get(tag) ?: error("NFC tag does not expose an NDEF message")
                                ndef.connect()
                                val message = try {
                                    ndef.ndefMessage ?: ndef.cachedNdefMessage
                                } finally {
                                    ndef.close()
                                } ?: error("No signed PSBT or transaction found on NFC tag")
                                val payload = ColdcardNfcPayload.extractSigningPayload(message)
                                    ?: error("NFC payload did not include a signed PSBT or transaction")
                                hostActivity.runOnUiThread {
                                    nfcError = null
                                    nfcStatus = "Signed data imported from ${deviceType.displayName}. Clench will check whether more signatures are required."
                                    nfcMode = ColdcardNfcMode.Idle
                                    viewModel.onSignedPsbtReceived(walletId, payload)
                                }
                            }
                            ColdcardNfcMode.Idle -> Unit
                        }
                    } catch (e: Exception) {
                        hostActivity.runOnUiThread {
                            nfcError = e.message ?: "NFC transfer failed"
                            nfcStatus = null
                        }
                    }
                },
                NfcReaderModeFlags.coldcardNdef,
                null
            )
            onDispose { adapter.disableReaderMode(hostActivity) }
        }
    }

    DisposableEffect(tapsignerReaderActive, deviceType) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if (!tapsignerReaderActive || !isTapsigner || hostActivity == null || adapter == null || !adapter.isEnabled) {
            onDispose { }
        } else {
            adapter.enableReaderMode(
                hostActivity,
                { tag ->
                    try {
                        val status = TapsignerNfcReader.readStatus(tag)
                        hostActivity.runOnUiThread {
                            tapsignerStatus = status.summary()
                            tapsignerError = null
                            tapsignerReaderActive = false
                        }
                    } catch (e: Exception) {
                        hostActivity.runOnUiThread {
                            tapsignerError = e.message ?: "TAPSIGNER NFC status read failed"
                            tapsignerStatus = null
                        }
                    }
                },
                NfcReaderModeFlags.coinkiteTap,
                null
            )
            onDispose { adapter.disableReaderMode(hostActivity) }
        }
    }

    // Fix 6: Empty PSBT error state — if psbtBase64 is empty, show error
    if (psbtBase64.isEmpty() && uiState.txid == null && !uiState.isBroadcasting) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sign with ${deviceType.displayName}") },
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
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "PSBT data was lost.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "This can happen if the app was backgrounded. Go back and try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Go Back")
                }
            }
        }
        return
    }

    // Pre-compute QR frames: BBQr for Coldcard Q, BC-UR for other QR devices.
    // Coldcard Mk4/Mk5 do not have a camera; use NFC or SD card file transfer.
    val qrFrames = remember(psbtBase64, deviceType) {
        if (psbtBase64.isNotEmpty() && deviceType.supportsQr) {
            encodePsbtForDevice(psbtBase64, deviceType)
        } else emptyList()
    }

    // Manual frame advance state for BBQr
    var autoAdvance by remember { mutableStateOf(true) }
    var manualFrameIndex by remember { mutableIntStateOf(0) }

    val isBBQr = deviceType == HardwareWalletType.COLDCARD_Q
    val isColdcardFileDevice = deviceType == HardwareWalletType.COLDCARD_Q ||
        deviceType == HardwareWalletType.COLDCARD_MK4 ||
        deviceType == HardwareWalletType.COLDCARD_MK5
    val supportsFileTransfer = deviceType == HardwareWalletType.COLDCARD_Q ||
        deviceType == HardwareWalletType.COLDCARD_MK4 ||
        deviceType == HardwareWalletType.COLDCARD_MK5 ||
        deviceType == HardwareWalletType.KEYSTONE ||
        deviceType == HardwareWalletType.FOUNDATION_PASSPORT
    val outboundPsbtLabel = if (uiState.hasCollectedSignature) "partially signed PSBT" else "unsigned PSBT"
    val signedReturnLabel = if (uiState.hasCollectedSignature) "next signed PSBT" else "signed PSBT"
    val psbtFileName = "${walletId.take(8)}_${if (uiState.hasCollectedSignature) "partial" else "unsigned"}.psbt"

    LaunchedEffect(qrFrames) {
        manualFrameIndex = 0
    }

    // Collect signed PSBTs delivered by NFC while this signing screen is active.
    LaunchedEffect(context, walletId) {
        val activity = context as? MainActivity ?: return@LaunchedEffect
        activity.nfcPsbtFlow.collect { signedPsbtBase64 ->
            viewModel.onSignedPsbtReceived(walletId, signedPsbtBase64)
        }
    }

    // File picker for importing signed PSBT (Coldcard Mk4 SD card flow)
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.use { it.readBytesBounded(InputLimits.PSBT_BYTES) }
                if (bytes != null) {
                    val signedBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    viewModel.onSignedPsbtReceived(walletId, signedBase64)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val psbtSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                val psbtBytes = Base64.decode(psbtBase64, Base64.DEFAULT)
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(psbtBytes)
                } ?: error("Could not open output file")
                Toast.makeText(context, "PSBT saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showScanner) {
        QrScanner(
            onResult = { signedPsbtBase64 ->
                showScanner = false
                viewModel.onSignedPsbtReceived(walletId, signedPsbtBase64)
            },
            onCancel = { showScanner = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign with ${deviceType.displayName}") },
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
                .verticalScroll(rememberScrollState())
        ) {
            // Success state
            if (uiState.txid != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Transaction Broadcast!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "TXID: ${uiState.txid?.take(16) ?: ""}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Done") }
                return@Column
            }

            // Broadcasting state
            if (uiState.isBroadcasting) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Broadcasting transaction...")
                    }
                }
                return@Column
            }

            if (uiState.isProcessingSignedPsbt) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Checking signatures...")
                    }
                }
                return@Column
            }

            if (!uiState.reviewAcknowledged) {
                if (uiState.isReviewLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verifying transaction outputs and fee…")
                    }
                }
                uiState.transactionReview?.let { review ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Review before sharing with signer", fontWeight = FontWeight.Bold)
                            review.outputs.forEach { output ->
                                Column {
                                    Text(
                                        if (output.belongsToWallet) "Change / wallet output" else "Recipient",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(output.amountSat)} sats",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(output.address ?: "Script output ${output.index}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            HorizontalDivider()
                            Text(
                                "Network fee: ${java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(review.feeSat)} sats " +
                                    "(${String.format("%.2f", review.feeRateSatPerVbyte)} sat/vB, ${review.vsize} vB)"
                            )
                            Text("Transaction ID: ${review.txid}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (uiState.requiresHighFeeConfirmation && !uiState.highFeeAcknowledged) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Unusually high fee", fontWeight = FontWeight.Bold)
                                Text("The exact network fee exceeds 5% of the reviewed amount.")
                                TextButton(onClick = viewModel::acknowledgeHighFee) { Text("I verified the fee") }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::acknowledgeReview,
                        enabled = !uiState.requiresHighFeeConfirmation || uiState.highFeeAcknowledged,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Approve PSBT for signer") }
                }
                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(error, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                return@Column
            }

            // Signed PSBT ready state — require explicit user confirmation before broadcast.
            if (uiState.readyToBroadcast && uiState.signedPsbtBase64 != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Enough Signatures Collected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            uiState.signingMessage
                                ?: "Clench verified the signed transaction data against the original PSBT. Broadcast only after you have verified the transaction on your signer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.broadcastSignedPsbt(walletId) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Broadcast Transaction") }
                        if (deviceType.supportsQr) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showScanner = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Scan Different Signed PSBT") }
                        }
                        if (isColdcardFileDevice) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { filePickerLauncher.launch("*/*") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Import Different Signed File") }
                        }
                    }
                }

                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Error: $error",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel") }
                return@Column
            }

            if (deviceType == HardwareWalletType.SEEDSIGNER) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "SeedSigner signing steps",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "On SeedSigner: choose Scan transaction, scan the current QR, review and approve, then show the signed PSBT QR and scan it back into Clench. For multisig, repeat until Clench says enough signatures are collected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (deviceType == HardwareWalletType.COLDCARD_Q) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Coldcard airgap method",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Use the QR flow below by default: Coldcard Q → Scan QR → review → sign → show signed QR. Clench shows the unsigned BBQr separately from the return scanner so the phone is never trying to display and scan at the same time. If QR is slow, turn off Auto-advance and tap through frames manually. You can also use file or NFC transfer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (deviceType == HardwareWalletType.COLDCARD_MK4 || deviceType == HardwareWalletType.COLDCARD_MK5) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "${deviceType.displayName} signing steps",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Use NFC for a phone-only flow: on Coldcard choose Advanced/Tools → NFC Tools → Sign PSBT, tap Send PSBT via NFC in Clench, review and sign, then tap again to import the signed return. SD card or virtual disk remains the fallback for large transactions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (deviceType == HardwareWalletType.KEYSTONE) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Keystone signing steps",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Keystone expects the QR as BC-UR crypto-psbt. Scan this QR, review and approve on Keystone, then scan Keystone’s signed PSBT QR back into Clench. File import/export is also available below if you prefer .psbt transfer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (deviceType == HardwareWalletType.FOUNDATION_PASSPORT) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Foundation Passport signing steps",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Passport accepts PSBTs as animated UR2/BC-UR QR and can return a signed PSBT QR. For large transactions, use the optional microSD-style file flow below and then import the signed PSBT before broadcasting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (deviceType == HardwareWalletType.JADE) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Blockstream Jade signing steps",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Jade QR mode signs Bitcoin PSBTs as BC-UR crypto-psbt. Scan this QR with Jade, review and approve, then scan Jade’s signed PSBT QR back into Clench and explicitly confirm broadcast.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (isTapsigner) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "TAPSIGNER signing status",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "TAPSIGNER is a screenless NFC signer. Clench can verify the card responds to Coinkite Tap Protocol status, but direct PSBT signing is not enabled until PIN-authenticated signing and safe PSBT signature injection are implemented.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "NFC status check",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap the TAPSIGNER to confirm NFC/app selection, firmware, derivation path, and backup count before using this wallet policy elsewhere.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        when {
                            nfcAdapter == null -> Text(
                                "This phone does not report NFC hardware.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            !nfcAdapter.isEnabled -> Text(
                                "NFC is off in Android settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            else -> {
                                Button(
                                    onClick = {
                                        tapsignerError = null
                                        tapsignerStatus = "Ready for NFC status. Hold TAPSIGNER against the phone."
                                        tapsignerReaderActive = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Read TAPSIGNER NFC Status") }
                                if (tapsignerReaderActive) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = {
                                            tapsignerReaderActive = false
                                            tapsignerStatus = null
                                            tapsignerError = null
                                        }
                                    ) { Text("Cancel NFC") }
                                }
                            }
                        }
                        tapsignerStatus?.let { status ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                        tapsignerError?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Direct signing unavailable",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Clench will not send this PSBT to a TAPSIGNER yet. The remaining bridge must compute each input digest, authenticate Tap Protocol sign commands with the TAPSIGNER PIN, inject signatures into the PSBT, finalize it, and re-run output validation before broadcast.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (uiState.signingMessage != null && !uiState.readyToBroadcast) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Signature Collected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${uiState.signingMessage} The QR, NFC, and file export below now contain the current PSBT. Continue with another signer, then scan or import the next signed PSBT.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showSignerPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Choose Another Signer Type") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // QR-based flow (SeedSigner, Keystone, Passport, Coldcard Q, Jade)
            if (deviceType.supportsQr) {
                // Step 1: Show PSBT as animated QR
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Step 1: Scan this $outboundPsbtLabel with your ${deviceType.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // "Hold phone steady" cue for BBQr (Coldcard)
                        if (isBBQr && qrFrames.size > 1) {
                            Text(
                                "📱 Hold phone steady — ${deviceType.displayName} needs to scan all frames",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        AnimatedQrCode(
                            frames = qrFrames,
                            qrSize = if (isBBQr) 768 else 512,
                            qrSizeDp = if (isBBQr) 420.dp else 360.dp,
                            frameDelayMs = deviceType.psbtQrFrameDelayMs(),
                            autoAdvance = autoAdvance,
                            forcedFrameIndex = if (isBBQr && !autoAdvance) manualFrameIndex else null
                        )

                        // Manual frame control toggle for BBQr
                        if (isBBQr && qrFrames.size > 1) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Auto-advance",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = autoAdvance,
                                    onCheckedChange = { autoAdvance = it }
                                )
                            }
                            if (!autoAdvance) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        manualFrameIndex = (manualFrameIndex + 1) % qrFrames.size
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Next Frame")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Step 2: Scan signed PSBT back
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Step 2: Scan the $signedReturnLabel from your ${deviceType.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showScanner = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (uiState.hasCollectedSignature) "Scan Next Signed PSBT" else "Scan Signed PSBT") }
                    }
                }
            }

            if (coldcardSupportsNfc) {
                if (deviceType.supportsQr) Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (deviceType.supportsQr) "Optional: NFC transfer" else "NFC transfer",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Enable NFC on ${deviceType.displayName}. To sign: choose NFC Tools → Sign PSBT, tap the device to send the current PSBT, review/sign on Coldcard, then use Receive Signed NFC Return when Coldcard shares the signed PSBT or transaction.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        when {
                            nfcAdapter == null -> Text(
                                "This phone does not report NFC hardware.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            !nfcAdapter.isEnabled -> Text(
                                "NFC is off in Android settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            else -> {
                                Button(
                                    onClick = {
                                        nfcError = null
                                        nfcStatus = "Ready to send. Hold the phone's NFC antenna against ${deviceType.displayName}."
                                        nfcMode = ColdcardNfcMode.SendUnsigned
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Send PSBT via NFC") }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        nfcError = null
                                        nfcStatus = "Ready to receive. Tap ${deviceType.displayName} when it is sharing the signed PSBT or transaction."
                                        nfcMode = ColdcardNfcMode.ReceiveSigned
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Receive Signed NFC Return") }
                                if (nfcMode != ColdcardNfcMode.Idle) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = {
                                            nfcMode = ColdcardNfcMode.Idle
                                            nfcStatus = null
                                            nfcError = null
                                        }
                                    ) { Text("Cancel NFC") }
                                }
                            }
                        }
                        nfcStatus?.let { status ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                        nfcError?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // File flow (Coldcard SD/virtual disk, Keystone file transfer, Passport microSD)
            if (supportsFileTransfer) {
                // Step 1: Save PSBT file
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (deviceType.supportsQr)
                                "Optional: Save current PSBT file for microSD transfer"
                            else
                                "Step 1: Save current PSBT to file, transfer to ${deviceType.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { psbtSaveLauncher.launch(psbtFileName) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save PSBT File") }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Step 2: Import signed PSBT
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (deviceType.supportsQr)
                                "Optional: after signing on ${deviceType.displayName}, import the $signedReturnLabel file"
                            else
                                "Step 2: After signing on ${deviceType.displayName}, import the $signedReturnLabel or transaction",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (uiState.hasCollectedSignature) "Import Next Signed File" else "Import Signed File") }
                    }
                }
            }

            // Error display
            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Error: $error",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cancel") }
        }
    }
}
