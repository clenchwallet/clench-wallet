package net.clench.wallet.ui

import android.content.Intent
import android.content.ContentResolver
import android.app.Activity.RESULT_OK
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.PinManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.security.ForegroundAuthorizationToken
import net.clench.wallet.security.SensitiveCleanupStatus
import net.clench.wallet.ui.navigation.ClenchNavHost
import net.clench.wallet.ui.picker.LocalPickerRoundTripHost
import net.clench.wallet.ui.picker.PickerMode
import net.clench.wallet.ui.picker.PickerDestination
import net.clench.wallet.ui.picker.PickerPurpose
import net.clench.wallet.ui.picker.PickerRequest
import net.clench.wallet.ui.picker.PickerResult
import net.clench.wallet.ui.picker.PickerRoundTripBroker
import net.clench.wallet.ui.picker.PickerRoundTripHost
import net.clench.wallet.ui.components.ColdcardNfcPayload
import net.clench.wallet.ui.screens.PinUnlockScreen
import net.clench.wallet.ui.theme.ClenchTheme
import net.clench.wallet.ui.util.AuthenticationSessionGuard
import net.clench.wallet.ui.util.BiometricHelper
import javax.inject.Inject

private const val MAX_PICKER_URI_CHARS = 8_192

@AndroidEntryPoint
class MainActivity : FragmentActivity(), AuthenticationSessionGuard {

    @Inject lateinit var settingsManager: SettingsManager
    @Inject lateinit var pinManager: PinManager
    @Inject lateinit var appProcessSecurityCoordinator: AppProcessSecurityCoordinator
    @Inject internal lateinit var pickerBroker: PickerRoundTripBroker

    private val isLocked = mutableStateOf(true) // Start locked — will be resolved in onCreate
    private val isOsAuthenticationAvailable = mutableStateOf(false)
    private var biometricUnlockRequestPending: Boolean = false
    private val pickerHost = object : PickerRoundTripHost {
        override val pickerResume get() = pickerBroker.resume

        override fun launchPicker(request: PickerRequest): Boolean =
            launchPickerForCurrentSession(request)

        override fun abortPicker(requestId: Long) {
            pickerBroker.abort(requestId)
        }

        override fun consumePickerResult(
            purpose: PickerPurpose,
            destination: PickerDestination
        ): PickerResult? = consumePickerResultForCurrentSession(purpose, destination)
    }

    private val documentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val returnedUri = if (result.resultCode == RESULT_OK) result.data?.data else null
        pickerBroker.recordResult(
            returnedUri
                ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
                ?.toString()
                ?.takeIf { it.length <= MAX_PICKER_URI_CHARS }
        )
        refreshPickerAuthorization()
    }

    // NFC PSBT flow — hardware wallets (Coldcard) can deliver signed PSBTs via NFC
    private val _nfcPsbtFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val nfcPsbtFlow = _nfcPsbtFlow.asSharedFlow()

    // Raw NFC tag flow — active hardware-wallet screens can consume foreground-dispatch fallbacks.
    private val _nfcTagFlow = MutableSharedFlow<Tag>(extraBufferCapacity = 1)
    val nfcTagFlow = _nfcTagFlow.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Reject obscured touches on every supported Android version. Android 12+
        // additionally prevents non-system application overlays from being drawn over
        // transaction review, authentication, signing, and broadcast controls.
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        // Determine initial lock state: locked if app lock is enabled
        val shouldLock = settingsManager.getAppLockMode() != "none"
        isLocked.value = shouldLock
        isOsAuthenticationAvailable.value = canUseOsAuthentication()

        setContent {
            ClenchTheme {
                val processSecurityState by appProcessSecurityCoordinator.state.collectAsState()
                LaunchedEffect(
                    processSecurityState.cleanupStatus,
                    processSecurityState.isForeground,
                    processSecurityState.appLockRequired,
                    processSecurityState.foregroundGeneration,
                    isLocked.value
                ) {
                    refreshPickerAuthorization()
                }
                CompositionLocalProvider(LocalPickerRoundTripHost provides pickerHost) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                    if (processSecurityState.cleanupStatus != SensitiveCleanupStatus.READY) {
                        val cleanupRetryable =
                            processSecurityState.cleanupStatus == SensitiveCleanupStatus.FAILED
                        val restartRequired = processSecurityState.cleanupStatus ==
                            SensitiveCleanupStatus.FAILED_RESTART_REQUIRED
                        val cleanupFailed = cleanupRetryable || restartRequired
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Wallet access stopped",
                                modifier = Modifier.size(64.dp),
                                tint = if (cleanupFailed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                if (cleanupFailed) {
                                    "Wallet access stopped"
                                } else {
                                    "Securing wallet session…"
                                },
                                style = MaterialTheme.typography.headlineSmall
                            )
                            if (cleanupFailed) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    if (restartRequired) {
                                        "Clench could not verify that native wallet state was fully cleared. " +
                                            "Force stop Clench in Android Settings, then reopen it before continuing."
                                    } else {
                                        "Secure cleanup did not finish. Retry the complete cleanup before " +
                                            "continuing."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                if (cleanupRetryable) {
                                    Button(
                                        onClick = appProcessSecurityCoordinator::retrySensitiveCleanup
                                    ) {
                                        Text("Retry secure cleanup")
                                    }
                                }
                            }
                        }
                    } else if (isLocked.value || processSecurityState.appLockRequired) {
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
                                    onUnlocked = {
                                        val token = appProcessSecurityCoordinator
                                            .captureForegroundAuthorization(allowPendingAppLock = true)
                                        isLocked.value = token == null ||
                                            !appProcessSecurityCoordinator.satisfyAppLock(token)
                                    }
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
                                    if (!isOsAuthenticationAvailable.value) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            "OS authentication is unavailable. Configure authentication " +
                                                "supported by this Android version. Android 8–10 requires " +
                                                "a strong biometric for cryptographic unlock; otherwise use Clench PIN.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Button(
                                        onClick = { promptBiometricUnlock() },
                                        enabled = isOsAuthenticationAvailable.value
                                    ) {
                                        Text("Unlock")
                                    }
                                }
                            }
                        }
                    } else {
                        // Do not compose protected navigation content while locked. Disposing the
                        // navigation tree also clears screen-local seed, WIF, PSBT, and send state.
                        key(processSecurityState.foregroundGeneration) {
                            val navController = rememberNavController()
                            ClenchNavHost(navController = navController)
                        }
                    }
                }
            }
        }
        }

        // Auto-prompt on first launch if locked (but not for PIN mode - user must enter PIN)
        if (shouldLock && settingsManager.getAppLockMode() != "pin") {
            requestBiometricUnlockWhenSecurityReady()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Drop NFC payloads while app is locked — don't process until biometric unlock
        if (isLocked.value ||
            appProcessSecurityCoordinator.state.value.cleanupStatus != SensitiveCleanupStatus.READY ||
            appProcessSecurityCoordinator.state.value.appLockRequired
        ) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("MainActivity", "NFC intent received while locked — ignored")
            return
        }
        @Suppress("DEPRECATION")
        runCatching { intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) }.getOrNull()?.let { tag ->
            _nfcTagFlow.tryEmit(tag)
        }

        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action
        ) {
            @Suppress("DEPRECATION")
            val ndefMessage = runCatching {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                    ?.firstNotNullOfOrNull { it as? NdefMessage }
            }.getOrNull()
            if (ndefMessage != null) {
                ColdcardNfcPayload.extractSigningPayload(ndefMessage)?.let { payload ->
                    _nfcPsbtFlow.tryEmit(payload)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isLocked.value && settingsManager.getAppLockMode() == "biometric") {
            // Refresh after returning from Android security settings so the retry button can
            // become available without weakening the configured lock.
            isOsAuthenticationAvailable.value = canUseOsAuthentication()
        }
        val processSecurityState = appProcessSecurityCoordinator.state.value
        if (processSecurityState.appLockRequired) {
            isLocked.value = true
            if (settingsManager.getAppLockMode() != "pin") {
                requestBiometricUnlockWhenSecurityReady()
            }
        }
    }

    private fun requestBiometricUnlockWhenSecurityReady() {
        if (biometricUnlockRequestPending) return
        biometricUnlockRequestPending = true
        lifecycleScope.launch {
            val readyState = appProcessSecurityCoordinator.state.first { state ->
                (state.cleanupStatus == SensitiveCleanupStatus.READY && state.isForeground) ||
                    state.cleanupStatus == SensitiveCleanupStatus.FAILED ||
                    state.cleanupStatus == SensitiveCleanupStatus.FAILED_RESTART_REQUIRED
            }
            biometricUnlockRequestPending = false
            if (readyState.cleanupStatus == SensitiveCleanupStatus.READY &&
                readyState.isForeground &&
                (isLocked.value || readyState.appLockRequired)
            ) {
                promptBiometricUnlock()
            }
        }
    }

    private fun promptBiometricUnlock() {
        val canAuth = canUseOsAuthentication()
        isOsAuthenticationAvailable.value = canAuth

        if (!canAuth) {
            // Keep the app locked until the configured Android authentication method is restored.
            isLocked.value = true
            return
        }

        val authorization = appProcessSecurityCoordinator.captureForegroundAuthorization(
            allowPendingAppLock = true
        )
        if (authorization == null) {
            isLocked.value = true
            return
        }

        BiometricHelper.authenticateForAppUnlock(
            activity = this,
            onSuccess = {
                isLocked.value = !appProcessSecurityCoordinator.satisfyAppLock(authorization)
                if (isLocked.value) requestBiometricUnlockWhenSecurityReady()
            },
            onFailure = {
                // Keep locked. The user may retry after restoring Android authentication.
                isLocked.value = true
                isOsAuthenticationAvailable.value = canUseOsAuthentication()
            },
            onCancel = { isLocked.value = true }
        )
    }

    private fun canUseOsAuthentication(): Boolean =
        BiometricHelper.canAuthenticateForAppUnlock(this)

    private fun launchPickerForCurrentSession(request: PickerRequest): Boolean {
        val authorization = appProcessSecurityCoordinator.captureForegroundAuthorization()
            ?: return false
        val requestId = pickerBroker.begin(request, authorization) ?: return false
        val intent = when (request.mode) {
            PickerMode.OPEN_DOCUMENT -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = request.mimeTypes.first()
                if (request.mimeTypes.size > 1) {
                    putExtra(Intent.EXTRA_MIME_TYPES, request.mimeTypes.toTypedArray())
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            PickerMode.CREATE_DOCUMENT -> Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = request.mimeTypes.first()
                putExtra(Intent.EXTRA_TITLE, request.suggestedName)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }.addCategory(Intent.CATEGORY_OPENABLE)

        return try {
            documentPickerLauncher.launch(intent)
            true
        } catch (_: Throwable) {
            pickerBroker.abort(requestId)
            false
        }
    }

    private fun consumePickerResultForCurrentSession(
        purpose: PickerPurpose,
        destination: PickerDestination
    ): PickerResult? {
        val authorization = appProcessSecurityCoordinator.captureForegroundAuthorization()
            ?: return null
        return pickerBroker.consume(
            purpose = purpose,
            destination = destination,
            authorization = authorization,
            authorizationIsCurrent = appProcessSecurityCoordinator
                .isForegroundAuthorizationCurrent(authorization)
        )
    }

    private fun refreshPickerAuthorization() {
        val snapshot = appProcessSecurityCoordinator.state.value
        if (isLocked.value ||
            !snapshot.isForeground ||
            snapshot.cleanupStatus != SensitiveCleanupStatus.READY ||
            snapshot.appLockRequired
        ) {
            pickerBroker.revokeAuthorization()
            return
        }
        val authorization = appProcessSecurityCoordinator.captureForegroundAuthorization()
            ?: run {
                pickerBroker.revokeAuthorization()
                return
            }
        pickerBroker.authorizeResult(
            authorization = authorization,
            authorizationIsCurrent = appProcessSecurityCoordinator
                .isForegroundAuthorizationCurrent(authorization)
        )
    }

    override fun captureSensitiveAuthenticationSession(): Any? =
        appProcessSecurityCoordinator.captureForegroundAuthorization()

    override fun isSensitiveAuthenticationSessionCurrent(token: Any): Boolean =
        (token as? ForegroundAuthorizationToken)?.let(
            { authorization ->
                appProcessSecurityCoordinator.isForegroundAuthorizationCurrent(
                    authorization
                )
            }
        ) ?: false

    override fun captureAppUnlockAuthenticationSession(): Any? =
        appProcessSecurityCoordinator.captureForegroundAuthorization(allowPendingAppLock = true)

    override fun isAppUnlockAuthenticationSessionCurrent(token: Any): Boolean =
        (token as? ForegroundAuthorizationToken)?.let { authorization ->
            appProcessSecurityCoordinator.isForegroundAuthorizationCurrent(
                authorization,
                allowPendingAppLock = true
            )
        } ?: false
}
