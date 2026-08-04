package net.clench.wallet.data.local

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * The security boundary Android reports for a non-exportable Keystore key.
 *
 * Hardware backing is a runtime property of the key and device, not a property Clench can
 * assume from use of the Android Keystore API. Older Android releases only report whether a
 * key is inside secure hardware; they cannot distinguish StrongBox from a TEE.
 */
enum class AndroidKeystoreProtection(val displayName: String) {
    STRONGBOX("StrongBox"),
    TRUSTED_ENVIRONMENT("Trusted Execution Environment (TEE)"),
    SECURE_HARDWARE_UNSPECIFIED("Secure hardware (type unavailable on this Android version)"),
    SOFTWARE("Software-backed Android Keystore"),
    UNKNOWN_SECURE("Secure environment (type not reported by Android)"),
    UNKNOWN("Unknown — Android did not report the key security level")
}

internal fun classifyAndroidKeystoreProtection(
    apiLevel: Int,
    platformSecurityLevel: Int?,
    insideSecureHardware: Boolean?
): AndroidKeystoreProtection {
    if (apiLevel >= Build.VERSION_CODES.S && platformSecurityLevel != null) {
        return when (platformSecurityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> AndroidKeystoreProtection.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> AndroidKeystoreProtection.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> AndroidKeystoreProtection.SOFTWARE
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> AndroidKeystoreProtection.UNKNOWN_SECURE
            else -> AndroidKeystoreProtection.UNKNOWN
        }
    }

    return when (insideSecureHardware) {
        true -> AndroidKeystoreProtection.SECURE_HARDWARE_UNSPECIFIED
        false -> AndroidKeystoreProtection.SOFTWARE
        null -> AndroidKeystoreProtection.UNKNOWN
    }
}

/** Inspect an existing Android Keystore secret key without exporting its bytes. */
@Suppress("DEPRECATION")
internal fun inspectAndroidKeystoreProtection(alias: String): AndroidKeystoreProtection {
    return runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        val key = keyStore.getKey(alias, null) as? SecretKey
            ?: return@runCatching AndroidKeystoreProtection.UNKNOWN
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE_PROVIDER)
        val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        classifyAndroidKeystoreProtection(
            apiLevel = Build.VERSION.SDK_INT,
            platformSecurityLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                keyInfo.getSecurityLevel()
            } else {
                null
            },
            insideSecureHardware = keyInfo.isInsideSecureHardware()
        )
    }.getOrDefault(AndroidKeystoreProtection.UNKNOWN)
}

private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
