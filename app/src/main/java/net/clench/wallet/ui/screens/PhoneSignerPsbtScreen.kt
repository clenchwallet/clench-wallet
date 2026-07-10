package net.clench.wallet.ui.screens

import android.util.Base64
import android.widget.Toast
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import net.clench.wallet.ui.viewmodel.PhoneSignerPsbtViewModel

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
    val storeData = remember { viewModel.initFromStore() }
    SecureWindowEffect()

    val signedPsbtSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val signed = uiState.signedPsbtBase64
        if (uri != null && signed != null) {
            try {
                val psbtBytes = Base64.decode(signed, Base64.DEFAULT)
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(psbtBytes)
                } ?: error("Could not open output file")
                Toast.makeText(context, "Signed PSBT saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Signer") },
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
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
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
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Verify before signing", fontWeight = FontWeight.Bold)
                        review.outputs.forEach { output ->
                            Column {
                                Text(
                                    if (output.belongsToWallet) "Change / wallet output" else "Recipient",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(output.amountSat)} sats",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(output.address ?: "Script output ${output.index}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                        Text(
                            "Network fee: ${java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(review.feeSat)} sats " +
                                "(${String.format("%.2f", review.feeRateSatPerVbyte)} sat/vB)"
                        )
                        Text("Transaction ID: ${review.txid}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (uiState.requiresHighFeeConfirmation && !uiState.highFeeAcknowledged) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Unusually high fee", fontWeight = FontWeight.Bold)
                        Text("The exact fee exceeds 5% of the external amount. Verify it before allowing phone keys to sign.")
                        TextButton(onClick = viewModel::acknowledgeHighFee) { Text("I verified the fee") }
                    }
                }
            }

            if (uiState.signedPsbtBase64 == null) {
                Button(
                    onClick = {
                        when {
                            !uiState.biometricForSendEnabled -> viewModel.signWithPhoneKeys(walletId)
                            fragmentActivity == null || !BiometricHelper.canAuthenticate(context) ->
                                viewModel.setError("Biometric or device-credential authentication is required but unavailable")
                            else -> {
                                (context as? net.clench.wallet.ui.MainActivity)?.suppressPassphraseLock = true
                                BiometricHelper.authenticate(
                                    activity = fragmentActivity,
                                    title = "Authenticate phone signer",
                                    subtitle = "Verify your identity before wallet keys sign this transaction",
                                    onSuccess = {
                                        (context as? net.clench.wallet.ui.MainActivity)?.suppressPassphraseLock = false
                                        viewModel.signWithPhoneKeys(walletId)
                                    },
                                    onFailure = { message ->
                                        (context as? net.clench.wallet.ui.MainActivity)?.suppressPassphraseLock = false
                                        viewModel.setError("Authentication failed: $message")
                                    },
                                    onCancel = {
                                        (context as? net.clench.wallet.ui.MainActivity)?.suppressPassphraseLock = false
                                    },
                                    allowUiOnlyFallback = false
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
                    onClick = { signedPsbtSaveLauncher.launch("${walletId.take(8)}_phone_signed.psbt") },
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

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
