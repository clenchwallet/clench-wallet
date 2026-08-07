package net.clench.wallet.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.clench.wallet.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit = {},
    onPrivacyPolicy: () -> Unit = {}
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Clench Wallet",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Built with BDK 3.0.0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "F-Droid friendly: no Google Play Services, Firebase, analytics, or crash-reporting SDKs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            ListItem(
                headlineContent = { Text("GitHub", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("github.com/clenchwallet/clench-wallet", style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) },
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/clenchwallet/clench-wallet"))
                    context.startActivity(intent)
                }
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Release Notes", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Latest APKs, checksums, and changelog", style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) },
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/clenchwallet/clench-wallet/releases"))
                    context.startActivity(intent)
                }
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Privacy Policy", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("How Clench handles your data", style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onPrivacyPolicy)
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Licenses", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Open source licenses", style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onLicenses)
            )
            HorizontalDivider()
        }
    }
}
