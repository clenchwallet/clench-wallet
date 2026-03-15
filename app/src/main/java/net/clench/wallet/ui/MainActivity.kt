package net.clench.wallet.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.ui.navigation.ClenchNavHost
import net.clench.wallet.ui.theme.ClenchTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsManager: SettingsManager

    private var lastPauseTimestamp: Long = 0L
    private val isLocked = mutableStateOf(true) // Start locked — will be resolved in onCreate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Determine initial lock state: locked if app lock is enabled
        val shouldLock = settingsManager.getAppLockMode() != "none"
        isLocked.value = shouldLock

        setContent {
            ClenchTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Main app content always rendered underneath
                    val navController = rememberNavController()
                    ClenchNavHost(navController = navController)

                    // Lock screen rendered ON TOP when locked
                    if (isLocked.value) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Clench is locked",
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(onClick = { promptBiometricUnlock() }) {
                                    Text("Unlock")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Auto-prompt biometric on first launch if locked
        if (shouldLock) {
            promptBiometricUnlock()
        }
    }

    override fun onPause() {
        super.onPause()
        lastPauseTimestamp = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (lastPauseTimestamp > 0L &&
            settingsManager.getAppLockMode() != "none"
        ) {
            val timeoutMs = settingsManager.getLockTimeoutMs()
            val elapsed = System.currentTimeMillis() - lastPauseTimestamp
            if (elapsed > timeoutMs) {
                val bm = BiometricManager.from(this)
                val canAuth = bm.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ) == BiometricManager.BIOMETRIC_SUCCESS

                if (canAuth) {
                    isLocked.value = true
                    promptBiometricUnlock()
                }
            }
        }
    }

    private fun promptBiometricUnlock() {
        val bm = BiometricManager.from(this)
        val canAuth = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS

        if (!canAuth) {
            // Device has no screen lock — unlock without auth
            isLocked.value = false
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                isLocked.value = false
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Keep locked — user can tap "Unlock" to retry
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    // User dismissed — stay locked, they can tap Unlock
                }
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Clench")
            .setSubtitle("Authenticate to access your wallet")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }
}
