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

    fun store(walletId: String, psbtBase64: String, deviceType: String) {
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
}
