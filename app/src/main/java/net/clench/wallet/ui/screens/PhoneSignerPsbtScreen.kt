package net.clench.wallet.ui.screens

import android.util.Base64
import android.widget.Toast
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import net.clench.wallet.ui.util.SecureWindowEffect
import net.clench.wallet.ui.util.BiometricHelper
import net.clench.wallet.ui.components.TransactionReviewCard
import net.clench.wallet.ui.viewmodel.PhoneSignerPsbtViewModel
import net.clench.wallet.ui.viewmodel.PsbtPickerPurpose
import net.clench.wallet.ui.picker.LocalPickerRoundTripHost
import net.clench.wallet.ui.picker.PickerDestination
import net.clench.wallet.ui.picker.PickerPurpose
import net.clench.wallet.ui.picker.PickerRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSignerPsbtScreen(
    walletId: String,
    onBack: () -> Unit,
    viewModel: PhoneSignerPsbtViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val fragmentActivity = remember(context) {
        var current: android.content.Context? = context
        while (current != null) {
            if (current is FragmentActivity) return@remember current
            current = (current as? ContextWrapper)?.baseContext
        }
        null
    }
    val uiState by viewModel.uiState.collectAsState()
    SecureWindowEffect()
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelPendingAuthentication() }
    }
    val pickerHost = LocalPickerRoundTripHost.current
    val pickerResume by pickerHost.pickerResume.collectAsState()
    val pickerDestination = pickerResume?.destination as? PickerDestination.PhonePsbt
    val pickerRouteMatches = pickerDestination == null || pickerDestination.walletId == walletId
    val storeData = remember(
        pickerResume?.requestId,
        pickerResume?.cancelled,
        walletId
    ) {
        when {
            pickerResume == null -> viewModel.initFromStore(expectedWalletId = walletId)
            pickerResume?.cancelled == true -> null
            pickerResume?.purpose == PickerPurpose.PHONE_PSBT_EXPORT &&
                pickerDestination != null && pickerRouteMatches -> viewModel.initFromStore(
                    expectedWalletId = walletId,
                    pickerToken = pickerDestination.handoffToken,
                    pickerPurpose = PsbtPickerPurpose.PHONE_EXPORT
                )
            else -> null
        }
    }

    LaunchedEffect(pickerResume?.requestId, pickerResume?.cancelled, pickerRouteMatches, storeData) {
        val resume = pickerResume ?: return@LaunchedEffect
        val destination = resume.destination as? PickerDestination.PhonePsbt
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
        pickerResume?.let { resume ->
            (resume.destination as? PickerDestination.PhonePsbt)?.let { destination ->
                viewModel.cancelDocumentPickerRoundTrip(destination.handoffToken)
            }
            pickerHost.abortPicker(resume.requestId)
        }
        onBack()
    }
    BackHandler(enabled = pickerResume != null, onBack = secureBack)

    LaunchedEffect(pickerResume?.requestId, uiState.signedPsbtBase64) {
        if (pickerResume?.purpose == PickerPurpose.PHONE_PSBT_EXPORT) {
            // The system picker disposed the old signing route. Re-inspect and sign again in
            // this fresh authorized route before consuming the destination URI.
            val destination = pickerDestination ?: return@LaunchedEffect
            if (!pickerRouteMatches || storeData == null || pickerResume?.cancelled == true ||
                uiState.signedPsbtBase64 == null
            ) return@LaunchedEffect
            val result = pickerHost.consumePickerResult(
                PickerPurpose.PHONE_PSBT_EXPORT,
                destination
            )
            if (result?.uri != null) {
                val signed = uiState.signedPsbtBase64
                if (signed != null) {
                    try {
                        val bytes = Base64.decode(signed, Base64.DEFAULT)
                        try {
                            context.contentResolver.openOutputStream(Uri.parse(result.uri))
                                ?.use { it.write(bytes) }
                                ?: error("Could not open output file")
                        } finally {
                            bytes.fill(0)
                        }
                        Toast.makeText(context, "Signed PSBT saved", Toast.LENGTH_SHORT).show()
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
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Signer") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (storeData == null && uiState.psbtBase64.isBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "No PSBT was loaded. Go back and create the transaction again.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                OutlinedButton(onClick = secureBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
                return@Column
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Clench Phone Signer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Clench will add signatures from the encrypted phone signer keys stored for this multisig wallet. If the wallet still needs more signatures, save the signed PSBT and continue with another signer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.txid != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Transaction Broadcast", fontWeight = FontWeight.Bold)
                        Text(uiState.txid ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = secureBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                return@Column
            }

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
                    title = "Verify before phone signing",
                    requiresHighFeeConfirmation = uiState.requiresHighFeeConfirmation,
                    highFeeAcknowledged = uiState.highFeeAcknowledged,
                    onAcknowledgeHighFee = viewModel::acknowledgeHighFee
                )
            }

            if (uiState.signedPsbtBase64 == null) {
                Button(
                    onClick = sign@{
                        val token = viewModel.beginPhoneSigning(walletId) ?: return@sign
                        when {
                            !uiState.biometricForSendEnabled -> viewModel.signWithPhoneKeys(token)
                            fragmentActivity == null || !BiometricHelper.canAuthenticate(context) -> {
                                viewModel.cancelPhoneSigning(token)
                                viewModel.setError(BiometricHelper.authenticationUnavailableGuidance())
                            }
                            else -> {
                                BiometricHelper.authenticate(
                                    activity = fragmentActivity,
                                    title = "Authenticate phone signer",
                                    subtitle = "Verify your identity before wallet keys sign this transaction",
                                    onSuccess = {
                                        viewModel.signWithPhoneKeys(token)
                                    },
                                    onFailure = { message ->
                                        viewModel.cancelPhoneSigning(token)
                                        viewModel.setError("Authentication failed: $message")
                                    },
                                    onCancel = { viewModel.cancelPhoneSigning(token) }
                                )
                            }
                        }
                    },
                    enabled = !uiState.isSigning &&
                        !uiState.isReviewLoading &&
                        uiState.transactionReview != null &&
                        (!uiState.requiresHighFeeConfirmation || uiState.highFeeAcknowledged),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isSigning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Signing...")
                    } else {
                        Text("Sign with Phone Keys")
                    }
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Signed PSBT Ready", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Broadcast only if this policy now has enough signatures. Otherwise save the signed PSBT and continue with the remaining signer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Button(
                    onClick = { viewModel.broadcastIfComplete(walletId) },
                    enabled = !uiState.isBroadcasting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isBroadcasting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Broadcasting...")
                    } else {
                        Text("Broadcast If Complete")
                    }
                }
                OutlinedButton(
                    onClick = {
                        val handoffToken = viewModel.stageForDocumentPicker()
                        if (handoffToken == null) {
                            Toast.makeText(
                                context,
                                "Could not secure the signing hand-off",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (!pickerHost.launchPicker(
                                PickerRequest.PhonePsbtExport(
                                    walletId,
                                    "${walletId.take(8)}_phone_signed.psbt",
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
                ) { Text("Save Signed PSBT") }
            }

            uiState.error?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss") }
                    }
                }
            }

            OutlinedButton(onClick = secureBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
