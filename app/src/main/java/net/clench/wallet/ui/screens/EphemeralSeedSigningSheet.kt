package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.clench.wallet.domain.model.HardwareWalletType

private fun deviceLabel(device: String): String = when (device) {
    "SEEDSIGNER" -> "SeedSigner"
    "KEYSTONE" -> "Keystone"
    "PASSPORT", "FOUNDATION_PASSPORT" -> "Foundation Passport"
    "COLDCARD_Q" -> "Coldcard Q"
    "COLDCARD_MK4" -> "Coldcard Mk4"
    "COLDCARD_MK5" -> "Coldcard Mk5"
    "JADE" -> "Blockstream Jade"
    else -> device
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchOnlySendSheet(
    onDismiss: () -> Unit,
    onHardwareWallet: () -> Unit,
    onHardwareWalletDirect: ((HardwareWalletType) -> Unit)? = null,
    preferredDevice: String? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Send from Watch-Only Wallet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This wallet is watch-only. Signing happens with the wallet’s configured signer; seed phrase entry is managed from Wallet Info, not during spend approval.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (preferredDevice != null)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                onClick = {
                    val directType = preferredDevice?.let {
                        try { HardwareWalletType.valueOf(it) } catch (_: Exception) {
                            when (it) {
                                "PASSPORT" -> HardwareWalletType.FOUNDATION_PASSPORT
                                else -> null
                            }
                        }
                    }
                    if (directType != null && onHardwareWalletDirect != null) {
                        onHardwareWalletDirect(directType)
                    } else {
                        onHardwareWallet()
                    }
                }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Sign with Hardware Wallet", fontWeight = FontWeight.Bold)
                    Text(
                        if (preferredDevice != null) "Use ${deviceLabel(preferredDevice)} to review and sign this PSBT."
                        else "Choose SeedSigner, Keystone, Passport, Coldcard, or Jade.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (preferredDevice != null)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Need to add a seed phrase? Open Wallet Info and use Add Seed Phrase there. That changes the wallet custody model, so Clench keeps it out of the signing flow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
