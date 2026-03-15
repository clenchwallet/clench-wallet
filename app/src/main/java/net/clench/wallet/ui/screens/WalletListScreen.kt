package net.clench.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.ui.viewmodel.WalletListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletListScreen(
    onWalletSelected: (String) -> Unit,
    onAddWallet: () -> Unit,
    onBack: () -> Unit,
    onNavigateWelcome: () -> Unit = {},
    viewModel: WalletListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var walletToDelete by remember { mutableStateOf<WalletData?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    // Navigate to Welcome if all wallets deleted
    LaunchedEffect(uiState.deletedWalletId) {
        if (uiState.deletedWalletId != null) {
            viewModel.clearDeletedState()
            onNavigateWelcome()
        }
    }

    // Delete confirmation dialog
    walletToDelete?.let { wallet ->
        AlertDialog(
            onDismissRequest = { walletToDelete = null },
            title = { Text("Delete ${wallet.name}?") },
            text = { Text("This cannot be undone. Make sure your seed phrase is backed up.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteWallet(wallet.id)
                        walletToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { walletToDelete = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallets") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddWallet) {
                Icon(Icons.Default.Add, contentDescription = "Add Wallet")
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                items(uiState.wallets, key = { it.id }) { wallet ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                walletToDelete = wallet
                                false // Don't actually dismiss; let the dialog handle it
                            } else false
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        },
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true
                    ) {
                        ListItem(
                            headlineContent = { Text(wallet.name) },
                            supportingContent = {
                                Text(
                                    if (wallet.isWatchOnly) "Watch-only" else "Full wallet",
                                    color = if (wallet.isWatchOnly)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else
                                        MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier.clickable { onWalletSelected(wallet.id) }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
