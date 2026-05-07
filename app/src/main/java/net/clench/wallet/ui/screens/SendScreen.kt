package net.clench.wallet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import net.clench.wallet.ui.viewmodel.AmountUnit
import net.clench.wallet.ui.viewmodel.FeeTier
import net.clench.wallet.ui.viewmodel.RecipientEntry
import net.clench.wallet.ui.viewmodel.SendViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SendScreen(
    walletId: String,
    onBack: () -> Unit,
    utxoOutpoint: String? = null,  // format: "txid:vout"
    selectedUtxos: String? = null,
    cpfpMode: Boolean = false,
    onNavigateHardwarePsbt: ((walletId: String, psbtBase64: String, deviceType: HardwareWalletType) -> Unit)? = null,
    onNavigatePhonePsbt: ((walletId: String, psbtBase64: String) -> Unit)? = null,
    viewModel: SendViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showHardwareWalletPicker by remember { mutableStateOf(false) }
    var showWatchOnlySheet by remember { mutableStateOf(false) }

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

    val focusManager = LocalFocusManager.current
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        try {
            result.contents?.let { scanned ->
                // Pass full BIP-21 URI to setAddress — it handles pj= and amount parsing
                viewModel.setAddress(scanned)
                // Dismiss keyboard after scan fills the address
                focusManager.clearFocus()
            }
        } finally {
            (context as? net.clench.wallet.ui.MainActivity)?.suppressPassphraseLock = false
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
                setOrientationLocked(true)
                setCaptureActivity(net.clench.wallet.ui.PortraitCaptureActivity::class.java)
            }
            // Suppress biometric lock and passphrase lock when returning from scanner
            (context as? net.clench.wallet.ui.MainActivity)?.let {
                it.suppressLockOnResume = true
                it.suppressPassphraseLock = true
            }
            scanLauncher.launch(options)
        } else {
            viewModel.setError("Camera permission required to scan QR codes")
        }
    }

    LaunchedEffect(walletId) {
        // utxoOutpoint may be a single "txid:vout" OR a comma-separated list of "txid:vout" outpoints
        // (coin control passes multiple outpoints through the same route param)
        val outpointList = utxoOutpoint?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val isSingle = outpointList.size == 1 && !outpointList.first().contains(",")
        val isMulti = outpointList.size > 1

        val utxoTxid = if (isSingle) outpointList.first().split(":").getOrNull(0) else null
        val utxoVout = if (isSingle) outpointList.first().split(":").getOrNull(1)?.toIntOrNull() else null

        viewModel.load(
            walletId,
            preselectedUtxoTxid = utxoTxid,
            preselectedUtxoVout = utxoVout,
            preselectedOutpoints = if (isMulti) outpointList else emptyList()
        )
        if (utxoTxid != null) {
            viewModel.setUtxo(utxoTxid, utxoVout)
        }
        if (isMulti) {
            viewModel.setSelectedUtxos(outpointList)
        }
        if (!selectedUtxos.isNullOrBlank()) {
            viewModel.setSelectedUtxos(selectedUtxos.split(",").filter { it.isNotBlank() })
        }
        if (cpfpMode && outpointList.isNotEmpty()) {
            viewModel.prepareCpfpSend(walletId, outpointList)
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
                val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
                val availableFormatted = when (uiState.amountUnit) {
                    AmountUnit.SATS -> "${fmt.format(uiState.availableBalanceSat)} sats"
                    AmountUnit.BTC -> {
                        val btc = uiState.availableBalanceSat / 100_000_000.0
                        String.format("%.8f", btc).trimEnd('0').trimEnd('.') + " BTC"
                    }
                    AmountUnit.USD -> uiState.btcPriceUsd?.let {
                        "$%.2f".format(uiState.availableBalanceSat / 100_000_000.0 * it) + " USD"
                    } ?: "${fmt.format(uiState.availableBalanceSat)} sats"
                }
                Text(
                    when {
                        uiState.selectedUtxoOutpoints.size > 1 ->
                            "Selected: $availableFormatted (${uiState.selectedUtxoOutpoints.size} UTXOs)"
                        uiState.utxoTxid != null ->
                            "UTXO: $availableFormatted"
                        else ->
                            "Available: $availableFormatted"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Frozen UTXO advisement
            if (uiState.frozenUtxoCount > 0 && uiState.utxoTxid == null && uiState.selectedUtxoOutpoints.isEmpty()) {
                val frozenFmt = java.text.NumberFormat.getNumberInstance()
                Text(
                    "❄\uFE0F ${uiState.frozenUtxoCount} frozen UTXO${if (uiState.frozenUtxoCount > 1) "s" else ""} excluded (${frozenFmt.format(uiState.frozenAmountSat)} sats)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
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

            if (uiState.selectedUtxoOutpoints.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        "Coin control: ${uiState.selectedUtxoOutpoints.size} UTXO(s) selected",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            if (uiState.cpFpMode) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        "CPFP mode: spending the selected unconfirmed output back to this wallet with a higher child fee.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // --- Recipients section ---
            val isBatchMode = uiState.recipients.size > 1

            if (isBatchMode) {
                // Batch mode: dynamic list of recipients
                uiState.recipients.forEachIndexed { index, recipient ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Recipient ${index + 1}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                if (uiState.recipients.size > 1) {
                                    IconButton(
                                        onClick = { viewModel.removeRecipient(index) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text("✕", style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = recipient.address,
                                onValueChange = { viewModel.updateRecipientAddress(index, it) },
                                label = { Text("Bitcoin address") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    if (index == 0) {
                                        TextButton(onClick = {
                                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                        }) { Text("Scan") }
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = recipient.amountSat,
                                onValueChange = { viewModel.updateRecipientAmount(index, it) },
                                label = { Text("Amount (sats)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = recipient.label,
                                onValueChange = { viewModel.updateRecipientLabel(index, it) },
                                label = { Text("Label (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }

                // Total amount display
                val totalSats = viewModel.batchTotalSats()
                if (totalSats > 0) {
                    val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
                    Text(
                        "Total: ${fmt.format(totalSats)} sats (${uiState.recipients.size} recipients)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                TextButton(onClick = { viewModel.addRecipient() }) {
                    Text("+ Add Recipient")
                }
            } else {
                // Single recipient mode (original UI)
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
                uiState.addressVerification?.let { verification ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        verification.displayText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                uiState.addressWarning?.let { warning ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                if (uiState.savedPayees.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Saved payees",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.savedPayees.take(6).forEach { payee ->
                            AssistChip(
                                onClick = { viewModel.selectPayee(payee) },
                                label = { Text(payee.label.take(24)) },
                                trailingIcon = {
                                    Text(
                                        "×",
                                        modifier = Modifier.clickable { viewModel.deletePayee(payee) },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val unitLabel = when (uiState.amountUnit) {
                    AmountUnit.SATS -> "sats"
                    AmountUnit.BTC -> "BTC"
                    AmountUnit.USD -> "USD"
                }
                val amountKeyboardType = when (uiState.amountUnit) {
                    AmountUnit.SATS -> KeyboardType.Number
                    else -> KeyboardType.Decimal
                }
                OutlinedTextField(
                    value = if (uiState.amountUnit == AmountUnit.SATS) uiState.amountSat else uiState.amountDisplay,
                    onValueChange = {
                        if (!uiState.sendMax) {
                            if (uiState.amountUnit == AmountUnit.SATS) viewModel.setAmount(it)
                            else viewModel.setAmountDisplay(it)
                        }
                    },
                    label = { Text("Amount") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !uiState.sendMax,
                    suffix = {
                        TextButton(
                            onClick = { if (!uiState.sendMax) viewModel.cycleAmountUnit() },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(unitLabel, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = amountKeyboardType)
                )
                if (uiState.sendMax) {
                    Text(
                        "Exact amount determined after fees",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } else {
                    Text(
                        when (uiState.amountUnit) {
                            AmountUnit.SATS -> ""
                            AmountUnit.BTC -> uiState.amountSat.toLongOrNull()?.let { "≈ ${java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(it)} sats" } ?: ""
                            AmountUnit.USD -> uiState.amountSat.toLongOrNull()?.let { "≈ ${java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(it)} sats" } ?: ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (viewModel.exceedsUtxoSelection(uiState)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚠ ", style = MaterialTheme.typography.bodyMedium)
                            val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
                            val availableFormatted = when (uiState.amountUnit) {
                                AmountUnit.SATS -> "${fmt.format(uiState.availableBalanceSat)} sats"
                                AmountUnit.BTC -> {
                                    val btc = uiState.availableBalanceSat / 100_000_000.0
                                    String.format("%.8f", btc).trimEnd('0').trimEnd('.') + " BTC"
                                }
                                AmountUnit.USD -> uiState.btcPriceUsd?.let {
                                    "$%.2f".format(uiState.availableBalanceSat / 100_000_000.0 * it)
                                } ?: "${fmt.format(uiState.availableBalanceSat)} sats"
                            }
                            Text(
                                "Exceeds selected UTXO(s) — $availableFormatted available",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // "Add recipient" button to switch to batch mode
                TextButton(onClick = { viewModel.addRecipient() }) {
                    Text("+ Add Recipient")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Send max — disabled in batch mode
            if (!isBatchMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.sendMax,
                        onCheckedChange = { viewModel.setSendMax(it) }
                    )
                    Text(if (uiState.utxoTxid != null || uiState.selectedUtxoOutpoints.isNotEmpty()) "Drain selected UTXO(s)" else "Send max (drain wallet)")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Label / Note (optional) — only in single mode; batch mode has per-recipient labels
            if (!isBatchMode) {
                OutlinedTextField(
                    value = uiState.label,
                    onValueChange = { viewModel.setLabel(it) },
                    label = { Text("Label (optional)") },
                    placeholder = { Text("e.g. Payment to Alice") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.savePayeeAfterSend,
                        onCheckedChange = { viewModel.setSavePayeeAfterSend(it) }
                    )
                    Text("Save recipient to address book")
                }
                TextButton(
                    onClick = { viewModel.saveCurrentPayee() },
                    enabled = uiState.toAddress.isNotBlank()
                ) {
                    Text("Save Payee Now")
                }
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
                // Watch-only wallet: show sheet to choose signing method
                Button(
                    onClick = { if (viewModel.validatePsbtInputs()) showWatchOnlySheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Sign Transaction")
                }
            } else if (uiState.broadcastSuccess) {
                // Transaction broadcast confirmation
                val clipboardManager = LocalClipboardManager.current
                var copiedTxid by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "✓ Transaction Broadcast",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Your transaction has been sent to the network.",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        uiState.broadcastTxid?.let { txid ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Transaction ID",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                txid,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(txid))
                                        copiedTxid = true
                                    }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(txid))
                                        copiedTxid = true
                                    }
                                ) { Text(if (copiedTxid) "Copied ✓" else "Copy TXID") }
                                OutlinedButton(
                                    onClick = {
                                        val mempoolUrl = viewModel.getMempoolUrl()
                                        val url = "$mempoolUrl/tx/$txid"
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        context.startActivity(intent)
                                    }
                                ) { Text("View in Explorer") }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.dismissBroadcastSuccess()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Done") }
            } else if (uiState.txHex != null) {
                Text("Transaction ready to broadcast", color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.broadcast { /* handled by broadcastSuccess state */ } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Broadcast Transaction")
                }
            } else {
                Button(
                    onClick = {
                        // R7-7: Only show biometric if the setting is enabled
                        if (uiState.biometricForSendEnabled && fragmentActivity != null && BiometricHelper.canAuthenticate(context)) {
                            // Suppress passphrase lock while biometric dialog is showing
                            (context as? net.clench.wallet.ui.MainActivity)?.suppressPassphraseLock = true
                            BiometricHelper.authenticate(
                                activity = fragmentActivity,
                                title = "Authenticate to send Bitcoin",
                                subtitle = "Verify your identity to sign this transaction",
                                onSuccess = {
                                    (context as? net.clench.wallet.ui.MainActivity)?.suppressPassphraseLock = false
                                    viewModel.buildTx()
                                },
                                onFailure = { msg ->
                                    (context as? net.clench.wallet.ui.MainActivity)?.suppressPassphraseLock = false
                                    viewModel.setError("Auth failed: $msg")
                                },
                                onCancel = {
                                    (context as? net.clench.wallet.ui.MainActivity)?.suppressPassphraseLock = false
                                },
                                allowUiOnlyFallback = false
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

    // Watch-only send sheet: choose the configured hardware signing path
    if (showWatchOnlySheet) {
        WatchOnlySendSheet(
            onDismiss = { showWatchOnlySheet = false },
            onHardwareWallet = {
                showWatchOnlySheet = false
                showHardwareWalletPicker = true
            },
            onHardwareWalletDirect = { deviceType ->
                // Preferred device selected — skip picker, go straight to PSBT
                showWatchOnlySheet = false
                viewModel.createPsbt { psbtBase64 ->
                    viewModel.storePsbtForNavigation(walletId, psbtBase64, deviceType.name)
                    onNavigateHardwarePsbt?.invoke(walletId, psbtBase64, deviceType)
                }
            },
            onPhoneSigner = {
                showWatchOnlySheet = false
                viewModel.createPsbt { psbtBase64 ->
                    viewModel.storePsbtForNavigation(walletId, psbtBase64, net.clench.wallet.domain.model.PhoneSigner.DEVICE_TYPE)
                    onNavigatePhonePsbt?.invoke(walletId, psbtBase64)
                }
            },
            hasPhoneSigner = uiState.hasPhoneSigner,
            preferredDevice = uiState.preferredHardwareWallet
        )
    }
}
