package net.clench.wallet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

internal enum class FeeRateGuidance { Invalid, NetworkEstimate, Elevated, HardLimit }

internal object FeePresentation {
    const val MAX_RATE_SAT_PER_VBYTE = 1_000.0

    fun guidance(rateText: String, priorityEstimate: Double?): FeeRateGuidance {
        val rate = rateText.toDoubleOrNull() ?: return FeeRateGuidance.Invalid
        if (!rate.isFinite() || rate <= 0.0) return FeeRateGuidance.Invalid
        if (rate > MAX_RATE_SAT_PER_VBYTE) return FeeRateGuidance.HardLimit
        if (priorityEstimate != null && priorityEstimate > 0.0 && rate > priorityEstimate * 3.0) {
            return FeeRateGuidance.Elevated
        }
        return FeeRateGuidance.NetworkEstimate
    }
}

@Composable
fun FeeSafetySummary(
    feeRateText: String,
    priorityEstimate: Double?,
    modifier: Modifier = Modifier
) {
    val guidance = FeePresentation.guidance(feeRateText, priorityEstimate)
    val (title, body) = when (guidance) {
        FeeRateGuidance.Invalid -> "Enter a valid fee rate" to
            "Use a positive finite sat/vB value before preparing the transaction."
        FeeRateGuidance.NetworkEstimate -> "Selected rate: $feeRateText sat/vB" to
            "The exact fee, virtual size, and percentage of the sent amount appear on the immutable review screen."
        FeeRateGuidance.Elevated -> "Rate is far above the priority estimate" to
            "Verify current network conditions. Clench will still calculate and show the exact absolute and percentage fee before signing."
        FeeRateGuidance.HardLimit -> "Rate reaches Clench's safety ceiling" to
            "Rates above ${String.format(Locale.US, "%.0f", FeePresentation.MAX_RATE_SAT_PER_VBYTE)} sat/vB are rejected. Choose a lower rate."
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (guidance) {
                FeeRateGuidance.Invalid, FeeRateGuidance.HardLimit -> MaterialTheme.colorScheme.errorContainer
                FeeRateGuidance.Elevated -> MaterialTheme.colorScheme.tertiaryContainer
                FeeRateGuidance.NetworkEstimate -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            Text(
                "Safety policy: explicit confirmation above 5% of the sent amount; rejection above 50% or 1,000,000 sats.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
