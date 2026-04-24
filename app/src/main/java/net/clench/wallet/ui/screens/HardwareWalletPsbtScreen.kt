package net.clench.wallet.ui.screens

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.app.Activity
import android.util.Base64
import android.view.WindowManager
import android.widget.Toast
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
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.components.encodePsbtForDevice
import net.clench.wallet.ui.viewmodel.HardwareWalletPsbtViewModel

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

    // Pre-compute QR frames: BBQr for Coldcard, BC-UR for others
    val qrFrames = remember(psbtBase64, deviceType) {
        if (psbtBase64.isNotEmpty() && deviceType.supportsQr) {
            encodePsbtForDevice(psbtBase64, deviceType)
        } else emptyList()
    }

    // Manual frame advance state for BBQr
    var autoAdvance by remember { mutableStateOf(true) }
    var manualFrameIndex by remember { mutableIntStateOf(0) }

    val isBBQr = deviceType == HardwareWalletType.COLDCARD_Q || deviceType == HardwareWalletType.COLDCARD_MK4

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
                            qrSizeDp = if (isBBQr) 400.dp else 360.dp,
                            frameDelayMs = when (deviceType) {
                                HardwareWalletType.COLDCARD_Q -> 1000L  // BBQr: slow for Coldcard Q scanner (was 600ms)
                                HardwareWalletType.COLDCARD_MK4 -> 1000L // BBQr: slow for Coldcard scanner
                                else -> 125L // BC-UR: fast fountain codes
                            },
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

            // SD Card / NFC flow (Coldcard Mk4)
            if (deviceType == HardwareWalletType.COLDCARD_MK4) {
                // Step 1: Save PSBT file
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Step 1: Save PSBT to file, transfer to Coldcard via SD card",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                try {
                                    val psbtBytes = Base64.decode(psbtBase64, Base64.DEFAULT)
                                    val contentValues = ContentValues().apply {
                                        put(MediaStore.Downloads.DISPLAY_NAME, "${walletId.take(8)}_unsigned.psbt")
                                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                    }
                                    val uri = context.contentResolver.insert(
                                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
                                    )
                                    uri?.let {
                                        context.contentResolver.openOutputStream(it)?.use { os ->
                                            os.write(psbtBytes)
                                        }
                                        Toast.makeText(context, "PSBT saved to Downloads", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
                            "Step 2: After signing on Coldcard, import the signed PSBT",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Import Signed PSBT") }
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
