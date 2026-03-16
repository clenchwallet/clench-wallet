package net.clench.wallet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.components.HardwareWalletPickerSheet
import net.clench.wallet.ui.util.BiometricHelper
import net.clench.wallet.ui.viewmodel.SendViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    walletId: String,
    onBack: () -> Unit,
    utxoTxid: String? = null,
    onNavigateHardwarePsbt: ((walletId: String, psbtBase64: String, deviceType: HardwareWalletType) -> Unit)? = null,
    viewModel: SendViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showHardwareWalletPicker by remember { mutableStateOf(false) }

    // FLAG_SECURE — prevent screenshots of balance/addresses
    val context = LocalContext.current

    // Resolve FragmentActivity from Compose context
    val fragmentActivity = remember(context) {
        var ctx = context as? android.content.Context
        while (ctx != null) {
            if (ctx is FragmentActivity) return@remember ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        null
    }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            // Parse BIP21 URI format: bitcoin:bc1q...?amount=0.001&label=...
            val address = if (scanned.startsWith("bitcoin:", ignoreCase = true)) {
                scanned.substringAfter(":").substringBefore("?")
            } else scanned
            viewModel.setAddress(address)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan Bitcoin address QR code")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            scanLauncher.launch(options)
        } else {
            viewModel.setError("Camera permission required to scan QR codes")
        }
    }

    LaunchedEffect(walletId) {
        viewModel.load(walletId)
        if (utxoTxid != null) {
            viewModel.setUtxo(utxoTxid, 0)
        }
    }

    // Hardware wallet picker sheet
    if (showHardwareWalletPicker) {
        HardwareWalletPickerSheet(
            onDismiss = { showHardwareWalletPicker = false },
            onDeviceSelected = { deviceType ->
                showHardwareWalletPicker = false
                viewModel.createPsbt { psbtBase64 ->
                    // Store PSBT in memory before navigating (avoids leaking via nav route args)
                    viewModel.storePsbtForNavigation(walletId, psbtBase64, deviceType.name)
                    onNavigateHardwarePsbt?.invoke(walletId, psbtBase64, deviceType)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isWatchOnly) "Create PSBT" else "Send Bitcoin") },
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
        ) {
            if (uiState.availableBalanceSat > 0) {
                Text(
                    "Available: ${uiState.availableBalanceSat} sats",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            uiState.utxoTxid?.let { txid ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        "Spending UTXO: ${txid.take(8)}…",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            OutlinedTextField(
                value = uiState.toAddress,
                onValueChange = { viewModel.setAddress(it) },
                label = { Text("Bitcoin address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = {
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }) {
                        Text("Scan")
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.amountSat,
                onValueChange = { viewModel.setAmount(it) },
                label = { Text("Amount (sats)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                suffix = { Text("sats") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = uiState.sendMax,
                    onCheckedChange = { viewModel.setSendMax(it) }
                )
                Text("Send max (drain wallet)")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.feeRate,
                onValueChange = { viewModel.setFeeRate(it) },
                label = { Text("Fee rate (sat/vB, whole number)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.isWatchOnly) {
                // Watch-only wallet: hardware wallet signing flow
                Button(
                    onClick = { showHardwareWalletPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Sign with Hardware Wallet")
                }
            } else if (uiState.txHex != null) {
                Text("Transaction ready to broadcast", color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.broadcast { onBack() } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Broadcast Transaction") }
            } else {
                Button(
                    onClick = {
                        if (fragmentActivity != null && BiometricHelper.canAuthenticate(context)) {
                            BiometricHelper.authenticate(
                                activity = fragmentActivity,
                                title = "Authenticate to send Bitcoin",
                                subtitle = "Verify your identity to sign this transaction",
                                onSuccess = { viewModel.buildTx() },
                                onFailure = { msg -> viewModel.setError("Auth failed: $msg") }
                            )
                        } else {
                            // No biometric available — proceed without (device is unprotected)
                            viewModel.buildTx()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Review Transaction")
                }
            }

            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
