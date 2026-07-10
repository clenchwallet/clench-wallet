package net.clench.wallet.ui

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.PinManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.repository.BdkBitcoinRepository
import net.clench.wallet.ui.navigation.ClenchNavHost
import net.clench.wallet.ui.components.ColdcardNfcPayload
import net.clench.wallet.ui.screens.PinUnlockScreen
import net.clench.wallet.ui.theme.ClenchTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsManager: SettingsManager
    @Inject lateinit var pinManager: PinManager
    @Inject lateinit var bitcoinRepository: BdkBitcoinRepository

    private var lastPauseTimestamp: Long = 0L
    private val isLocked = mutableStateOf(true) // Start locked — will be resolved in onCreate
    var suppressLockOnResume: Boolean = false  // Skip lock check when returning from scanner/camera
    @Volatile var suppressPassphraseLock: Boolean = false  // Skip passphrase wallet lock during QR scan / biometric
    private var isChangingConfiguration: Boolean = false  // Don't lock on rotation

    // NFC PSBT flow — hardware wallets (Coldcard) can deliver signed PSBTs via NFC
    private val _nfcPsbtFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val nfcPsbtFlow = _nfcPsbtFlow.asSharedFlow()

    // Raw NFC tag flow — active hardware-wallet screens can consume foreground-dispatch fallbacks.
    private val _nfcTagFlow = MutableSharedFlow<Tag>(extraBufferCapacity = 1)
    val nfcTagFlow = _nfcTagFlow.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Determine initial lock state: locked if app lock is enabled
        val shouldLock = settingsManager.getAppLockMode() != "none"
        isLocked.value = shouldLock

        // Lock passphrase wallets when app becomes fully invisible (ON_STOP),
        // NOT on onPause — onPause fires when the Activity is merely obscured
        // (e.g. QR scanner overlay, biometric dialog), which would nuke
        // in-memory wallets mid-send-flow.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (!suppressPassphraseLock && !isChangingConfiguration) {
                    lifecycleScope.launch {
                        try {
                            val wallets = bitcoinRepository.listWallets()
                            for (wallet in wallets) {
                                if (wallet.hasPassphrase) {
                                    bitcoinRepository.lockPassphraseWallet(wallet.id)
                                }
                            }
                            // Regular hot wallets also hold private descriptors in native BDK
                            // objects. Explicitly dispose every remaining cache entry instead of
                            // leaving secret-bearing handles to GC while the app is backgrounded.
                            bitcoinRepository.clearCachedWallets()
                        } catch (e: Exception) {
                            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("MainActivity", "Failed to lock passphrase wallets on stop: ${e.message}")
                        }
                    }
                } else {
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("MainActivity", "Sensitive-wallet eviction suppressed for transient stop/configuration change")
                }
            }
        })

        setContent {
            ClenchTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (isLocked.value) {
                        val lockMode = settingsManager.getAppLockMode()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            if (lockMode == "pin") {
                                // Show PIN unlock screen
                                PinUnlockScreen(
                                    pinManager = pinManager,
                                    onUnlocked = { isLocked.value = false }
                                )
                            } else {
                                // Show biometric/credential unlock screen
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
                    } else {
                        // Do not compose protected navigation content while locked. Disposing the
                        // navigation tree also clears screen-local seed, WIF, PSBT, and send state.
                        val navController = rememberNavController()
                        ClenchNavHost(navController = navController)
                    }
                }
            }
        }

        // Auto-prompt on first launch if locked (but not for PIN mode - user must enter PIN)
        if (shouldLock && settingsManager.getAppLockMode() != "pin") {
            promptBiometricUnlock()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Drop NFC payloads while app is locked — don't process until biometric unlock
        if (isLocked.value) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("MainActivity", "NFC intent received while locked — ignored")
            return
        }
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)?.let { tag ->
            _nfcTagFlow.tryEmit(tag)
        }

        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action
        ) {
            @Suppress("DEPRECATION")
            val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            if (rawMsgs != null && rawMsgs.isNotEmpty()) {
                val ndefMessage = rawMsgs[0] as NdefMessage
                ColdcardNfcPayload.extractSigningPayload(ndefMessage)?.let { payload ->
                    _nfcPsbtFlow.tryEmit(payload)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isChangingConfiguration = isChangingConfigurations
        lastPauseTimestamp = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (suppressLockOnResume || isChangingConfiguration) {
            suppressLockOnResume = false
            isChangingConfiguration = false
            lastPauseTimestamp = 0L
            return
        }
        if (lastPauseTimestamp > 0L &&
            settingsManager.getAppLockMode() != "none"
        ) {
            val timeoutMs = settingsManager.getLockTimeoutMs()
            val elapsed = System.currentTimeMillis() - lastPauseTimestamp
            if (elapsed > timeoutMs) {
                val lockMode = settingsManager.getAppLockMode()
                if (lockMode == "pin") {
                    // PIN mode: just show lock screen, don't prompt biometric
                    isLocked.value = true
                } else {
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
