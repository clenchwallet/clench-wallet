package net.clench.wallet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.clench.wallet.domain.model.HardwareWalletType

data class SignerProgressStep(
    val title: String,
    val detail: String,
    val complete: Boolean
)

internal object SignerProgressPresentation {
    fun transferDetail(device: HardwareWalletType): String = when (device) {
        HardwareWalletType.SEEDSIGNER -> "Scan Clench's animated PSBT QR, verify outputs on SeedSigner, then scan its signed PSBT QR back."
        HardwareWalletType.KEYSTONE -> "Scan the BC-UR PSBT or use a file, verify outputs on Keystone, then return the signed PSBT."
        HardwareWalletType.FOUNDATION_PASSPORT -> "Scan the BC-UR PSBT or use microSD, verify outputs on Passport, then return the signed PSBT."
        HardwareWalletType.COLDCARD_Q -> "Use BBQr, NFC, or microSD; verify every output on Coldcard Q before returning the signed PSBT."
        HardwareWalletType.COLDCARD_MK4, HardwareWalletType.COLDCARD_MK5 ->
            "Use an intentional NFC tap or a user-selected microSD file; verify every output on Coldcard before returning the signed PSBT."
        HardwareWalletType.TAPSIGNER -> "Direct transaction signing is unavailable. NFC status checks do not sign or approve this PSBT."
        HardwareWalletType.JADE -> "Scan the BC-UR PSBT, verify outputs on Jade, then scan Jade's signed PSBT back."
    }

    fun steps(
        reviewAcknowledged: Boolean,
        hasCollectedSignature: Boolean,
        readyToBroadcast: Boolean,
        transferDetail: String = "Transfer the PSBT and approve the same outputs on the hardware device."
    ): List<SignerProgressStep> = listOf(
        SignerProgressStep(
            title = "Review transaction",
            detail = "Confirm recipients, change, exact fee, and transaction identifier in Clench.",
            complete = reviewAcknowledged
        ),
        SignerProgressStep(
            title = "Review on signer",
            detail = transferDetail,
            complete = hasCollectedSignature
        ),
        SignerProgressStep(
            title = "Return signature",
            detail = "Scan, tap, or import the signed PSBT back into Clench for policy validation.",
            complete = hasCollectedSignature
        ),
        SignerProgressStep(
            title = "Broadcast",
            detail = "Broadcast is available only after the wallet policy has enough valid signatures.",
            complete = readyToBroadcast
        )
    )

    fun signatureStatus(collectedSignerReturns: Int, readyToBroadcast: Boolean): String = when {
        readyToBroadcast -> "$collectedSignerReturns signer return${if (collectedSignerReturns == 1) "" else "s"}; policy complete"
        collectedSignerReturns > 0 -> "$collectedSignerReturns signer return${if (collectedSignerReturns == 1) "" else "s"}; more may be required"
        else -> "Waiting for signer"
    }
}

@Composable
fun SignerProgressStepper(
    signerName: String,
    connectionLabel: String,
    signatureStatus: String,
    steps: List<SignerProgressStep>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("$signerName signing progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "$connectionLabel · $signatureStatus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (step.complete) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (step.complete) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${index + 1}. ${step.title}", fontWeight = FontWeight.SemiBold)
                        Text(
                            step.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
