package net.clench.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Licenses") },
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
            val licenses = """
                Bitcoin Dev Kit (BDK) — Apache 2.0 / MIT
                https://github.com/bitcoindevkit/bdk
                
                Jetpack Compose — Apache 2.0
                https://developer.android.com/jetpack/compose
                
                Hilt (Dagger) — Apache 2.0
                https://dagger.dev/hilt/
                
                ZXing Android Embedded — Apache 2.0
                https://github.com/journeyapps/zxing-android-embedded
                
                AndroidX Biometric — Apache 2.0
                https://developer.android.com/jetpack/androidx/releases/biometric
                
                Room Persistence Library — Apache 2.0
                https://developer.android.com/jetpack/androidx/releases/room
                
                SQLCipher for Android — BSD License
                https://github.com/sqlcipher/sqlcipher-android
                
                Hummingbird (BC-UR) — Apache 2.0
                https://github.com/sparrowwallet/hummingbird
                
                CameraX — Apache 2.0
                https://developer.android.com/jetpack/androidx/releases/camera
                
                Kotlinx Coroutines — Apache 2.0
                https://github.com/Kotlin/kotlinx.coroutines
            """.trimIndent()

            Text(
                licenses,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
