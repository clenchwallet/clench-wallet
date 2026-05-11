package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.data.local.entity.SavedSignerEntity
import net.clench.wallet.ui.viewmodel.SignerVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignerVaultScreen(
    onBack: () -> Unit,
    viewModel: SignerVaultViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Signer Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Save Multisig Account Key", fontWeight = FontWeight.Bold)
                        Text(
                            "Store public cosigner account keys before building a multisig wallet. Clench expects complete origin data: master fingerprint, derivation path, and account xpub.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = uiState.label,
                            onValueChange = viewModel::setLabel,
                            label = { Text("Label") },
                            placeholder = { Text("e.g. TAPSIGNER, Coldcard, Recovery key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = uiState.publicKey,
                            onValueChange = viewModel::setPublicKey,
                            label = { Text("Public account key") },
                            placeholder = { Text("[fingerprint/48'/0'/0'/2']xpub...") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = uiState.fingerprint,
                                onValueChange = viewModel::setFingerprint,
                                label = { Text("Fingerprint") },
                                placeholder = { Text("8 hex") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = uiState.derivationPath,
                                onValueChange = viewModel::setDerivationPath,
                                label = { Text("Derivation path") },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                        }
                        OutlinedTextField(
                            value = uiState.deviceType,
                            onValueChange = viewModel::setDeviceType,
                            label = { Text("Device type optional") },
                            placeholder = { Text("TAPSIGNER, Coldcard, offline key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { viewModel.saveManualSigner() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Signer")
                        }
                    }
                }
            }

            uiState.error?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                error,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                            IconButton(onClick = { viewModel.clearError() }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }
            }

            uiState.message?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Saved Signers", fontWeight = FontWeight.Bold)
            }

            if (uiState.isLoading) {
                item {
                    CircularProgressIndicator()
                }
            } else if (uiState.savedSigners.isEmpty()) {
                item {
                    Text(
                        "No saved signers yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(uiState.savedSigners, key = { it.id }) { signer ->
                    SavedSignerCard(signer = signer, onDelete = { viewModel.deleteSigner(signer.id) })
                }
            }
        }
    }
}

@Composable
private fun SavedSignerCard(
    signer: SavedSignerEntity,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(signer.label, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            Text(
                "${signer.network} • ${signer.derivationPath} • ${signer.deviceType ?: "manual"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            signer.fingerprint?.let {
                Text(
                    "Fingerprint: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                signer.xpub.take(48) + if (signer.xpub.length > 48) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
