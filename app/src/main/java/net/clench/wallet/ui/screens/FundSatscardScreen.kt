package net.clench.wallet.ui.screens

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.ui.MainActivity
import net.clench.wallet.ui.components.NfcDispatch
import net.clench.wallet.ui.components.NfcReaderModeFlags
import net.clench.wallet.ui.components.QrCodeImage
import net.clench.wallet.ui.components.SatscardNfcReader
import net.clench.wallet.ui.components.satscardDisplaySlot
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.viewmodel.FundSatscardViewModel
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundSatscardScreen(
    walletId: String,
    onBack: () -> Unit,
    onFundAddress: (address: String, label: String) -> Unit,
    viewModel: FundSatscardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    SecureWindowEffect(enabled = true)

    val context = LocalContext.current
    val activity = context as? Activity
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val nfcProcessing = remember { AtomicBoolean(false) }
    var readReaderActive by remember { mutableStateOf(false) }
    var setupReaderActive by remember { mutableStateOf(false) }
    var spendCodeInput by remember { mutableStateOf("") }
    var setupConfirmed by remember { mutableStateOf(false) }
    var pendingSpendCode by remember { mutableStateOf<CharArray?>(null) }

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    fun processSatscardTag(tag: Tag, hostActivity: Activity, setup: Boolean) {
        if (!nfcProcessing.compareAndSet(false, true)) return
        try {
            val result = if (setup) {
                val spendCode = pendingSpendCode ?: error("Enter the SATSCARD spend code before setup")
                SatscardNfcReader.setupCurrentSlot(
                    tag = tag,
                    cvc = spendCode,
                    expectedTestnet = uiState.isTestnet
                )
            } else {
                SatscardNfcReader.readCurrentSlot(
                    tag = tag,
                    expectedTestnet = uiState.isTestnet
                )
            }
            hostActivity.runOnUiThread {
                viewModel.applySlotResult(result)
                readReaderActive = false
                setupReaderActive = false
                pendingSpendCode?.fill('0')
                pendingSpendCode = null
                if (setup) {
                    spendCodeInput = ""
                    setupConfirmed = false
                }
            }
        } catch (e: Exception) {
            hostActivity.runOnUiThread {
                viewModel.showError(e.message ?: "SATSCARD NFC operation failed")
                readReaderActive = false
                setupReaderActive = false
                pendingSpendCode?.fill('0')
                pendingSpendCode = null
            }
        } finally {
            nfcProcessing.set(false)
        }
    }

    DisposableEffect(readReaderActive, setupReaderActive, pendingSpendCode) {
        val hostActivity = activity
        val adapter = nfcAdapter
        if ((!readReaderActive && !setupReaderActive) || hostActivity == null || adapter == null || !adapter.isEnabled) {
            onDispose { }
        } else {
            adapter.enableReaderMode(
                hostActivity,
                { tag -> processSatscardTag(tag, hostActivity, setupReaderActive) },
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
            onDispose { NfcDispatch.disableForegroundDispatch(hostActivity, adapter) }
        } else {
            onDispose { }
        }
    }

    val mainActivity = activity as? MainActivity
    LaunchedEffect(mainActivity, readReaderActive, setupReaderActive, pendingSpendCode) {
        val hostActivity = activity
        if (hostActivity == null || mainActivity == null || (!readReaderActive && !setupReaderActive)) return@LaunchedEffect
        mainActivity.nfcTagFlow.collect { tag ->
            processSatscardTag(tag, hostActivity, setupReaderActive)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fund SATSCARD") },
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Prepare Active Slot", fontWeight = FontWeight.Bold)
                    Text(
                        "For an unused SATSCARD, enter the 6-digit spend code printed on the back, tap Set Up Active Slot, then tap the card again. Clench will verify the card and show the deposit address before you send funds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Use the address Clench verifies from the card. If SATSCARD slot 1 has ever been unsealed, do not rely on the printed QR for receiving; read the active slot instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val nfcUnavailable = nfcAdapter == null || !nfcAdapter.isEnabled
            if (nfcUnavailable) {
                Text(
                    "NFC is not available or is turned off on this device.",
                    color = MaterialTheme.colorScheme.error
                )
            }

            OutlinedButton(
                onClick = {
                    readReaderActive = true
                    setupReaderActive = false
                    viewModel.setNfcBusy(true)
                },
                enabled = !nfcUnavailable && !uiState.isNfcBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (readReaderActive) "Waiting for SATSCARD..." else "Read Active Slot")
            }

            OutlinedTextField(
                value = spendCodeInput,
                onValueChange = { spendCodeInput = it.filter { ch -> ch.isLetterOrDigit() }.take(32) },
                label = { Text("SATSCARD spend code") },
                placeholder = { Text("6-digit code printed on the back") },
                supportingText = { Text("Required only to set up an unused active slot. Do not enter a TAPSIGNER PIN here.") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isNfcBusy
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = setupConfirmed,
                    onCheckedChange = { setupConfirmed = it },
                    enabled = !uiState.isNfcBusy
                )
                Text(
                    "I am setting up the active SATSCARD slot before funding it.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = {
                    pendingSpendCode?.fill('0')
                    pendingSpendCode = spendCodeInput.toCharArray()
                    setupReaderActive = true
                    readReaderActive = false
                    viewModel.setNfcBusy(true)
                },
                enabled = !nfcUnavailable &&
                    !uiState.isNfcBusy &&
                    setupConfirmed &&
                    spendCodeInput.length >= 6,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (setupReaderActive) "Waiting for SATSCARD..." else "Set Up Active Slot")
            }

            uiState.summary?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (uiState.address.isNotBlank()) {
                val displayAddress = remember(uiState.address) {
                    uiState.address.chunked(12).joinToString("\n")
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Deposit Address", fontWeight = FontWeight.Bold)
                        QrCodeImage(
                            data = viewModel.bip21Uri(),
                            modifier = Modifier.size(220.dp)
                        )
                        Text(
                            displayAddress,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (uiState.isCheckingBalance) {
                                "Checking balance..."
                            } else {
                                "${viewModel.formattedBalance()} sats on this SATSCARD address"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                val label = uiState.slot?.let { "SATSCARD slot ${satscardDisplaySlot(it)}" } ?: "SATSCARD"
                                onFundAddress(uiState.address, label)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Fund from Wallet")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.copyAddress() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Copy")
                            }
                            OutlinedButton(
                                onClick = { viewModel.refreshBalance() },
                                modifier = Modifier.weight(1f),
                                enabled = !uiState.isCheckingBalance
                            ) {
                                Text("Refresh")
                            }
                        }
                    }
                }
            }

            if (uiState.isNfcBusy) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Hold the SATSCARD against the phone until Clench finishes reading it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
