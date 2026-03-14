package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WelcomeScreen(
    onCreateWallet: () -> Unit,
    onImportWallet: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "✊",
            fontSize = 64.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Clench",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Hold it tight.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(64.dp))

        Button(
            onClick = onCreateWallet,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create New Wallet")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onImportWallet,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Import Existing Wallet")
        }
    }
}
