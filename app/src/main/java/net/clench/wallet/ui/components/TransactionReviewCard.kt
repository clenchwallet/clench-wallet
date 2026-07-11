package net.clench.wallet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import net.clench.wallet.domain.repository.BuiltTransactionReview
import net.clench.wallet.domain.repository.TransactionReviewOutput
import net.clench.wallet.ui.util.copyToClipboardWithAutoClear

internal object TransactionReviewPresentation {
    private val satsFormatter = NumberFormat.getNumberInstance(Locale.US)

    fun sats(value: Long): String = satsFormatter.format(value)

    fun feePercentage(review: BuiltTransactionReview): Double? {
        val referenceAmount = review.externalAmountSat.takeIf { it > 0L }
            ?: review.outputs.sumOf(TransactionReviewOutput::amountSat).takeIf { it > 0L }
            ?: return null
        return review.feeSat.toDouble() * 100.0 / referenceAmount.toDouble()
    }

    fun feeSummary(review: BuiltTransactionReview): String {
        val percentage = feePercentage(review)?.let { ", ${String.format(Locale.US, "%.2f", it)}% of sent amount" }.orEmpty()
        return "${sats(review.feeSat)} sats " +
            "(${String.format(Locale.US, "%.2f", review.feeRateSatPerVbyte)} sat/vB, ${review.vsize} vB$percentage)"
    }

    fun grouped(value: String, groupSize: Int = 4): String =
        value.chunked(groupSize.coerceAtLeast(1)).joinToString(" ")
}

/**
 * One immutable transaction-review surface shared by every signing path.
 * Callers retain ownership of authentication and the final sign/broadcast action.
 */
@Composable
fun TransactionReviewCard(
    review: BuiltTransactionReview,
    title: String,
    requiresHighFeeConfirmation: Boolean = false,
    highFeeAcknowledged: Boolean = false,
    onAcknowledgeHighFee: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Verify every recipient, amount, fee, and change output. Signing approves exactly this transaction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                review.outputs.forEach { output ->
                    ReviewOutput(output)
                }

                HorizontalDivider()
                Text("Network fee", fontWeight = FontWeight.SemiBold)
                Text(TransactionReviewPresentation.feeSummary(review), style = MaterialTheme.typography.bodySmall)
                Text(
                    "${review.inputs.size} input${if (review.inputs.size == 1) "" else "s"} · " +
                        "${review.outputs.size} output${if (review.outputs.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Transaction ID", style = MaterialTheme.typography.labelMedium)
                val context = LocalContext.current
                Row(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            TransactionReviewPresentation.grouped(review.txid, 8),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    TextButton(
                        onClick = { copyToClipboardWithAutoClear(context, "Transaction ID", review.txid) }
                    ) { Text("Copy") }
                }
            }
        }

        if (requiresHighFeeConfirmation) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (highFeeAcknowledged) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        if (highFeeAcknowledged) "High fee acknowledged" else "Unusually high fee",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This exact fee is above the normal relative-fee threshold. Confirm it against your intent and current network conditions.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!highFeeAcknowledged && onAcknowledgeHighFee != null) {
                        TextButton(onClick = onAcknowledgeHighFee) {
                            Text("I verified the exact fee")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewOutput(output: TransactionReviewOutput) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (output.belongsToWallet) "Change / wallet output" else "Recipient",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "${TransactionReviewPresentation.sats(output.amountSat)} sats",
                fontWeight = FontWeight.Bold
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    output.address?.let { TransactionReviewPresentation.grouped(it) }
                        ?: "Script output ${output.index}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            output.address?.let { address ->
                TextButton(
                    onClick = { copyToClipboardWithAutoClear(context, "Bitcoin address", address) }
                ) { Text("Copy") }
            }
        }
    }
}
