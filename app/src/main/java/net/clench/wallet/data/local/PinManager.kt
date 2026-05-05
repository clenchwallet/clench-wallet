package net.clench.wallet.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.experimental.xor

/**
 * Manages a Clench-specific numeric PIN for app lock.
 *
 * The PIN is stored as an HMAC-SHA256 using an Android Keystore-backed key.
 * The PIN itself is never stored — only the HMAC digest.
 *
 * Security design:
 * - PIN accepted as CharArray, converted to bytes without creating a String, zeroed after use
 * - HMAC key stored in Android Keystore (hardware-backed when available)
 * - No PIN wipe on failure — exponential time delay enforced after 5 failed attempts
 * - Users are informed of the no-wipe policy at PIN setup time
 * - Constant-time comparison used to prevent timing attacks
 */
@Singleton
class PinManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val PIN_KEY_ALIAS = "clench_pin_hmac_key"
        private const val PREFS_NAME = "clench_pin_prefs"
        private const val PREF_PIN_HASH = "pin_hmac"
        private const val PREF_FAILED_ATTEMPTS = "failed_attempts"
        private const val PREF_LAST_FAILURE_TIME = "last_failure_ms"
        const val MIN_PIN_LENGTH = 6
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPinSet(): Boolean = prefs.contains(PREF_PIN_HASH)

    fun setPin(pin: CharArray): String? {
        if (pin.size < MIN_PIN_LENGTH) { pin.fill('0'); return "PIN must be at least $MIN_PIN_LENGTH digits" }
        if (!pin.all { it.isDigit() }) { pin.fill('0'); return "PIN must contain only digits" }
        val hash = computeHmac(pin)
        pin.fill('0')
        if (hash == null) return "Failed to set PIN — Keystore error"
        prefs.edit {
            putString(PREF_PIN_HASH, hash)
            remove(PREF_FAILED_ATTEMPTS)
            remove(PREF_LAST_FAILURE_TIME)
        }
        return null
    }

    fun verifyPin(pin: CharArray): String? {
        val delayMs = getRemainingDelayMs()
        if (delayMs > 0) {
            pin.fill('0')
            return "Too many attempts. Try again in ${(delayMs / 1000) + 1}s"
        }
        val storedHash = prefs.getString(PREF_PIN_HASH, null)
        if (storedHash == null) { pin.fill('0'); return "No PIN set" }
        val inputHash = computeHmac(pin)
        pin.fill('0')
        return if (inputHash != null && constantTimeEquals(inputHash, storedHash)) {
            prefs.edit { remove(PREF_FAILED_ATTEMPTS); remove(PREF_LAST_FAILURE_TIME) }
            null
        } else {
            recordFailedAttempt()
            val remaining = getRemainingDelayMs()
            if (remaining > 0) "Incorrect PIN. Too many attempts — wait ${(remaining / 1000) + 1}s"
            else "Incorrect PIN"
        }
    }

    fun clearPin() {
        prefs.edit {
            remove(PREF_PIN_HASH); remove(PREF_FAILED_ATTEMPTS); remove(PREF_LAST_FAILURE_TIME)
        }
    }

    fun getRemainingDelayMs(): Long {
        val attempts = prefs.getInt(PREF_FAILED_ATTEMPTS, 0)
        if (attempts < 5) return 0L
        val lastFailureTime = prefs.getLong(PREF_LAST_FAILURE_TIME, 0L)
        val delayMs = exponentialDelay(attempts)
        val elapsed = System.currentTimeMillis() - lastFailureTime
        return maxOf(0L, delayMs - elapsed)
    }

    private fun recordFailedAttempt() {
        val attempts = prefs.getInt(PREF_FAILED_ATTEMPTS, 0) + 1
        prefs.edit { putInt(PREF_FAILED_ATTEMPTS, attempts); putLong(PREF_LAST_FAILURE_TIME, System.currentTimeMillis()) }
    }

    private fun exponentialDelay(attempts: Int): Long {
        if (attempts < 5) return 0L
        val multiplier = 1L shl (attempts - 5)
        return minOf(30_000L * multiplier, 30 * 60 * 1000L)
    }

    private fun getOrCreateHmacKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(PIN_KEY_ALIAS, null)?.let { return it as SecretKey }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(PIN_KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun computeHmac(pin: CharArray): String? {
        return try {
            val key = getOrCreateHmacKey()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            val pinBytes = ByteArray(pin.size) { pin[it].code.toByte() }
            val result = mac.doFinal(pinBytes)
            pinBytes.fill(0)
            android.util.Base64.encodeToString(result, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(); val bBytes = b.toByteArray()
        if (aBytes.size != bBytes.size) return false
        var diff: Byte = 0
        for (i in aBytes.indices) diff = diff xor (aBytes[i] xor bBytes[i])
        return diff == 0.toByte()
    }
}
