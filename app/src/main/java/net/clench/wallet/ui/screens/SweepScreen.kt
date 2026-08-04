package net.clench.wallet.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import android.nfc.NfcAdapter
import android.nfc.Tag
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
import net.clench.wallet.ui.components.SecureBip39WordEntry
import net.clench.wallet.ui.components.TransactionReviewCard
import net.clench.wallet.ui.components.FeeSafetySummary
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.util.BiometricHelper
import net.clench.wallet.ui.picker.LocalPickerRoundTripHost
import net.clench.wallet.ui.picker.PickerDestination
import net.clench.wallet.ui.picker.PickerPurpose
import net.clench.wallet.ui.picker.PickerRequest
import net.clench.wallet.ui.viewmodel.FeeTier
import net.clench.wallet.ui.viewmodel.SweepViewModel
import net.clench.wallet.ui.viewmodel.SweepSeedScriptType
import net.clench.wallet.ui.viewmodel.SweepWifScriptType
import net.clench.wallet.security.InputLimits
import net.clench.wallet.security.readTextBounded

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
    val pickerHost = LocalPickerRoundTripHost.current
    val pickerResume by pickerHost.pickerResume.collectAsState()
    val pickerDestination = remember(walletId) { PickerDestination.Sweep(walletId) }
    val activity = context as? Activity
    val fragmentActivity = activity as? FragmentActivity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }

    // Seed phrase input state (kept local for security — never exposed to ViewModel until submit)
    var seedWords by remember { mutableStateOf(emptyList<String>()) }
    var expectedSeedWordCount by remember { mutableIntStateOf(12) }
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
    var wizardStep by remember { mutableStateOf(SweepWizardStep.Source) }
    val satscardNfcProcessing = remember { AtomicBoolean(false) }
    LaunchedEffect(pickerResume?.requestId) {
        if (pickerResume?.purpose != PickerPurpose.SWEEP_WIF_IMPORT) return@LaunchedEffect
        val result = pickerHost.consumePickerResult(
            PickerPurpose.SWEEP_WIF_IMPORT,
            pickerDestination
        ) ?: return@LaunchedEffect
        result.uri?.let { encodedUri ->
            try {
                val uri = Uri.parse(encodedUri)
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.readTextBounded(InputLimits.SECRET_TEXT_CHARS)
                }
                if (text.isNullOrBlank()) {
                    wifError = "Selected file was empty"
                } else {
                    sourceType = SweepSourceType.WifPrivateKey
                    wifInput = text.trim()
                    wifError = null
                    if (wizardStep == SweepWizardStep.Source) viewModel.clearSourceValidation()
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
                val result = try {
                    SatscardNfcReader.unsealCurrentSlot(
                        tag = tag,
                        cvc = cvc,
                        expectedTestnet = uiState.isTestnet
                    )
                } finally {
                    cvc.fill('0')
                }
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

    LaunchedEffect(uiState.seedValidated) {
        if (uiState.seedValidated && wizardStep == SweepWizardStep.Source) {
            wizardStep = SweepWizardStep.Discovery
        }
    }

    LaunchedEffect(uiState.transactionReview?.txid) {
        if (uiState.transactionReview != null) wizardStep = SweepWizardStep.Review
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

            SweepWizardHeader(current = wizardStep)
            Spacer(modifier = Modifier.height(16.dp))

            // Destination address
            if (wizardStep == SweepWizardStep.Destination) {
            OutlinedTextField(
                value = uiState.destinationAddress,
                onValueChange = { viewModel.setDestinationAddress(it) },
                label = { Text("Sweep destination") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (uiState.destinationAddress.trim() != uiState.defaultDestinationAddress &&
                uiState.destinationAddress.isNotBlank()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("External destination", fontWeight = FontWeight.Bold)
                        Text(
                            "This is not the receive address generated for the selected wallet. Verify it independently before continuing.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = uiState.externalDestinationConfirmed,
                                onCheckedChange = viewModel::confirmExternalDestination
                            )
                            Text("I verified this external address", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (uiState.destinationAddress.isNotBlank()) {
                Text(
                    "Verified destination: this wallet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val destinationReady = SweepWizardPolicy.destinationReady(
                destinationAddress = uiState.destinationAddress,
                defaultDestinationAddress = uiState.defaultDestinationAddress,
                externalDestinationConfirmed = uiState.externalDestinationConfirmed
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { wizardStep = SweepWizardStep.Fee },
                enabled = destinationReady,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Continue to fee") }
            TextButton(onClick = { wizardStep = SweepWizardStep.Discovery }) { Text("Back to discovery") }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Security warning
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Text(
                    "⚠ Source key material is used in memory only. Char buffers are zeroed " +
                    "immediately after the signed sweep transaction is prepared.",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5D4037)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            uiState.transactionReview?.let { review ->
                TransactionReviewCard(
                    review = review,
                    title = "Review signed sweep",
                    requiresHighFeeConfirmation = uiState.requiresHighFeeConfirmation,
                    highFeeAcknowledged = uiState.highFeeAcknowledged,
                    onAcknowledgeHighFee = viewModel::acknowledgeHighFee
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = viewModel::broadcastPreparedSweep,
                    enabled = !uiState.isBroadcasting &&
                        (!uiState.requiresHighFeeConfirmation || uiState.highFeeAcknowledged),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isBroadcasting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Broadcasting…")
                    } else {
                        Text("Broadcast reviewed sweep")
                    }
                }
                OutlinedButton(
                    onClick = {
                        viewModel.discardPreparedSweep()
                        wizardStep = SweepWizardStep.Fee
                    },
                    enabled = !uiState.isBroadcasting,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Discard prepared transaction") }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (wizardStep == SweepWizardStep.Source) {
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
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "If you unseal SATSCARD slot 1, stop using the printed QR address for future receiving. Use Clench's verified active-slot address instead.",
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
                                        val armUnsealReader: () -> Unit = {
                                            pendingSatscardCvc?.fill('0')
                                            pendingSatscardCvc = satscardCvcInput.toCharArray()
                                            satscardCvcInput = ""
                                            satscardStatus = "Authenticated. Hold SATSCARD against the phone to unseal it."
                                            satscardError = null
                                            satscardSweepReaderActive = true
                                        }
                                        when {
                                            !uiState.biometricForSendEnabled -> armUnsealReader()
                                            fragmentActivity == null || !BiometricHelper.canAuthenticate(context) ->
                                                viewModel.setError(BiometricHelper.authenticationUnavailableGuidance())
                                            else -> {
                                                BiometricHelper.authenticate(
                                                    activity = fragmentActivity,
                                                    title = "Authenticate SATSCARD unseal",
                                                    subtitle = "Verify your identity before permanently unsealing and signing the sweep",
                                                    onSuccess = {
                                                        armUnsealReader()
                                                    },
                                                    onFailure = { message ->
                                                        viewModel.setError("Authentication failed: $message")
                                                    },
                                                    onCancel = { }
                                                )
                                            }
                                        }
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Source seed phrase
            if (wizardStep == SweepWizardStep.Source) {
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

            Text("Source address type", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            if (sourceType == SweepSourceType.SeedPhrase) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        SweepSeedScriptType.LEGACY to "Legacy (BIP44)",
                        SweepSeedScriptType.NESTED_SEGWIT to "Nested SegWit (BIP49)",
                        SweepSeedScriptType.NATIVE_SEGWIT to "Native SegWit (BIP84)",
                        SweepSeedScriptType.TAPROOT to "Taproot (BIP86)"
                    ).forEach { (type, label) ->
                        FilterChip(
                            selected = uiState.seedScriptType == type,
                            onClick = { viewModel.setSeedScriptType(type) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.seedAccount.toString(),
                    onValueChange = { raw ->
                        raw.filter(Char::isDigit).take(3).toUIntOrNull()?.let { account ->
                            if (account <= 100u) viewModel.setSeedAccount(account)
                        }
                    },
                    label = { Text("BIP account index (0–100)") },
                    supportingText = { Text("Most wallets use account 0. Check other accounts you previously used.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = uiState.wifScriptType == SweepWifScriptType.LEGACY,
                        onClick = { viewModel.setWifScriptType(SweepWifScriptType.LEGACY) },
                        label = { Text("Legacy P2PKH") }
                    )
                    FilterChip(
                        selected = uiState.wifScriptType == SweepWifScriptType.NESTED_SEGWIT,
                        onClick = { viewModel.setWifScriptType(SweepWifScriptType.NESTED_SEGWIT) },
                        label = { Text("Nested SegWit") }
                    )
                    FilterChip(
                        selected = uiState.wifScriptType == SweepWifScriptType.NATIVE_SEGWIT,
                        onClick = { viewModel.setWifScriptType(SweepWifScriptType.NATIVE_SEGWIT) },
                        label = { Text("Native SegWit") }
                    )
                }
            }

            Text(
                "Clench scans the selected address type and account with a 20-address gap. Check every type and account you may have used before concluding the source is empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (wizardStep == SweepWizardStep.Source || wizardStep == SweepWizardStep.Fee) {
            if (sourceType == SweepSourceType.SeedPhrase) {
                SecureBip39WordEntry(
                    words = seedWords,
                    expectedWordCount = expectedSeedWordCount,
                    onWordsChange = {
                        seedWords = it
                        seedError = null
                        if (wizardStep == SweepWizardStep.Source) viewModel.clearSourceValidation()
                    },
                    onExpectedWordCountChange = {
                        expectedSeedWordCount = it
                        if (seedWords.size > it) seedWords = seedWords.take(it)
                        seedError = null
                        if (wizardStep == SweepWizardStep.Source) viewModel.clearSourceValidation()
                    },
                    title = if (uiState.seedValidated) "Re-enter source words to authorize signing" else "Enter source words securely"
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
                            if (wizardStep == SweepWizardStep.Source) viewModel.clearSourceValidation()
                        }
                    )
                    Text("BIP39 passphrase (optional)")
                }

                AnimatedVisibility(visible = showPassphrase) {
                    OutlinedTextField(
                        value = passphraseInput,
                        onValueChange = {
                            passphraseInput = it
                            if (wizardStep == SweepWizardStep.Source) viewModel.clearSourceValidation()
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
                        if (wizardStep == SweepWizardStep.Source) viewModel.clearSourceValidation()
                    },
                    label = { Text("WIF private key") },
                    placeholder = { Text("Scan a paper wallet or OpenDime private-key QR") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false
                    ),
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
                    OutlinedButton(
                        onClick = {
                            if (!pickerHost.launchPicker(PickerRequest.SweepWifImport(walletId))) {
                                wifError = "Unlock Clench before choosing a WIF file"
                            }
                        }
                    ) {
                        Text("Load private-key.txt")
                    }
                }
            }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fee section
            if (wizardStep == SweepWizardStep.Fee) {
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
            FeeSafetySummary(
                feeRateText = uiState.feeRate,
                priorityEstimate = estimates?.priority?.toDouble(),
                modifier = Modifier.padding(top = 8.dp)
            )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Source: validate and discover the selected wallet before choosing a destination.
            if (wizardStep == SweepWizardStep.Source && !uiState.seedValidated) {
                val isValidWordCount = SweepWizardPolicy.seedReady(seedWords.size, expectedSeedWordCount)
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
                            val mnemonic = seedWords.joinToString(" ").toCharArray()
                            val passphrase = if (showPassphrase && passphraseInput.isNotBlank())
                                passphraseInput.toCharArray() else null
                            seedWords = emptyList()
                            passphraseInput = ""
                            // Char arrays are zeroed inside ViewModel after use.
                            viewModel.validateSeedAndFetchBalance(mnemonic, passphrase)
                        } else {
                            val wif = wifInput.toCharArray()
                            wifInput = ""
                            viewModel.validateWifAndFetchBalance(wif)
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
            }

            if (wizardStep == SweepWizardStep.Discovery && uiState.seedValidated) {
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
                        onClick = { wizardStep = SweepWizardStep.Destination },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Continue with ${uiState.sourceBalanceSat} sats") }
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
                TextButton(
                    onClick = {
                        viewModel.clearSourceValidation()
                        wizardStep = SweepWizardStep.Source
                    }
                ) { Text("Try a different source or account") }
            }

            if (wizardStep == SweepWizardStep.Fee && uiState.seedValidated) {
                val sourceReady = when (sourceType) {
                    SweepSourceType.SeedPhrase -> SweepWizardPolicy.seedReady(seedWords.size, expectedSeedWordCount)
                    SweepSourceType.WifPrivateKey -> wifInput.isNotBlank()
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Signing authorization", fontWeight = FontWeight.Bold)
                        Text(
                            "The discovery secret was cleared from memory. Re-enter it above; Clench will verify it against the discovered source, prepare the transaction, then clear it again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Destination: ${uiState.destinationAddress}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Selected rate: ${uiState.feeRate} sat/vB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val prepareSweep: () -> Unit = {
                            when (sourceType) {
                                SweepSourceType.SeedPhrase -> {
                                    if (!sourceReady) {
                                        seedError = "Re-enter all $expectedSeedWordCount source words"
                                    } else {
                                        val mnemonic = seedWords.joinToString(" ").toCharArray()
                                        val passphrase = if (showPassphrase && passphraseInput.isNotBlank()) {
                                            passphraseInput.toCharArray()
                                        } else null
                                        seedWords = emptyList()
                                        passphraseInput = ""
                                        viewModel.sweep(mnemonic, passphrase)
                                    }
                                }
                                SweepSourceType.WifPrivateKey -> {
                                    if (!sourceReady) {
                                        wifError = "Re-enter or scan the source WIF key"
                                    } else {
                                        val wif = wifInput.toCharArray()
                                        wifInput = ""
                                        viewModel.sweepWif(wif)
                                    }
                                }
                            }
                        }
                        when {
                            !uiState.biometricForSendEnabled -> prepareSweep()
                            fragmentActivity == null || !BiometricHelper.canAuthenticate(context) ->
                                viewModel.setError(BiometricHelper.authenticationUnavailableGuidance())
                            else -> {
                                BiometricHelper.authenticate(
                                    activity = fragmentActivity,
                                    title = "Authenticate sweep",
                                    subtitle = "Verify your identity before signing the reviewed sweep",
                                    onSuccess = {
                                        prepareSweep()
                                    },
                                    onFailure = { message ->
                                        viewModel.setError("Authentication failed: $message")
                                    },
                                    onCancel = { }
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sourceReady && !uiState.isSweeping
                ) {
                    if (uiState.isSweeping) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Preparing…")
                    } else {
                        Text("Prepare transaction for review")
                    }
                }
                TextButton(onClick = { wizardStep = SweepWizardStep.Destination }) {
                    Text("Back to destination")
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
                if (wizardStep == SweepWizardStep.Source) viewModel.clearSourceValidation()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SweepWizardHeader(current: SweepWizardStep) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Step ${current.ordinal + 1} of ${SweepWizardStep.entries.size}: ${current.label}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SweepWizardStep.entries.forEach { step ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (step == current) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        if (step.ordinal < current.ordinal) "✓ ${step.label}" else step.label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (step == current) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
