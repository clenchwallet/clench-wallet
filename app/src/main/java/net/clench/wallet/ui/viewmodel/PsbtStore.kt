package net.clench.wallet.ui.viewmodel

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import net.clench.wallet.security.PsbtSafety

internal enum class PsbtPickerPurpose {
    HARDWARE_IMPORT,
    HARDWARE_EXPORT,
    PHONE_EXPORT
}

internal data class PsbtHandoff(
    val walletId: String,
    /** The immutable policy baseline created before any external signature was collected. */
    val originalUnsignedPsbtBase64: String,
    /** The PSBT currently being transferred; it may already contain valid multisig partials. */
    val currentPsbtBase64: String,
    val deviceType: String,
    val sourceSessionGeneration: Long
)

/**
 * Process-memory-only hand-off for PSBT routes and system document picker reconstruction.
 *
 * A picker stage is one-shot, time bounded, and tied to an unguessable token embedded in the
 * typed picker request. Android process death drops the entire object. The original unsigned
 * policy is deliberately stored separately from the current, possibly partially signed PSBT.
 */
@Singleton
class PsbtStore internal constructor(
    private val monotonicNanos: () -> Long,
    private val tokenBytes: (Int) -> ByteArray
) {
    @Inject
    constructor() : this(
        monotonicNanos = System::nanoTime,
        tokenBytes = { size -> ByteArray(size).also(SecureRandom()::nextBytes) }
    )

    private data class PickerStage(
        val token: String,
        val purpose: PsbtPickerPurpose,
        val originalUnsignedHash: String,
        val currentHash: String,
        val expiresAtNanos: Long
    )

    private data class Pending(
        val walletId: String,
        val originalUnsignedPsbtBase64: String,
        val currentPsbtBase64: String,
        val deviceType: String,
        val sourceSessionGeneration: Long,
        val pickerStage: PickerStage?
    )

    private var pending: Pending? = null

    /** Initial in-process route hand-off. The current PSBT is also the unsigned policy baseline. */
    @Synchronized
    fun store(walletId: String, psbtBase64: String, deviceType: String) {
        validatePsbt(psbtBase64)
        clearIfExpiredLocked()
        val current = pending
        if (current != null) {
            throw IllegalStateException(
                "A PSBT is already pending for wallet ${current.walletId}. " +
                    "Complete or cancel the current signing flow before starting another send."
            )
        }
        pending = Pending(
            walletId = walletId,
            originalUnsignedPsbtBase64 = psbtBase64,
            currentPsbtBase64 = psbtBase64,
            deviceType = deviceType,
            sourceSessionGeneration = 0L,
            pickerStage = null
        )
    }

    /**
     * Stage an exact signing session before launching DocumentsUI.
     *
     * The returned token is public routing metadata, not a signing secret. It must be embedded in
     * the corresponding typed [net.clench.wallet.ui.picker.PickerRequest].
     */
    @Synchronized
    internal fun stageForPicker(
        walletId: String,
        originalUnsignedPsbtBase64: String,
        currentPsbtBase64: String,
        deviceType: String,
        sourceSessionGeneration: Long,
        purpose: PsbtPickerPurpose
    ): String {
        require(sourceSessionGeneration > 0L) { "Signing session generation is invalid" }
        validatePsbt(originalUnsignedPsbtBase64)
        validatePsbt(currentPsbtBase64)
        clearIfExpiredLocked()
        check(pending == null) { "Another PSBT hand-off is already pending" }

        val token = tokenBytes(TOKEN_BYTES).also {
            require(it.size == TOKEN_BYTES) { "PSBT hand-off token source returned the wrong size" }
        }.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val now = monotonicNanos()
        pending = Pending(
            walletId = walletId,
            originalUnsignedPsbtBase64 = originalUnsignedPsbtBase64,
            currentPsbtBase64 = currentPsbtBase64,
            deviceType = deviceType,
            sourceSessionGeneration = sourceSessionGeneration,
            pickerStage = PickerStage(
                token = token,
                purpose = purpose,
                originalUnsignedHash = psbtHash(originalUnsignedPsbtBase64),
                currentHash = psbtHash(currentPsbtBase64),
                expiresAtNanos = now.saturatingAdd(PICKER_TTL_NANOS)
            )
        )
        EXPIRY_EXECUTOR.schedule(
            { discardPickerStage(token) },
            PICKER_TTL_NANOS,
            TimeUnit.NANOSECONDS
        )
        return token
    }

    /**
     * Atomically validates and consumes a route hand-off. Any mismatch fails closed and destroys
     * the pending record so it cannot later be claimed by a different route or picker request.
     */
    @Synchronized
    internal fun consume(
        expectedWalletId: String,
        expectedDeviceType: String,
        pickerToken: String? = null,
        pickerPurpose: PsbtPickerPurpose? = null
    ): PsbtHandoff? {
        clearIfExpiredLocked()
        val current = pending ?: return null
        val stage = current.pickerStage
        val matches = current.walletId == expectedWalletId &&
            current.deviceType == expectedDeviceType &&
            if (stage == null) {
                pickerToken == null && pickerPurpose == null
            } else {
                pickerToken != null && pickerPurpose != null &&
                    stage.token == pickerToken &&
                    stage.purpose == pickerPurpose &&
                    stage.originalUnsignedHash == psbtHash(current.originalUnsignedPsbtBase64) &&
                    stage.currentHash == psbtHash(current.currentPsbtBase64)
            }

        pending = null
        if (!matches) return null
        return PsbtHandoff(
            walletId = current.walletId,
            originalUnsignedPsbtBase64 = current.originalUnsignedPsbtBase64,
            currentPsbtBase64 = current.currentPsbtBase64,
            deviceType = current.deviceType,
            sourceSessionGeneration = current.sourceSessionGeneration
        )
    }

    /** Compare-and-clear prevents an old picker callback from deleting a newer hand-off. */
    @Synchronized
    fun discardPickerStage(token: String) {
        clearIfExpiredLocked()
        if (pending?.pickerStage?.token == token) pending = null
    }

    @Synchronized
    fun clear() {
        pending = null
    }

    @Synchronized
    internal fun hasPendingForTest(): Boolean {
        clearIfExpiredLocked()
        return pending != null
    }

    private fun clearIfExpiredLocked() {
        val expiresAt = pending?.pickerStage?.expiresAtNanos ?: return
        if (monotonicNanos() >= expiresAt) pending = null
    }

    private fun validatePsbt(psbtBase64: String) {
        require(psbtBase64.length <= MAX_PSBT_BASE64_CHARS) {
            "PSBT exceeds the in-memory safety limit"
        }
        PsbtSafety.inspectBase64(psbtBase64)
    }

    private fun psbtHash(psbtBase64: String): String {
        val bytes = Base64.getDecoder().decode(psbtBase64.filterNot(Char::isWhitespace))
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun Long.saturatingAdd(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    companion object {
        const val MAX_PSBT_BASE64_CHARS = 6 * 1024 * 1024
        internal const val PICKER_TTL_NANOS = 10L * 60L * 1_000_000_000L
        private const val TOKEN_BYTES = 16
        private val EXPIRY_EXECUTOR = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "clench-psbt-handoff-expiry").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }
}
