package net.clench.wallet.ui.screens

import android.nfc.NfcAdapter
import android.app.Activity
import android.util.Base64
import android.widget.Toast
import android.nfc.tech.Ndef
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.MainActivity
import net.clench.wallet.ui.components.AnimatedQrCode
import net.clench.wallet.ui.components.ColdcardNfcPayload
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.components.HardwareWalletPickerSheet
import net.clench.wallet.ui.components.NfcReaderModeFlags
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.components.TapsignerNfcReader
import net.clench.wallet.ui.components.TapsignerPinInput
import net.clench.wallet.ui.components.TransactionReviewCard
import net.clench.wallet.ui.components.SignerProgressPresentation
import net.clench.wallet.ui.components.SignerProgressStepper
import net.clench.wallet.ui.components.encodePsbtForDevice
import net.clench.wallet.ui.components.psbtQrFrameDelayMs
import net.clench.wallet.ui.components.isValidTapsignerPin
import net.clench.wallet.ui.components.rememberImeDismissAction
import net.clench.wallet.ui.viewmodel.HardwareWalletPsbtViewModel
import net.clench.wallet.ui.viewmodel.PsbtPickerPurpose
import net.clench.wallet.security.InputLimits
import net.clench.wallet.security.readBytesBounded
import net.clench.wallet.ui.picker.LocalPickerRoundTripHost
import net.clench.wallet.ui.picker.PickerDestination
import net.clench.wallet.ui.picker.PickerPurpose
import net.clench.wallet.ui.picker.PickerRequest

private enum class ColdcardNfcMode { Idle, SendUnsigned, ReceiveSigned }

private sealed interface TapsignerNfcAttempt {
    val id: Long

    data class Status(override val id: Long) : TapsignerNfcAttempt

    data class Sign(
        override val id: Long,
        val token: HardwareWalletPsbtViewModel.TapsignerSigningToken,
        val cvc: CharArray
    ) : TapsignerNfcAttempt
}

private fun TapsignerNfcAttempt.clearSecret() {
    if (this is TapsignerNfcAttempt.Sign) cvc.fill('0')
}

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
    val tapsignerAttemptIds = remember { AtomicLong(0L) }
    var tapsignerAttempt by remember { mutableStateOf<TapsignerNfcAttempt?>(null) }
    val tapsignerReaderActive = tapsignerAttempt != null
    var tapsignerStatus by remember { mutableStateOf<String?>(null) }
    var tapsignerError by remember { mutableStateOf<String?>(null) }
    var tapsignerCvcInput by remember { mutableStateOf("") }
    val dismissIme = rememberImeDismissAction()
    val cancelTapsignerAttempt: () -> Unit = {
        val active = tapsignerAttempt
        if (active is TapsignerNfcAttempt.Sign) {
            viewModel.cancelTapsignerSigning(active.token)
        }
        active?.clearSecret()
        tapsignerAttempt = null
    }

    // A focused Send field can otherwise leave its IME over this route after navigation.
    LaunchedEffect(Unit) { dismissIme() }

    if (showSignerPicker) {
        HardwareWalletPickerSheet(
            title = "Continue with signer",
            onDismiss = { showSignerPicker = false },
            onDeviceSelected = { selected ->
                dismissIme()
                if (viewModel.selectDeviceType(selected.name)) {
                    deviceType = selected
                }
                showSignerPicker = false
                nfcMode = ColdcardNfcMode.Idle
                nfcStatus = null
                nfcError = null
                cancelTapsignerAttempt()
                tapsignerStatus = null
                tapsignerError = null
                tapsignerCvcInput = ""
            }
        )
    }

    // Prevent signer data from appearing in screenshots or Recents. The shared
    // helper is reference-counted so overlapping protected routes cannot clear
    // one another's secure-window flag during navigation.
    SecureWindowEffect()

    val pickerHost = LocalPickerRoundTripHost.current
    val pickerResume by pickerHost.pickerResume.collectAsState()
    val pickerDestination = pickerResume?.destination as? PickerDestination.HardwarePsbt
    val stagedPurpose = when (pickerResume?.purpose) {
        PickerPurpose.HARDWARE_PSBT_IMPORT -> PsbtPickerPurpose.HARDWARE_IMPORT
        PickerPurpose.HARDWARE_PSBT_EXPORT -> PsbtPickerPurpose.HARDWARE_EXPORT
        else -> null
    }
    val pickerRouteMatches = pickerDestination == null ||
        (pickerDestination.walletId == walletId &&
            pickerDestination.deviceType == initialDeviceType.name)

    // The request ID/token key forces reconstruction even if DocumentsUI returned to the same
    // Activity/ViewModel instance. A cancelled result is never allowed to reclaim staged PSBT data.
    val storeData = remember(
        pickerResume?.requestId,
        pickerResume?.cancelled,
        walletId,
        initialDeviceType
    ) {
        when {
            pickerResume == null -> viewModel.initFromStore(
                expectedWalletId = walletId,
                expectedDeviceType = initialDeviceType.name
            )
            pickerResume?.cancelled == true -> null
            pickerDestination != null && stagedPurpose != null && pickerRouteMatches ->
                viewModel.initFromStore(
                    expectedWalletId = walletId,
                    expectedDeviceType = initialDeviceType.name,
                    pickerToken = pickerDestination.handoffToken,
                    pickerPurpose = stagedPurpose
                )
            else -> null
        }
    }
    val psbtBase64 = uiState.psbtBase64

    LaunchedEffect(pickerResume?.requestId, pickerResume?.cancelled, pickerRouteMatches, storeData) {
        val resume = pickerResume ?: return@LaunchedEffect
        val destination = resume.destination as? PickerDestination.HardwarePsbt
            ?: run {
                pickerHost.abortPicker(resume.requestId)
                return@LaunchedEffect
            }
        if (resume.cancelled) {
            pickerHost.consumePickerResult(resume.purpose, destination)
            viewModel.cancelDocumentPickerRoundTrip(destination.handoffToken)
            return@LaunchedEffect
        }
        if (!pickerRouteMatches || storeData == null) {
            viewModel.cancelDocumentPickerRoundTrip(
                destination.handoffToken,
                "The file hand-off did not match this signing route"
            )
            pickerHost.abortPicker(resume.requestId)
        }
    }

    val secureBack: () -> Unit = {
        dismissIme()
        tapsignerCvcInput = ""
        cancelTapsignerAttempt()
        pickerResume?.let { resume ->
            when (val destination = resume.destination) {
                is PickerDestination.HardwarePsbt ->
                    viewModel.cancelDocumentPickerRoundTrip(destination.handoffToken)
                else -> Unit
            }
            pickerHost.abortPicker(resume.requestId)
        }
        onBack()
    }
    BackHandler(onBack = secureBack)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                tapsignerCvcInput = ""
                cancelTapsignerAttempt()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        pickerResume?.requestId,
        uiState.reviewAcknowledged,
        uiState.transactionReview
    ) {
        val destination = pickerResume?.destination as? PickerDestination.HardwarePsbt
            ?: return@LaunchedEffect
        if (!pickerRouteMatches || storeData == null || pickerResume?.cancelled == true) {
            return@LaunchedEffect
        }
        // DocumentsUI is a real background transition. The recreated route must re-inspect
        // and re-approve the staged PSBT before the URI/result can be consumed.
        if (!uiState.reviewAcknowledged || uiState.transactionReview == null) {
            return@LaunchedEffect
        }
        when (pickerResume?.purpose) {
            PickerPurpose.HARDWARE_PSBT_IMPORT -> {
                val result = pickerHost.consumePickerResult(
                    PickerPurpose.HARDWARE_PSBT_IMPORT,
                    destination
                )
                if (result?.uri != null) {
                    if (psbtBase64.isNotBlank()) {
                        try {
                            val bytes = context.contentResolver
                                .openInputStream(Uri.parse(result.uri))
                                ?.use { it.readBytesBounded(InputLimits.PSBT_BYTES) }
                            if (bytes != null) {
                                viewModel.onSignedPsbtReceived(
                                    walletId,
                                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                                )
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "Failed to read file: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            PickerPurpose.HARDWARE_PSBT_EXPORT -> {
                val result = pickerHost.consumePickerResult(
                    PickerPurpose.HARDWARE_PSBT_EXPORT,
                    destination
                )
                if (result?.uri != null) {
                    if (psbtBase64.isNotBlank()) {
                        try {
                            val bytes = Base64.decode(psbtBase64, Base64.DEFAULT)
                            try {
                                context.contentResolver.openOutputStream(Uri.parse(result.uri))
                                    ?.use { it.write(bytes) }
                                    ?: error("Could not open output file")
                            } finally {
                                bytes.fill(0)
                            }
                            Toast.makeText(context, "PSBT saved", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "Save failed: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            else -> Unit
        }
    }

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

    DisposableEffect(tapsignerAttempt, deviceType) {
        val attempt = tapsignerAttempt
        val hostActivity = activity
        val adapter = nfcAdapter
        if (attempt == null) {
            onDispose { }
        } else if (!isTapsigner || hostActivity == null || adapter == null || !adapter.isEnabled) {
            if (attempt is TapsignerNfcAttempt.Sign) {
                viewModel.cancelTapsignerSigning(attempt.token)
            }
            attempt.clearSecret()
            tapsignerAttempt = null
            tapsignerError = when {
                adapter == null -> "This phone does not report NFC hardware"
                !adapter.isEnabled -> "NFC is off in Android settings"
                else -> "TAPSIGNER NFC signing was cancelled"
            }
            onDispose { }
        } else {
            val tagHandled = AtomicBoolean(false)
            val disposed = AtomicBoolean(false)
            adapter.enableReaderMode(
                hostActivity,
                { tag ->
                    if (tagHandled.compareAndSet(false, true)) {
                        try {
                            val signingResult = when (attempt) {
                                is TapsignerNfcAttempt.Sign -> TapsignerNfcReader.signPsbt(
                                    tag = tag,
                                    cvc = attempt.cvc,
                                    psbtBase64 = attempt.token.psbtBase64
                                )
                                is TapsignerNfcAttempt.Status -> null
                            }
                            val status = if (signingResult == null) {
                                TapsignerNfcReader.readStatus(tag).summary()
                            } else {
                                signingResult.summary
                            }
                            hostActivity.runOnUiThread {
                                if (!disposed.get() && tapsignerAttempt?.id == attempt.id) {
                                    val accepted = if (
                                        attempt is TapsignerNfcAttempt.Sign && signingResult != null
                                    ) {
                                        viewModel.completeTapsignerSigning(
                                            attempt.token,
                                            signingResult.signedPsbtBase64
                                        )
                                    } else {
                                        true
                                    }
                                    attempt.clearSecret()
                                    tapsignerAttempt = null
                                    tapsignerStatus = if (accepted) status else null
                                    tapsignerError = if (accepted) null else "Stale TAPSIGNER result was discarded"
                                }
                            }
                        } catch (e: Exception) {
                            if (attempt is TapsignerNfcAttempt.Sign) {
                                viewModel.cancelTapsignerSigning(attempt.token)
                            }
                            attempt.clearSecret()
                            hostActivity.runOnUiThread {
                                if (!disposed.get() && tapsignerAttempt?.id == attempt.id) {
                                    tapsignerError = e.message ?: "TAPSIGNER NFC signing failed"
                                    tapsignerStatus = null
                                    tapsignerAttempt = null
                                }
                            }
                        }
                    }
                },
                NfcReaderModeFlags.coinkiteTap,
                null
            )
            onDispose {
                disposed.set(true)
                adapter.disableReaderMode(hostActivity)
                if (attempt is TapsignerNfcAttempt.Sign) {
                    viewModel.cancelTapsignerSigning(attempt.token)
                }
                attempt.clearSecret()
            }
        }
    }

    // Fix 6: Empty PSBT error state — if psbtBase64 is empty, show error
    if (psbtBase64.isEmpty() && uiState.txid == null && !uiState.isBroadcasting) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sign with ${deviceType.displayName}") },
                    navigationIcon = {
                        IconButton(onClick = secureBack) {
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
                    onClick = secureBack,
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
    val supportsFileTransfer = deviceType.supportsFileTransfer
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
                    IconButton(onClick = secureBack) {
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
                .imePadding()
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
                    onClick = secureBack,
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

            SignerProgressStepper(
                signerName = deviceType.displayName,
                connectionLabel = deviceType.connectionMethod,
                signatureStatus = SignerProgressPresentation.signatureStatus(
                    uiState.collectedSignerReturns,
                    uiState.readyToBroadcast
                ),
                steps = SignerProgressPresentation.steps(
                    reviewAcknowledged = uiState.reviewAcknowledged,
                    hasCollectedSignature = uiState.hasCollectedSignature,
                    readyToBroadcast = uiState.readyToBroadcast,
                    transferDetail = SignerProgressPresentation.transferDetail(deviceType)
                )
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (!uiState.reviewAcknowledged) {
                if (uiState.isReviewLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verifying transaction outputs and fee…")
                    }
                }
                uiState.transactionReview?.let { review ->
                    TransactionReviewCard(
                        review = review,
                        title = "Review before sharing with signer",
                        requiresHighFeeConfirmation = uiState.requiresHighFeeConfirmation,
                        highFeeAcknowledged = uiState.highFeeAcknowledged,
                        onAcknowledgeHighFee = viewModel::acknowledgeHighFee
                    )
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
                OutlinedButton(onClick = secureBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
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
                                onClick = {
                                    val handoffToken = viewModel.stageForDocumentPicker(
                                        PsbtPickerPurpose.HARDWARE_IMPORT,
                                        deviceType.name
                                    )
                                    if (handoffToken == null) {
                                        Toast.makeText(
                                            context,
                                            "Could not secure the signing hand-off",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else if (!pickerHost.launchPicker(
                                            PickerRequest.HardwarePsbtImport(
                                                walletId,
                                                deviceType.name,
                                                handoffToken
                                            )
                                        )
                                    ) {
                                        viewModel.discardDocumentPickerStage(handoffToken)
                                        Toast.makeText(
                                            context,
                                            "Finish the current file selection first",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
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
                    onClick = secureBack,
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
                            "Coldcard QR method",
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
                            "Use NFC for a phone-only flow: on Coldcard choose Advanced/Tools → NFC Tools → Sign PSBT, tap Send PSBT via NFC in Clench, review and sign, then tap again to import the signed return. A user-selected file or microSD card remains the fallback for large transactions.",
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

            if (
                deviceType == HardwareWalletType.ONEKEY_PRO ||
                deviceType == HardwareWalletType.KRUX ||
                deviceType == HardwareWalletType.SPECTER_DIY
            ) {
                val signingInstructions = when (deviceType) {
                    HardwareWalletType.ONEKEY_PRO ->
                        "Use OneKey Pro's air-gapped Bitcoin flow to scan the animated BC-UR crypto-psbt, review every output on the device, sign, and scan its signed PSBT back. Clench does not use USB or Bluetooth data."
                    HardwareWalletType.KRUX ->
                        "Scan the BC-UR crypto-psbt with Krux, review and sign, then scan the signed return into Clench. For large transactions, use the optional microSD file round trip below."
                    HardwareWalletType.SPECTER_DIY ->
                        "Scan the BC-UR crypto-psbt with Specter DIY, review and sign, then scan the signed return into Clench. The optional microSD file round trip is available without using serial or USB data."
                    else -> error("Unexpected air-gapped signer")
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "${deviceType.displayName} signing steps",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            signingInstructions,
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
                uiState.transactionReview?.let { review ->
                    TransactionReviewCard(
                        review = review,
                        title = "Recheck before the TAPSIGNER tap",
                        requiresHighFeeConfirmation = uiState.requiresHighFeeConfirmation,
                        highFeeAcknowledged = uiState.highFeeAcknowledged,
                        onAcknowledgeHighFee = viewModel::acknowledgeHighFee
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "TAPSIGNER signing status",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "TAPSIGNER is a screenless NFC signer. Review every recipient, amount, change output, and fee here before entering the PIN. The card signs Clench's digest; it cannot display or independently confirm the payment.",
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
                            "Read status without authorizing a signature, or use the payment panel below after approving the transaction.",
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
                                        dismissIme()
                                        tapsignerCvcInput = ""
                                        tapsignerError = null
                                        tapsignerStatus = "Ready for NFC status. Hold TAPSIGNER against the phone."
                                        tapsignerAttempt = TapsignerNfcAttempt.Status(
                                            tapsignerAttemptIds.incrementAndGet()
                                        )
                                    },
                                    enabled = tapsignerAttempt == null,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Read TAPSIGNER NFC Status") }
                                if (tapsignerAttempt is TapsignerNfcAttempt.Status) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = {
                                            cancelTapsignerAttempt()
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
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Tap to sign",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Supports PSBT-v0 native-SegWit BIP84 P2WPKH and BIP48 P2WSH multisig inputs using SIGHASH_ALL. Keep the card against the phone while Clench signs every eligible input. The PIN is cleared as soon as NFC starts, and the signed PSBT still passes Clench's normal signature-only merge and final transaction checks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TapsignerPinInput(
                            value = tapsignerCvcInput,
                            onValueChange = {
                                tapsignerCvcInput = it
                                tapsignerError = null
                            },
                            supportingText = "Use the current PIN, not the printed AES backup key.",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !tapsignerReaderActive && !uiState.isProcessingSignedPsbt
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                when {
                                    nfcAdapter == null -> tapsignerError = "This phone does not report NFC hardware"
                                    !nfcAdapter.isEnabled -> tapsignerError = "NFC is off in Android settings"
                                    tapsignerCvcInput.length !in 6..32 -> tapsignerError = "Enter the TAPSIGNER PIN"
                                    !isValidTapsignerPin(tapsignerCvcInput) ->
                                        tapsignerError = "TAPSIGNER PIN must use printable ASCII without spaces"
                                    else -> {
                                        dismissIme()
                                        val token = viewModel.beginTapsignerSigning(walletId)
                                        if (token != null) {
                                            val signingCvc = tapsignerCvcInput.toCharArray()
                                            tapsignerCvcInput = ""
                                            tapsignerError = null
                                            tapsignerStatus = "Hold TAPSIGNER against the phone until every input is signed."
                                            tapsignerAttempt = TapsignerNfcAttempt.Sign(
                                                id = tapsignerAttemptIds.incrementAndGet(),
                                                token = token,
                                                cvc = signingCvc
                                            )
                                        }
                                    }
                                }
                            },
                            enabled = isValidTapsignerPin(tapsignerCvcInput) &&
                                !tapsignerReaderActive &&
                                !uiState.isProcessingSignedPsbt,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Sign Reviewed Payment with TAPSIGNER") }
                        if (tapsignerAttempt is TapsignerNfcAttempt.Sign) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    cancelTapsignerAttempt()
                                    tapsignerStatus = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Cancel Signing Tap") }
                        }
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
                            onClick = {
                                dismissIme()
                                showSignerPicker = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Choose Another Signer Type") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // QR-based flow for every device with an explicit PSBT QR format.
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

            // File flow (Coldcard or Passport microSD, or a user-selected signer file)
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
                            onClick = {
                                val handoffToken = viewModel.stageForDocumentPicker(
                                    PsbtPickerPurpose.HARDWARE_EXPORT,
                                    deviceType.name
                                )
                                if (handoffToken == null) {
                                    Toast.makeText(
                                        context,
                                        "Could not secure the signing hand-off",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else if (!pickerHost.launchPicker(
                                        PickerRequest.HardwarePsbtExport(
                                            walletId = walletId,
                                            deviceType = deviceType.name,
                                            filename = psbtFileName,
                                            handoffToken = handoffToken
                                        )
                                    )
                                ) {
                                    viewModel.discardDocumentPickerStage(handoffToken)
                                    Toast.makeText(
                                        context,
                                        "Finish the current file selection first",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
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
                            onClick = {
                                val handoffToken = viewModel.stageForDocumentPicker(
                                    PsbtPickerPurpose.HARDWARE_IMPORT,
                                    deviceType.name
                                )
                                if (handoffToken == null) {
                                    Toast.makeText(
                                        context,
                                        "Could not secure the signing hand-off",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else if (!pickerHost.launchPicker(
                                        PickerRequest.HardwarePsbtImport(
                                            walletId,
                                            deviceType.name,
                                            handoffToken
                                        )
                                    )
                                ) {
                                    viewModel.discardDocumentPickerStage(handoffToken)
                                    Toast.makeText(
                                        context,
                                        "Finish the current file selection first",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
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
                onClick = secureBack,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cancel") }
        }
    }
}
