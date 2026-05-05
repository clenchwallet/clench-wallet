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
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.components.encodePsbtForDevice
import net.clench.wallet.ui.components.psbtQrFrameDelayMs
import net.clench.wallet.ui.viewmodel.HardwareWalletPsbtViewModel

private enum class ColdcardNfcMode { Idle, SendUnsigned, ReceiveSigned }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareWalletPsbtScreen(
    walletId: String,
    deviceType: HardwareWalletType,
    onBack: () -> Unit,
    viewModel: HardwareWalletPsbtViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showScanner by remember { mutableStateOf(false) }
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val coldcardSupportsNfc = deviceType == HardwareWalletType.COLDCARD_Q ||
        deviceType == HardwareWalletType.COLDCARD_MK4 ||
        deviceType == HardwareWalletType.COLDCARD_MK5
    var nfcMode by remember { mutableStateOf(ColdcardNfcMode.Idle) }
    var nfcStatus by remember { mutableStateOf<String?>(null) }
    var nfcError by remember { mutableStateOf<String?>(null) }

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
            val flags = NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
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
                                    nfcStatus = "Unsigned PSBT sent. Review and sign on ${deviceType.displayName}, then tap Receive Signed NFC Return when Coldcard is sharing the signed payload."
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
                                    nfcStatus = "Signed transaction data imported from ${deviceType.displayName}. Review in Clench before broadcasting."
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
                flags,
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
                val bytes = inputStream?.readBytes()
                inputStream?.close()
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

            // Signed PSBT ready state — require explicit user confirmation before broadcast.
            if (uiState.signedPsbtBase64 != null) {
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
                            "Signed Transaction Ready",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Clench imported signed transaction data from your ${deviceType.displayName}. Broadcast only after you have verified the transaction on your signer.",
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
                            ) { Text("Scan Signed PSBT Again") }
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
                            "On SeedSigner: choose Scan transaction, scan this QR, review and approve, then show the signed PSBT QR and scan it back into Clench.",
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

            // QR-based flow (SeedSigner, Keystone, Passport, Coldcard Q, Jade)
            if (deviceType.supportsQr) {
                // Step 1: Show PSBT as animated QR
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Step 1: Scan this QR with your ${deviceType.displayName}",
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
                            "Step 2: Scan the signed QR from your ${deviceType.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showScanner = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Scan Signed PSBT") }
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
                            "Enable NFC on ${deviceType.displayName}. To sign: choose NFC Tools → Sign PSBT, tap the device to send this PSBT, review/sign on Coldcard, then use Receive Signed NFC Return when Coldcard shares the signed PSBT or transaction.",
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
                                "Optional: Save PSBT file for microSD transfer"
                            else
                                "Step 1: Save PSBT to file, transfer to ${deviceType.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { psbtSaveLauncher.launch("${walletId.take(8)}_unsigned.psbt") },
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
                                "Optional: after signing on ${deviceType.displayName}, import the signed PSBT file"
                            else
                                "Step 2: After signing on ${deviceType.displayName}, import the signed PSBT or transaction",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Import Signed File") }
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
