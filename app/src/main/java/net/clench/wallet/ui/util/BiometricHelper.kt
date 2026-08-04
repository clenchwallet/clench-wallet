package net.clench.wallet.ui.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.util.Collections
import java.util.IdentityHashMap
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import net.clench.wallet.security.deliverAuthenticatedActionIfCurrent

/** Implemented by the sole Clench host Activity to bind prompt callbacks to one process session. */
interface AuthenticationSessionGuard {
    fun captureSensitiveAuthenticationSession(): Any?
    fun isSensitiveAuthenticationSessionCurrent(token: Any): Boolean
    fun captureAppUnlockAuthenticationSession(): Any?
    fun isAppUnlockAuthenticationSessionCurrent(token: Any): Boolean
}

/** The prompt/key policy that is safe on a particular Android API and authenticator state. */
internal enum class CryptoAuthenticationPolicy {
    MODERN_STRONG_OR_DEVICE_CREDENTIAL,
    MODERN_STRONG_BIOMETRIC,
    LEGACY_STRONG_BIOMETRIC,
    UNAVAILABLE
}

/**
 * Select a policy without ever asking Android 8-10 to use an unsupported credential/CryptoObject
 * combination. This pure function is kept separate so API-boundary behavior remains testable.
 */
internal fun selectCryptoAuthenticationPolicy(
    apiLevel: Int,
    strongBiometricAvailable: Boolean,
    modernCombinedAvailable: Boolean
): CryptoAuthenticationPolicy {
    if (apiLevel >= Build.VERSION_CODES.R) {
        return when {
            modernCombinedAvailable -> CryptoAuthenticationPolicy.MODERN_STRONG_OR_DEVICE_CREDENTIAL
            strongBiometricAvailable -> CryptoAuthenticationPolicy.MODERN_STRONG_BIOMETRIC
            else -> CryptoAuthenticationPolicy.UNAVAILABLE
        }
    }
    return if (strongBiometricAvailable) {
        CryptoAuthenticationPolicy.LEGACY_STRONG_BIOMETRIC
    } else {
        CryptoAuthenticationPolicy.UNAVAILABLE
    }
}

/** Walk wrapped provider failures safely; Android Keystore often wraps invalidation exceptions. */
internal fun Throwable.hasCauseMatching(predicate: (Throwable) -> Boolean): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (predicate(current)) return true
        current = current.cause
    }
    return false
}

/**
 * Crypto-bound Android authentication for app unlock and sensitive wallet actions.
 *
 * Every success must authorize a per-use Android Keystore HMAC operation supplied to the system
 * prompt as a [BiometricPrompt.CryptoObject]. A prompt callback alone never grants access.
 * Android 11+ can bind either a strong biometric or device credential to that operation. Android
 * 8-10 can bind only a strong biometric; users without one must choose the Clench PIN app lock or
 * disable an optional per-action gate from Settings rather than receiving a UI-only fallback.
 */
object BiometricHelper {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val MODERN_COMBINED_PROOF_KEY_ALIAS = "clench_crypto_auth_modern_combined_key_v3"
    private const val MODERN_BIOMETRIC_PROOF_KEY_ALIAS = "clench_crypto_auth_modern_biometric_key_v3"
    private const val LEGACY_BIOMETRIC_PROOF_KEY_ALIAS = "clench_crypto_auth_legacy_key_v3"

    /** Whether this device can provide the crypto-bound authentication Clench requires. */
    fun canAuthenticate(context: Context): Boolean =
        authenticationPolicy(context) != CryptoAuthenticationPolicy.UNAVAILABLE

    /** Whether this device can provide a crypto-bound app unlock. */
    fun canAuthenticateForAppUnlock(context: Context): Boolean = canAuthenticate(context)

    fun authenticateForAppUnlock(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        authenticateInternal(
            activity = activity,
            title = "Unlock Clench",
            subtitle = "Authenticate to access your wallet",
            onSuccess = onSuccess,
            onFailure = onFailure,
            onCancel = onCancel,
            allowPendingAppLock = true
        )
    }

    /** Authenticate a sensitive action with a fresh prompt-bound Keystore HMAC operation. */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        authenticateInternal(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onSuccess = onSuccess,
            onFailure = onFailure,
            onCancel = onCancel,
            allowPendingAppLock = false
        )
    }

    /** Only app unlock may capture a token while the authoritative app-lock latch is set. */
    private fun authenticateInternal(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
        onCancel: (() -> Unit)?,
        allowPendingAppLock: Boolean
    ) {
        val sessionGuard = activity as? AuthenticationSessionGuard
        val sessionToken = if (allowPendingAppLock) {
            sessionGuard?.captureAppUnlockAuthenticationSession()
        } else {
            sessionGuard?.captureSensitiveAuthenticationSession()
        }
        if (sessionGuard == null || sessionToken == null) {
            onFailure("Wallet session is not ready for authentication")
            return
        }

        val policy = authenticationPolicy(activity)
        if (policy == CryptoAuthenticationPolicy.UNAVAILABLE) {
            onFailure(authenticationUnavailableGuidance())
            return
        }

        val mac = try {
            initializedProofMac(policy)
        } catch (_: Exception) {
            onFailure("Cryptographic Android authentication could not be initialized")
            return
        }

        val authenticators = when (policy) {
            CryptoAuthenticationPolicy.MODERN_STRONG_OR_DEVICE_CREDENTIAL ->
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            CryptoAuthenticationPolicy.MODERN_STRONG_BIOMETRIC,
            CryptoAuthenticationPolicy.LEGACY_STRONG_BIOMETRIC ->
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            CryptoAuthenticationPolicy.UNAVAILABLE -> error("Unavailable policy was already rejected")
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authenticatedMac = result.cryptoObject?.mac
                if (authenticatedMac !== mac || !proveAuthenticatedMac(authenticatedMac)) {
                    onFailure("Android authentication did not provide cryptographic verification")
                    return
                }
                deliverAuthenticatedActionIfCurrent(
                    isCurrent = {
                        if (allowPendingAppLock) {
                            sessionGuard.isAppUnlockAuthenticationSessionCurrent(sessionToken)
                        } else {
                            sessionGuard.isSensitiveAuthenticationSessionCurrent(sessionToken)
                        }
                    },
                    onSuccess = onSuccess,
                    onStale = {
                        onFailure("Clench was backgrounded. Review and authenticate again")
                    }
                )
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    onCancel?.invoke()
                } else {
                    onFailure(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                // A single failed biometric attempt leaves the system prompt open for retry.
            }
        }

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
        if (policy != CryptoAuthenticationPolicy.MODERN_STRONG_OR_DEVICE_CREDENTIAL) {
            // Android forbids a negative button when device credential is an allowed fallback.
            promptInfoBuilder.setNegativeButtonText("Cancel")
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            callback
        )
        try {
            prompt.authenticate(
                promptInfoBuilder.build(),
                BiometricPrompt.CryptoObject(mac)
            )
        } catch (_: Exception) {
            onFailure("Could not start cryptographic Android authentication")
        }
    }

    private fun authenticationPolicy(context: Context): CryptoAuthenticationPolicy {
        val manager = BiometricManager.from(context)
        val strongAvailable = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
        val modernCombinedAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            // This combination is unsupported on API 28-29 and must not be queried there.
            false
        }
        return selectCryptoAuthenticationPolicy(
            apiLevel = Build.VERSION.SDK_INT,
            strongBiometricAvailable = strongAvailable,
            modernCombinedAvailable = modernCombinedAvailable
        )
    }

    /** Recovery guidance for callers that cannot start a crypto-bound prompt. */
    fun authenticationUnavailableGuidance(): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            "Cryptographic authentication on Android 8-10 requires an enrolled strong " +
                "biometric. Use a Clench PIN app lock, or change optional authentication " +
                "gates in Settings → Security."
        } else {
            "Configure a strong biometric or device credential in Android settings, then retry."
        }

    private fun initializedProofMac(policy: CryptoAuthenticationPolicy): Mac {
        val alias = proofKeyAlias(policy)

        fun initialize(): Mac {
            val key = getOrCreateProofKey(alias, policy)
            return Mac.getInstance("HmacSHA256").apply { init(key) }
        }

        return try {
            initialize()
        } catch (error: Exception) {
            if (!error.hasCauseMatching { it is KeyPermanentlyInvalidatedException }) throw error
            // Only these disposable proof keys may be recreated. They never encrypt wallet data,
            // and this allowlist prevents an invalidation wrapper from deleting a wallet key.
            deleteProofKeyEntry(alias)
            initialize()
        }
    }

    private fun proofKeyAlias(policy: CryptoAuthenticationPolicy): String = when (policy) {
        CryptoAuthenticationPolicy.MODERN_STRONG_OR_DEVICE_CREDENTIAL ->
            MODERN_COMBINED_PROOF_KEY_ALIAS
        CryptoAuthenticationPolicy.MODERN_STRONG_BIOMETRIC -> MODERN_BIOMETRIC_PROOF_KEY_ALIAS
        CryptoAuthenticationPolicy.LEGACY_STRONG_BIOMETRIC -> LEGACY_BIOMETRIC_PROOF_KEY_ALIAS
        CryptoAuthenticationPolicy.UNAVAILABLE -> error("Unavailable authentication has no proof key")
    }

    private fun getOrCreateProofKey(
        alias: String,
        policy: CryptoAuthenticationPolicy
    ): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(alias, null)?.let { return it as SecretKey }

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)

        when (policy) {
            CryptoAuthenticationPolicy.MODERN_STRONG_OR_DEVICE_CREDENTIAL -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    configureModernProofKey(
                        builder,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    error("Modern authentication policy selected below Android 11")
                }
            }
            CryptoAuthenticationPolicy.MODERN_STRONG_BIOMETRIC -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    configureModernProofKey(builder, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    error("Modern authentication policy selected below Android 11")
                }
            }
            CryptoAuthenticationPolicy.LEGACY_STRONG_BIOMETRIC -> configureLegacyProofKey(builder)
            CryptoAuthenticationPolicy.UNAVAILABLE -> error("Unavailable authentication has no proof key")
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            KEYSTORE_PROVIDER
        )
        generator.init(builder.build())
        return generator.generateKey()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun configureModernProofKey(
        builder: KeyGenParameterSpec.Builder,
        authenticators: Int
    ) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        builder.setUserAuthenticationParameters(0, authenticators)
    }

    @Suppress("DEPRECATION")
    private fun configureLegacyProofKey(builder: KeyGenParameterSpec.Builder) {
        // -1 means every key use must be authorized by a prompt-bound strong biometric.
        builder.setUserAuthenticationValidityDurationSeconds(-1)
        builder.setInvalidatedByBiometricEnrollment(true)
    }

    private fun deleteProofKeyEntry(alias: String) {
        require(
            alias == MODERN_COMBINED_PROOF_KEY_ALIAS ||
                alias == MODERN_BIOMETRIC_PROOF_KEY_ALIAS ||
                alias == LEGACY_BIOMETRIC_PROOF_KEY_ALIAS
        ) {
            "Refusing to delete a non-proof Keystore key"
        }
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(alias)
    }

    private fun proveAuthenticatedMac(mac: Mac): Boolean {
        val challenge = ByteArray(32)
        var proof: ByteArray? = null
        return try {
            java.security.SecureRandom().nextBytes(challenge)
            proof = mac.doFinal(challenge)
            proof?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        } finally {
            challenge.fill(0)
            proof?.fill(0)
        }
    }
}
