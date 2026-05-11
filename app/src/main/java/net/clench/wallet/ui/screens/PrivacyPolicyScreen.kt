package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy") },
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Last updated: May 5, 2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // The Short Version
            SectionHeader("The Short Version")
            BodyText(
                "Clench Wallet collects no data. We have no servers, no analytics, no tracking, " +
                "and no advertising. Your keys stay on your device. Period."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // What We Collect
            SectionHeader("What We Collect")
            BodyText(
                "Nothing. Clench Wallet does not collect, transmit, or store any personal " +
                "information, usage data, crash reports, or analytics. There are no third-party " +
                "tracking or analytics SDKs, no telemetry, and no phone-home behavior of any kind."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Data Stored on Your Device
            SectionHeader("Data Stored on Your Device")
            BodyText("Clench stores the following data locally on your device only:")
            Spacer(modifier = Modifier.height(8.dp))
            BulletItem("Wallet data — addresses, transactions, labels, balances")
            BulletItem("Key material — mnemonics and private keys, encrypted with AES-256-GCM via Android Keystore")
            BulletItem("Settings — your app preferences (server configuration, display currency, etc.)")
            BulletItem("Database — all local data is stored in a SQLCipher-encrypted database")
            Spacer(modifier = Modifier.height(8.dp))
            BodyText(
                "This data never leaves your device unless you explicitly export it (e.g., " +
                "exporting transaction labels via BIP-329, or sharing a PSBT file)."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Network Connections
            SectionHeader("Network Connections")
            BodyText(
                "Clench makes the following network connections during normal operation. " +
                "All of these are routable through Tor when you enable Tor support in settings."
            )
            Spacer(modifier = Modifier.height(8.dp))
            NetworkConnectionItem(
                name = "Electrum server (default: electrum.blockstream.info)",
                purpose = "Wallet sync, transaction broadcast",
                sees = "Your wallet addresses and transactions",
                optional = "Configurable — you can point to your own server"
            )
            NetworkConnectionItem(
                name = "mempool.space",
                purpose = "Fee estimation, block height",
                sees = "Anonymous API requests (no wallet data)",
                optional = "Yes — can be disabled; falls back to Electrum fee estimation"
            )
            NetworkConnectionItem(
                name = "Coinbase / CoinGecko",
                purpose = "BTC price in your local currency",
                sees = "Anonymous API requests (no wallet data)",
                optional = "Yes — can be disabled in settings"
            )

            Spacer(modifier = Modifier.height(16.dp))

            // About Electrum Servers
            SubSectionHeader("About Electrum Servers")
            BodyText(
                "When Clench syncs your wallet, it sends your wallet addresses to the configured " +
                "Electrum server. The server operator can see which addresses belong to the same " +
                "wallet. This is inherent to how Electrum protocol works, not specific to Clench."
            )
            Spacer(modifier = Modifier.height(8.dp))
            BodyText("To maximize privacy:")
            BulletItem("Run your own Electrum server and point Clench to it")
            BulletItem("Enable Tor to hide your IP address from the server")

            Spacer(modifier = Modifier.height(16.dp))

            // Third Parties
            SectionHeader("Third Parties")
            BodyText(
                "We share no data with third parties because we have no data to share. There is:"
            )
            Spacer(modifier = Modifier.height(8.dp))
            BulletItem("No advertising")
            BulletItem("No analytics (no Google Analytics, no Firebase, no Mixpanel, nothing)")
            BulletItem("No crash reporting services")
            BulletItem("No user accounts or registration")
            BulletItem("No cloud sync")

            Spacer(modifier = Modifier.height(16.dp))

            // Key Material Security
            SectionHeader("Key Material Security")
            BodyText("Your mnemonics (seed phrases) and private keys:")
            Spacer(modifier = Modifier.height(8.dp))
            BulletItem("Are generated on-device using cryptographically secure random number generation")
            BulletItem("Are encrypted at rest using AES-256-GCM with keys stored in the Android Keystore hardware-backed security module")
            BulletItem("Never leave your device — not to our servers (we don't have any), not to any third party, not anywhere")
            BulletItem("Are never included in Android backups")

            Spacer(modifier = Modifier.height(16.dp))

            // Open Source
            SectionHeader("Open Source")
            BodyText(
                "Clench Wallet is fully open source. You don't have to take our word for any of " +
                "this — you can audit the code yourself at github.com/clenchwallet/clench-wallet."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Permissions
            SectionHeader("Permissions")
            BodyText("Clench requests only the following Android permissions:")
            Spacer(modifier = Modifier.height(8.dp))
            BulletItem("Internet — to connect to Electrum servers and optional price/fee APIs")
            BulletItem("Camera — to scan QR codes (addresses, PSBTs, hardware wallet communication). Only active when you open the scanner.")
            BulletItem("NFC — to communicate with NFC-based hardware wallets and cards (Coldcard, TAPSIGNER, SATSCARD). Only active during explicit NFC flows.")
            BulletItem("Bluetooth — to discover and connect to Bluetooth hardware wallets when that signing flow is used.")
            BulletItem("Biometrics — to authenticate wallet access")
            BulletItem("Clench does not require Google Play Services, Firebase, analytics, or third-party crash reporting.")

            Spacer(modifier = Modifier.height(16.dp))

            // Children's Privacy
            SectionHeader("Children's Privacy")
            BodyText(
                "Clench Wallet does not knowingly collect any information from anyone, " +
                "including children under 13."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Changes to This Policy
            SectionHeader("Changes to This Policy")
            BodyText(
                "If we ever change this policy, we'll update it in the repository and increment " +
                "the app version. Given that our policy is \"we collect nothing,\" changes would be unusual."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Contact
            SectionHeader("Contact")
            BodyText(
                "Questions about this privacy policy? Open an issue on GitHub or email cw@clench.net."
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun SubSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun BulletItem(text: String) {
    Row(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
        Text(
            text = "•  ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NetworkConnectionItem(
    name: String,
    purpose: String,
    sees: String,
    optional: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            LabeledRow("Purpose", purpose)
            LabeledRow("What it sees", sees)
            LabeledRow("Optional?", optional)
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
