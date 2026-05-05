package net.clench.wallet.ui.viewmodel

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory store for PSBT data between screens.
 * Avoids passing large base64 strings via navigation route arguments,
 * which would leak into back stack logs and URL-encoded route strings.
 */
@Singleton
class PsbtStore @Inject constructor() {
    private var pending: Triple<String, String, String>? = null  // (walletId, psbtBase64, deviceType)

    /**
     * Store a PSBT for hardware wallet signing.
     * @throws IllegalStateException if a PSBT is already pending (race condition protection)
     */
    fun store(walletId: String, psbtBase64: String, deviceType: String) {
        val current = pending
        if (current != null) {
            throw IllegalStateException(
                "A PSBT is already pending for wallet ${current.first}. " +
                "Complete or cancel the current hardware wallet signing flow before starting a new send."
            )
        }
        pending = Triple(walletId, psbtBase64, deviceType)
    }

    /**
     * Consume the stored PSBT data (returns and clears).
     * @return Triple of (walletId, psbtBase64, deviceType) or null if nothing stored
     */
    fun consume(): Triple<String, String, String>? {
        val result = pending
        pending = null
        return result
    }

    /**
     * Peek at the stored PSBT base64 without consuming.
     * Used by the screen to access the original unsigned PSBT for validation.
     */
    fun peekPsbtBase64(): String? = pending?.second

    /**
     * Force-clear any pending PSBT. Use when the user explicitly cancels the signing flow.
     */
    fun clear() {
        pending = null
    }
}
