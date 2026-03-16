package net.clench.wallet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.components.HardwareWalletPickerSheet
import net.clench.wallet.ui.util.BiometricHelper
import net.clench.wallet.ui.viewmodel.FeeTier
import net.clench.wallet.ui.viewmodel.SendViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
                .verticalScroll(rememberScrollState())
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

            Spacer(modifier = Modifier.height(16.dp))

            // Fee estimation tiers
            Text("Fee Rate", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.isEstimatingFees) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Estimating fees…", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            val estimates = uiState.feeEstimates

            // Fee tier chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Economy
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.ECONOMY,
                    onClick = { viewModel.selectFeeTier(FeeTier.ECONOMY) },
                    label = {
                        Column {
                            Text("Economy", fontWeight = FontWeight.Bold)
                            if (estimates != null) {
                                Text(
                                    "~60 min · ${estimates.economy.toInt()} sat/vB",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                )

                // Standard
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.STANDARD,
                    onClick = { viewModel.selectFeeTier(FeeTier.STANDARD) },
                    label = {
                        Column {
                            Text("Standard", fontWeight = FontWeight.Bold)
                            if (estimates != null) {
                                Text(
                                    "~30 min · ${estimates.standard.toInt()} sat/vB",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                )

                // Priority
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.PRIORITY,
                    onClick = { viewModel.selectFeeTier(FeeTier.PRIORITY) },
                    label = {
                        Column {
                            Text("Priority", fontWeight = FontWeight.Bold)
                            if (estimates != null) {
                                Text(
                                    "~10 min · ${estimates.priority.toInt()} sat/vB",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                )

                // Custom
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.CUSTOM,
                    onClick = { viewModel.selectFeeTier(FeeTier.CUSTOM) },
                    label = { Text("Custom") }
                )
            }

            // Custom fee rate input
            AnimatedVisibility(visible = uiState.selectedFeeTier == FeeTier.CUSTOM) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.feeRate,
                        onValueChange = { viewModel.setFeeRate(it) },
                        label = { Text("Fee rate (sat/vB)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            // Show current effective fee rate when not custom
            if (uiState.selectedFeeTier != FeeTier.CUSTOM) {
                Text(
                    "Fee rate: ${uiState.feeRate} sat/vB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // High relative fee warning
            val amountSat = uiState.amountSat.toLongOrNull() ?: 0L
            val feeRateFloat = uiState.feeRate.toFloatOrNull() ?: 0f
            // Rough fee estimate: typical tx is ~140 vB
            val estimatedFeeSat = (feeRateFloat * 140).toLong()
            if (amountSat > 0 && estimatedFeeSat > 0) {
                val feePercent = (estimatedFeeSat.toDouble() / amountSat.toDouble()) * 100
                if (feePercent > 5) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3E0)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "⚠️ Fee is ~${String.format("%.1f", feePercent)}% of the amount (~$estimatedFeeSat sats). " +
                            "Consider using a lower fee tier for small transactions.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5D4037)
                        )
                    }
                }
            }

            uiState.feeEstimateError?.let { error ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

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
                        // R7-7: Only show biometric if the setting is enabled
                        if (uiState.biometricForSendEnabled && fragmentActivity != null && BiometricHelper.canAuthenticate(context)) {
                            BiometricHelper.authenticate(
                                activity = fragmentActivity,
                                title = "Authenticate to send Bitcoin",
                                subtitle = "Verify your identity to sign this transaction",
                                onSuccess = { viewModel.buildTx() },
                                onFailure = { msg -> viewModel.setError("Auth failed: $msg") }
                            )
                        } else {
                            // Biometric disabled or not available — proceed without
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
