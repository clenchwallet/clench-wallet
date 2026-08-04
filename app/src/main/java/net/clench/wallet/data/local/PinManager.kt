package net.clench.wallet.data.local

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.provider.Settings
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
        private const val PREF_LAST_FAILURE_ELAPSED = "last_failure_elapsed_ms"
        private const val PREF_LAST_FAILURE_BOOT_COUNT = "last_failure_boot_count"
        // Removed in v0.3.24. Kept only so upgraded installs delete the wall-clock anchor.
        private const val PREF_LEGACY_LAST_FAILURE_WALL_TIME = "last_failure_ms"
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
            clearThrottlePreferences()
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
            prefs.edit { clearThrottlePreferences() }
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
            remove(PREF_PIN_HASH)
            clearThrottlePreferences()
        }
    }

    fun getRemainingDelayMs(): Long {
        val attempts = prefs.getInt(PREF_FAILED_ATTEMPTS, 0)
        if (attempts < 5) return 0L
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowBootCount = currentBootCount()
        val decision = PinThrottlePolicy.remainingDelay(
            attempts = attempts,
            storedElapsedMs = prefs.getLong(PREF_LAST_FAILURE_ELAPSED, -1L),
            storedBootCount = prefs.getInt(PREF_LAST_FAILURE_BOOT_COUNT, -1),
            nowElapsedMs = nowElapsed,
            nowBootCount = nowBootCount
        )
        if (decision.reanchor) {
            // A reboot, monotonic-clock reset, or pre-v0.3.24 throttle record restarts the
            // current delay. Rebooting or changing the wall clock can never shorten it.
            prefs.edit(commit = true) {
                putLong(PREF_LAST_FAILURE_ELAPSED, nowElapsed)
                putInt(PREF_LAST_FAILURE_BOOT_COUNT, nowBootCount)
                remove(PREF_LEGACY_LAST_FAILURE_WALL_TIME)
            }
        }
        return decision.remainingMs
    }

    private fun recordFailedAttempt() {
        val attempts = prefs.getInt(PREF_FAILED_ATTEMPTS, 0).coerceIn(0, 99) + 1
        prefs.edit(commit = true) {
            putInt(PREF_FAILED_ATTEMPTS, attempts)
            putLong(PREF_LAST_FAILURE_ELAPSED, SystemClock.elapsedRealtime())
            putInt(PREF_LAST_FAILURE_BOOT_COUNT, currentBootCount())
            remove(PREF_LEGACY_LAST_FAILURE_WALL_TIME)
        }
    }

    private fun currentBootCount(): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)

    private fun android.content.SharedPreferences.Editor.clearThrottlePreferences() {
        remove(PREF_FAILED_ATTEMPTS)
        remove(PREF_LAST_FAILURE_ELAPSED)
        remove(PREF_LAST_FAILURE_BOOT_COUNT)
        remove(PREF_LEGACY_LAST_FAILURE_WALL_TIME)
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
        var pinBytes: ByteArray? = null
        var result: ByteArray? = null
        return try {
            val key = getOrCreateHmacKey()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            pinBytes = ByteArray(pin.size) { pin[it].code.toByte() }
            val computed = mac.doFinal(pinBytes)
            result = computed
            android.util.Base64.encodeToString(computed, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } finally {
            pinBytes?.fill(0)
            result?.fill(0)
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray()
        val bBytes = b.toByteArray()
        return try {
            if (aBytes.size != bBytes.size) return false
            var diff: Byte = 0
            for (i in aBytes.indices) diff = diff xor (aBytes[i] xor bBytes[i])
            diff == 0.toByte()
        } finally {
            aBytes.fill(0)
            bBytes.fill(0)
        }
    }
}

internal data class PinThrottleDecision(
    val remainingMs: Long,
    val reanchor: Boolean
)

/** Pure policy kept separate from Android storage so rollback/reboot behavior is unit-testable. */
internal object PinThrottlePolicy {
    private const val FIRST_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 30L * 60L * 1_000L

    fun remainingDelay(
        attempts: Int,
        storedElapsedMs: Long,
        storedBootCount: Int,
        nowElapsedMs: Long,
        nowBootCount: Int
    ): PinThrottleDecision {
        val delayMs = delayForAttempts(attempts)
        if (delayMs == 0L) return PinThrottleDecision(0L, false)

        val missingAnchor = storedElapsedMs < 0L
        // Treat a counter becoming available/unavailable as a boot-boundary uncertainty too.
        // A platform quirk must never turn an unknown reboot into a shorter delay.
        val bootChanged = storedBootCount != nowBootCount
        val monotonicClockReset = nowElapsedMs < storedElapsedMs
        if (missingAnchor || bootChanged || monotonicClockReset) {
            return PinThrottleDecision(delayMs, true)
        }

        return PinThrottleDecision(
            remainingMs = (delayMs - (nowElapsedMs - storedElapsedMs)).coerceAtLeast(0L),
            reanchor = false
        )
    }

    fun delayForAttempts(attempts: Int): Long {
        if (attempts < 5) return 0L
        // Six doublings already exceed the 30-minute cap. Clamping also prevents
        // shift-count wraparound after an extremely large number of attempts.
        val exponent = (attempts - 5).coerceIn(0, 6)
        return (FIRST_DELAY_MS * (1L shl exponent)).coerceAtMost(MAX_DELAY_MS)
    }
}
