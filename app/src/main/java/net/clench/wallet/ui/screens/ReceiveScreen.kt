package net.clench.wallet.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.ui.viewmodel.ReceiveViewModel
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    walletId: String,
    onBack: () -> Unit,
    viewModel: ReceiveViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // FLAG_SECURE — prevent screenshots of receive address
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive Bitcoin") },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator()
            } else {
                // Render QR code using BIP21 URI
                if (uiState.address.isNotBlank()) {
                    val bip21Uri = remember(uiState.address, uiState.receiveWithAmount, uiState.amountSat) {
                        viewModel.getBip21Uri()
                    }
                    val bitmap = remember(bip21Uri) {
                        try {
                            val encoder = BarcodeEncoder()
                            val bmp = encoder.encodeBitmap(bip21Uri, BarcodeFormat.QR_CODE, 512, 512)
                            bmp.asImageBitmap()
                        } catch (e: Exception) { null }
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "Bitcoin BIP21 QR code",
                            modifier = Modifier
                                .size(220.dp)
                                .background(Color.White)
                                .padding(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.receiveWithAmount) {
                    OutlinedTextField(
                        value = uiState.amountSat,
                        onValueChange = { viewModel.setAmount(it) },
                        label = { Text("Amount (sats)") },
                        placeholder = { Text("Required for amount request") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            Text("QR and URI include this amount using BIP21")
                        }
                    )

                    val sats = uiState.amountSat.toLongOrNull()
                    if (sats != null && sats > 0L) {
                        val fmt = NumberFormat.getNumberInstance(Locale.US)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Requesting ${fmt.format(sats)} sats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.receiveAddressOnly() }) {
                        Text("Receive without Amount")
                    }
                } else {
                    Button(
                        onClick = { viewModel.receiveWithAmount() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Receive with Amount")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Bitcoin Address", style = MaterialTheme.typography.labelLarge)

                Spacer(modifier = Modifier.height(8.dp))

                SelectionContainer {
                    Text(
                        text = uiState.address,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.address.isNotBlank()) {
                    val deviceLabel = (uiState.importedViaDevice ?: uiState.preferredHardwareWallet)?.let { raw ->
                        runCatching { HardwareWalletType.valueOf(raw).displayName }.getOrDefault(raw)
                    } ?: "your hardware wallet"
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Verify Receive Address", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Confirm this exact address on $deviceLabel before receiving funds.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Index: ${uiState.addressIndex}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            Text(
                                "Path: ${uiState.verificationPath}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            uiState.masterFingerprint?.let { fp ->
                                Text(
                                    "Fingerprint: $fp",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Copy buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.copyAddress() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Copy Address") }

                    OutlinedButton(
                        onClick = { viewModel.copyBip21Uri() },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (uiState.receiveWithAmount) "Copy Request URI" else "Copy URI") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = { viewModel.nextAddress() }) {
                    Text("Generate next address")
                }
            }

            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// Need this import for SelectionContainer
@Composable
private fun SelectionContainer(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer { content() }
}
