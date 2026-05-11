package net.clench.wallet.ui.screens

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import net.clench.wallet.ui.components.CoinkiteTapCardNfcReader
import net.clench.wallet.ui.MainActivity
import net.clench.wallet.ui.components.NfcDispatch
import net.clench.wallet.ui.components.NfcReaderModeFlags
import net.clench.wallet.ui.components.QrScanner
import net.clench.wallet.ui.components.SatscardNfcReader
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.FeeTier
import net.clench.wallet.ui.viewmodel.SweepViewModel

/**
 * Sweep wallet screen: allows sweeping all funds from an external seed phrase
 * into the current wallet's receive address.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SweepScreen(
    walletId: String,
    onBack: () -> Unit,
    viewModel: SweepViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    SecureWindowEffect()
    val context = LocalContext.current
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }

    // Seed phrase input state (kept local for security — never exposed to ViewModel until submit)
    var seedInput by remember { mutableStateOf("") }
    var passphraseInput by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var showPassphraseText by remember { mutableStateOf(false) }
    var wifInput by remember { mutableStateOf("") }
    var sourceType by remember { mutableStateOf(SweepSourceType.SeedPhrase) }
    var showScanner by remember { mutableStateOf(false) }
    var seedError by remember { mutableStateOf<String?>(null) }
    var wifError by remember { mutableStateOf<String?>(null) }
    var satscardReaderActive by remember { mutableStateOf(false) }
    var satscardSweepReaderActive by remember { mutableStateOf(false) }
    var satscardStatus by remember { mutableStateOf<String?>(null) }
    var satscardError by remember { mutableStateOf<String?>(null) }
    var satscardCvcInput by remember { mutableStateOf("") }
    var satscardSweepConfirmed by remember { mutableStateOf(false) }
    var pendingSatscardCvc by remember { mutableStateOf<CharArray?>(null) }
    val satscardNfcProcessing = remember { AtomicBoolean(false) }
    val wifFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() }
                if (text.isNullOrBlank()) {
                    wifError = "Selected file was empty"
                } else {
                    sourceType = SweepSourceType.WifPrivateKey
                    wifInput = text.trim()
                    wifError = null
                    viewModel.clearSourceValidation()
                }
            } catch (e: Exception) {
                wifError = "Could not read file: ${e.message}"
            }
        }
    }

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    fun processSatscardNfcTag(tag: Tag, hostActivity: Activity, sweep: Boolean) {
        if (!satscardNfcProcessing.compareAndSet(false, true)) return
        try {
            if (sweep) {
                val cvc = pendingSatscardCvc ?: error("Enter the SATSCARD spend code before sweeping")
                val result = SatscardNfcReader.unsealCurrentSlot(
                    tag = tag,
                    cvc = cvc,
                    expectedTestnet = uiState.isTestnet
                )
                hostActivity.runOnUiThread {
                    satscardStatus = "${result.summary}. Building sweep transaction..."
                    satscardError = null
                    satscardSweepReaderActive = false
                    pendingSatscardCvc = null
                    viewModel.sweepSatscardPrivateKey(result.privateKey, result.isTestnet)
                }
            } else {
                val status = CoinkiteTapCardNfcReader.readStatus(tag)
                hostActivity.runOnUiThread {
                    if (status.isSatscard) {
                        satscardStatus = "${status.summary()}. Enter the spend code to unseal the active slot and sweep it."
                        satscardError = null
                    } else {
                        val cardName = if (status.isTapsigner) "TAPSIGNER" else "Coinkite card"
                        satscardStatus = null
                        satscardError = "$cardName detected; this sweep tool only supports SATSCARD here."
                    }
                    satscardReaderActive = false
                }
            }
        } catch (e: Exception) {
            hostActivity.runOnUiThread {
                satscardStatus = null
                satscardError = e.message ?: "SATSCARD NFC read failed"
                satscardReaderActive = false
                satscardSweepReaderActive = false
                pendingSatscardCvc?.fill('0')
                pendingSatscardCvc = null
            }
        } finally {
            satscardNfcProcessing.set(false)
        }
    }

    DisposableEffect(satscardReaderActive, satscardSweepReaderActive, pendingSatscardCvc) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if ((!satscardReaderActive && !satscardSweepReaderActive) || hostActivity == null || adapter == null || !adapter.isEnabled) {
            onDispose { }
        } else {
            adapter.enableReaderMode(
                hostActivity,
                { tag ->
                    processSatscardNfcTag(tag, hostActivity, satscardSweepReaderActive)
                },
                NfcReaderModeFlags.coinkiteTap,
                null
            )
            onDispose { adapter.disableReaderMode(hostActivity) }
        }
    }

    DisposableEffect(activity, nfcAdapter) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if (hostActivity != null && adapter != null && adapter.isEnabled) {
            runCatching { NfcDispatch.enableCoinkiteForegroundDispatch(hostActivity, adapter) }
            onDispose {
                NfcDispatch.disableForegroundDispatch(hostActivity, adapter)
            }
        } else {
            onDispose { }
        }
    }

    val mainActivity = activity as? MainActivity
    LaunchedEffect(mainActivity, satscardSweepReaderActive, pendingSatscardCvc) {
        val hostActivity = activity
        if (hostActivity == null || mainActivity == null) return@LaunchedEffect
        mainActivity.nfcTagFlow.collect { tag ->
            processSatscardNfcTag(tag, hostActivity, satscardSweepReaderActive)
        }
    }

    // Navigate back on successful broadcast
    LaunchedEffect(uiState.broadcastTxid) {
        if (uiState.broadcastTxid != null) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sweep to Wallet") },
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
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Sweep External Wallet", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Enter a source seed phrase, or scan/paste a WIF private key from a paper wallet or unsealed OpenDime. All confirmed funds will be sent to this wallet's receive address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Destination address
            OutlinedTextField(
                value = uiState.destinationAddress,
                onValueChange = { viewModel.setDestinationAddress(it) },
                label = { Text("Destination address (this wallet)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Security warning
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Text(
                    "⚠ Source key material is used in memory only. Char buffers are zeroed " +
                    "immediately after the sweep transaction is broadcast.",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5D4037)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SATSCARD NFC", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Read SATSCARD status, or enter the 6-digit spend code printed on the back of the SATSCARD to unseal the active slot and sweep its confirmed funds to this wallet. Unsealing is irreversible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = satscardCvcInput,
                        onValueChange = {
                            satscardCvcInput = it.take(32)
                            satscardError = null
                        },
                        label = { Text("SATSCARD spend code") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = satscardSweepConfirmed,
                            onCheckedChange = { satscardSweepConfirmed = it }
                        )
                        Text(
                            "I understand this will permanently unseal the active slot.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        nfcAdapter == null -> Text(
                            "This phone does not report NFC hardware.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        !nfcAdapter.isEnabled -> Text(
                            "NFC is off in Android settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        satscardStatus = "Ready for NFC status. Hold SATSCARD against the phone."
                                        satscardError = null
                                        satscardReaderActive = true
                                    },
                                    enabled = !satscardReaderActive && !satscardSweepReaderActive && !uiState.isSweeping
                                ) {
                                    if (satscardReaderActive) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Reading...")
                                    } else {
                                        Text("Read Status")
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (satscardCvcInput.length !in 6..32) {
                                            satscardError = "Enter the 6-digit SATSCARD spend code"
                                            return@Button
                                        }
                                        pendingSatscardCvc?.fill('0')
                                        pendingSatscardCvc = satscardCvcInput.toCharArray()
                                        satscardCvcInput = ""
                                        satscardStatus = "Ready to unseal. Hold SATSCARD against the phone."
                                        satscardError = null
                                        satscardSweepReaderActive = true
                                    },
                                    enabled = satscardSweepConfirmed &&
                                        satscardCvcInput.length in 6..32 &&
                                        !satscardReaderActive &&
                                        !satscardSweepReaderActive &&
                                        !uiState.isSweeping
                                ) {
                                    if (satscardSweepReaderActive || uiState.isSweeping) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sweeping...")
                                    } else {
                                        Text("Unseal and Sweep")
                                    }
                                }
                            }
                            if (satscardReaderActive || satscardSweepReaderActive) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        satscardReaderActive = false
                                        satscardSweepReaderActive = false
                                        satscardStatus = null
                                        satscardError = null
                                        pendingSatscardCvc?.fill('0')
                                        pendingSatscardCvc = null
                                    }
                                ) { Text("Cancel NFC") }
                            }
                        }
                    }
                    satscardStatus?.let { status ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    satscardError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Source seed phrase
            Text("Source", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = sourceType == SweepSourceType.SeedPhrase,
                    onClick = {
                        sourceType = SweepSourceType.SeedPhrase
                        seedError = null
                        wifError = null
                        viewModel.clearSourceValidation()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Seed phrase") }
                SegmentedButton(
                    selected = sourceType == SweepSourceType.WifPrivateKey,
                    onClick = {
                        sourceType = SweepSourceType.WifPrivateKey
                        seedError = null
                        wifError = null
                        viewModel.clearSourceValidation()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("WIF key") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (sourceType == SweepSourceType.SeedPhrase) {
                OutlinedTextField(
                    value = seedInput,
                    onValueChange = {
                        seedInput = it
                        seedError = null
                        viewModel.clearSourceValidation()
                    },
                    label = { Text("Seed phrase (12 or 24 words)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("word1 word2 word3…") },
                    isError = seedError != null
                )
                seedError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Optional passphrase
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = showPassphrase,
                        onCheckedChange = {
                            showPassphrase = it
                            viewModel.clearSourceValidation()
                        }
                    )
                    Text("BIP39 passphrase (optional)")
                }

                AnimatedVisibility(visible = showPassphrase) {
                    OutlinedTextField(
                        value = passphraseInput,
                        onValueChange = {
                            passphraseInput = it
                            viewModel.clearSourceValidation()
                        },
                        label = { Text("Passphrase") },
                        visualTransformation = if (showPassphraseText) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            TextButton(onClick = { showPassphraseText = !showPassphraseText }) {
                                Text(if (showPassphraseText) "Hide" else "Show")
                            }
                        }
                    )
                }
            } else {
                OutlinedTextField(
                    value = wifInput,
                    onValueChange = {
                        wifInput = it
                        wifError = null
                        viewModel.clearSourceValidation()
                    },
                    label = { Text("WIF private key") },
                    placeholder = { Text("Scan a paper wallet or OpenDime private-key QR") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = wifError != null
                )
                wifError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { showScanner = true }) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan QR")
                    }
                    OutlinedButton(onClick = { wifFileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) {
                        Text("Load private-key.txt")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fee section
            Text("Fee Rate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            val estimates = uiState.feeEstimates
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.ECONOMY,
                    onClick = { viewModel.selectFeeTier(FeeTier.ECONOMY) },
                    label = {
                        Column {
                            Text("Economy", fontWeight = FontWeight.Bold)
                            if (estimates != null) Text("${estimates.economy.toInt()} sat/vB", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.STANDARD,
                    onClick = { viewModel.selectFeeTier(FeeTier.STANDARD) },
                    label = {
                        Column {
                            Text("Standard", fontWeight = FontWeight.Bold)
                            if (estimates != null) Text("${estimates.standard.toInt()} sat/vB", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.PRIORITY,
                    onClick = { viewModel.selectFeeTier(FeeTier.PRIORITY) },
                    label = {
                        Column {
                            Text("Priority", fontWeight = FontWeight.Bold)
                            if (estimates != null) Text("${estimates.priority.toInt()} sat/vB", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
                FilterChip(
                    selected = uiState.selectedFeeTier == FeeTier.CUSTOM,
                    onClick = { viewModel.selectFeeTier(FeeTier.CUSTOM) },
                    label = { Text("Custom") }
                )
            }

            AnimatedVisibility(visible = uiState.selectedFeeTier == FeeTier.CUSTOM) {
                OutlinedTextField(
                    value = uiState.feeRate,
                    onValueChange = { viewModel.setFeeRate(it) },
                    label = { Text("Fee rate (sat/vB)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            if (uiState.selectedFeeTier != FeeTier.CUSTOM) {
                Text(
                    "Fee rate: ${uiState.feeRate} sat/vB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Validate seed button (check balance before sweeping)
            if (!uiState.seedValidated) {
                val words = seedInput.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                val isValidWordCount = words.size == 12 || words.size == 24
                val isWifReady = wifInput.isNotBlank()

                Button(
                    onClick = {
                        if (sourceType == SweepSourceType.SeedPhrase && !isValidWordCount) {
                            seedError = "Enter 12 or 24 words"
                            return@Button
                        }
                        if (sourceType == SweepSourceType.WifPrivateKey && !isWifReady) {
                            wifError = "Enter or scan a WIF private key"
                            return@Button
                        }
                        if (sourceType == SweepSourceType.SeedPhrase) {
                            val mnemonic = seedInput.trim().toCharArray()
                            val passphrase = if (showPassphrase && passphraseInput.isNotBlank())
                                passphraseInput.toCharArray() else null
                            // Note: chars zeroed inside ViewModel after use
                            viewModel.validateSeedAndFetchBalance(mnemonic, passphrase)
                        } else {
                            viewModel.validateWifAndFetchBalance(wifInput.toCharArray())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoadingBalance && (
                        (sourceType == SweepSourceType.SeedPhrase && isValidWordCount) ||
                            (sourceType == SweepSourceType.WifPrivateKey && isWifReady)
                        )
                ) {
                    if (uiState.isLoadingBalance) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking balance…")
                    } else {
                        Text("Check Balance")
                    }
                }
            } else {
                // Show found balance and sweep button
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Source Wallet Balance", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Confirmed: ${uiState.sourceBalanceSat} sats",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.sourcePendingSat > 0) {
                            Text(
                                "Pending: ${uiState.sourcePendingSat} sats (not swept)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.sourceBalanceSat > 0) {
                    Button(
                        onClick = {
                            if (sourceType == SweepSourceType.SeedPhrase) {
                                val words = seedInput.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                                if (words.size != 12 && words.size != 24) {
                                    seedError = "Seed phrase was cleared — re-enter it"
                                    return@Button
                                }
                                val mnemonic = seedInput.trim().toCharArray()
                                val passphrase = if (showPassphrase && passphraseInput.isNotBlank())
                                    passphraseInput.toCharArray() else null
                                seedInput = ""
                                passphraseInput = ""
                                viewModel.sweep(mnemonic, passphrase)
                            } else {
                                if (wifInput.isBlank()) {
                                    wifError = "WIF private key was cleared — re-enter it"
                                    return@Button
                                }
                                val wif = wifInput.toCharArray()
                                wifInput = ""
                                viewModel.sweepWif(wif)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSweeping
                    ) {
                        if (uiState.isSweeping) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sweeping…")
                        } else {
                            Text("Sweep ${uiState.sourceBalanceSat} sats → This Wallet")
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            "No confirmed funds found in source wallet.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    AnimatedVisibility(visible = showScanner) {
        QrScanner(
            onResult = { result ->
                wifInput = result
                wifError = null
                sourceType = SweepSourceType.WifPrivateKey
                viewModel.clearSourceValidation()
                showScanner = false
            },
            onCancel = { showScanner = false }
        )
    }
}

private enum class SweepSourceType {
    SeedPhrase,
    WifPrivateKey
}
