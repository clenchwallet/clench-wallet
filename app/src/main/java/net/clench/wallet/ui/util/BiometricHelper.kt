package net.clench.wallet.ui.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Helper for biometric/device-credential authentication gates.
 * Uses crypto-bound biometric auth with Android Keystore for strong security.
 *
 * The biometric key requires biometric authentication before the cipher can be used.
 * This cryptographically verifies the prompt result, but no app can claim that a fully
 * compromised/rooted runtime is impossible to bypass; wallet-secret use still needs strict
 * transaction review and minimal in-memory exposure.
 */
object BiometricHelper {

    private const val TAG = "BiometricHelper"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val BIOMETRIC_KEY_ALIAS = "clench_biometric_auth_key"
    private const val PREFS_NAME = "clench_biometric_prefs"
    private const val PREF_ACCESS_TOKEN_IV = "biometric_access_token_iv"
    private const val PREF_ACCESS_TOKEN_CIPHER = "biometric_access_token_cipher"

    /**
     * Check if the device supports biometric or device credential authentication.
     */
    fun canAuthenticate(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Show biometric/PIN prompt with crypto-bound authentication.
     * Uses a Keystore-backed cipher that requires biometric auth to unlock.
     *
     * Falls back to UI-only auth if crypto setup fails (e.g., no biometric enrolled,
     * device doesn't support strong biometric + crypto).
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
        onCancel: (() -> Unit)? = null,
        allowUiOnlyFallback: Boolean = true
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        // Try crypto-bound auth first
        val cipher = try {
            ensureBiometricSetup(activity)
            getDecryptCipher(activity)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // User changed biometrics — re-enroll
            if (net.clench.wallet.BuildConfig.DEBUG) Log.w(TAG, "Biometric key invalidated (user changed biometrics), re-enrolling")
            try {
                resetBiometricKey(activity)
                ensureBiometricSetup(activity)
                getDecryptCipher(activity)
            } catch (e2: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) Log.w(TAG, "Crypto re-enrollment failed", e2)
                if (!allowUiOnlyFallback) {
                    onFailure("Crypto-bound biometric verification unavailable after enrollment changed")
                    return
                }
                null
            }
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) Log.w(TAG, "Crypto-bound auth unavailable", e)
            if (!allowUiOnlyFallback) {
                onFailure("Crypto-bound biometric verification unavailable")
                return
            }
            null
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cipher != null) {
                    // Crypto-bound: verify the cipher actually worked
                    val resultCipher = result.cryptoObject?.cipher
                    if (resultCipher != null) {
                        try {
                            val decrypted = decryptAccessToken(activity, resultCipher)
                            if (decrypted != null) {
                                try { onSuccess() } finally { decrypted.fill(0) }
                            } else onFailure("Biometric verification failed — could not decrypt access token")
                        } catch (e: Exception) {
                            if (net.clench.wallet.BuildConfig.DEBUG) Log.e(TAG, "Decryption failed after auth", e)
                            onFailure("Biometric verification failed: ${e.message}")
                        }
                    } else {
                        // CryptoObject was null in result — treat as failure
                        onFailure("Biometric authentication did not provide crypto verification")
                    }
                } else {
                    // UI-only fallback
                    onSuccess()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    onCancel?.invoke()
                } else {
                    onFailure(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                // Called on a single failed attempt — prompt stays visible for retry.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        if (cipher != null) {
            // Crypto-bound auth — BIOMETRIC_STRONG only (no DEVICE_CREDENTIAL with CryptoObject)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            try {
                prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            } catch (e: Exception) {
                if (!allowUiOnlyFallback) {
                    if (net.clench.wallet.BuildConfig.DEBUG) Log.w(TAG, "Crypto prompt failed; strict auth refuses UI-only fallback", e)
                    onFailure("Crypto-bound biometric prompt failed")
                    return
                }
                // Fallback to UI-only if crypto prompt fails
                if (net.clench.wallet.BuildConfig.DEBUG) Log.w(TAG, "Crypto prompt failed, falling back", e)
                authenticateUiOnly(prompt, title, subtitle)
            }
        } else {
            // UI-only fallback
            authenticateUiOnly(prompt, title, subtitle)
        }
    }

    private fun authenticateUiOnly(prompt: BiometricPrompt, title: String, subtitle: String) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    // --- Keystore key management ---

    private fun getOrCreateBiometricKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        keyStore.getKey(BIOMETRIC_KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            // Require biometric for every use (timeout = 0)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /**
     * Ensure the biometric access token is set up.
     * On first call: generates a random access token and encrypts it with the biometric key.
     */
    private fun ensureBiometricSetup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(PREF_ACCESS_TOKEN_CIPHER)) return

        // First-time setup: generate and encrypt an access token
        val key = getOrCreateBiometricKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        // Random 32-byte access token
        val accessToken = ByteArray(32)
        java.security.SecureRandom().nextBytes(accessToken)

        val encryptedBytes = cipher.doFinal(accessToken)
        val iv = cipher.iv

        try {
            check(prefs.edit()
                .putString(PREF_ACCESS_TOKEN_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                .putString(PREF_ACCESS_TOKEN_CIPHER, android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP))
                .commit()) { "Could not persist biometric verification material" }
        } finally {
            accessToken.fill(0)
        }
    }

    /**
     * Get a decrypt cipher initialized with the biometric key.
     * This cipher CANNOT be used until biometric auth succeeds (via CryptoObject).
     */
    private fun getDecryptCipher(context: Context): Cipher {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ivBase64 = prefs.getString(PREF_ACCESS_TOKEN_IV, null)
            ?: throw IllegalStateException("Biometric not set up")
        val iv = android.util.Base64.decode(ivBase64, android.util.Base64.NO_WRAP)

        val key = getOrCreateBiometricKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher
    }

    /**
     * Decrypt the access token using the authenticated cipher.
     * Returns the decrypted bytes, or null if decryption fails.
     */
    private fun decryptAccessToken(context: Context, cipher: Cipher): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cipherBase64 = prefs.getString(PREF_ACCESS_TOKEN_CIPHER, null) ?: return null
        val cipherBytes = android.util.Base64.decode(cipherBase64, android.util.Base64.NO_WRAP)
        return cipher.doFinal(cipherBytes)
    }

    /**
     * Reset the biometric key and stored access token.
     * Called when the key is permanently invalidated (user changed biometrics).
     */
    private fun resetBiometricKey(context: Context) {
        // Remove from Keystore
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) Log.w(TAG, "Failed to delete biometric key", e)
        }
        // Clear stored access token
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_ACCESS_TOKEN_IV)
            .remove(PREF_ACCESS_TOKEN_CIPHER)
            .apply()
    }
}
