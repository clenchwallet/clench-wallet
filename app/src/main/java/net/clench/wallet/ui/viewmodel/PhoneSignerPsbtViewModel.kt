package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.domain.repository.BuiltTransactionReview
import net.clench.wallet.data.local.SettingsManager
import javax.inject.Inject

@HiltViewModel
class PhoneSignerPsbtViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val psbtStore: PsbtStore,
    private val settingsManager: SettingsManager
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val psbtBase64: String = "",
        val signedPsbtBase64: String? = null,
        val transactionReview: BuiltTransactionReview? = null,
        val isReviewLoading: Boolean = false,
        val requiresHighFeeConfirmation: Boolean = false,
        val highFeeAcknowledged: Boolean = false,
        val biometricForSendEnabled: Boolean = true,
        val isSigning: Boolean = false,
        val isBroadcasting: Boolean = false,
        val txid: String? = null,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var unsignedPsbtBase64: String = ""
    private var sessionGeneration: Long = 0L
    class SigningToken internal constructor(
        internal val generation: Long,
        internal val walletId: String,
        internal val psbt: String
    )
    private var pendingSigning: SigningToken? = null

    private fun isSessionCurrent(generation: Long, walletId: String, psbt: String): Boolean =
        generation == sessionGeneration && _uiState.value.walletId == walletId &&
            _uiState.value.psbtBase64 == psbt


    internal fun initFromStore(
        expectedWalletId: String,
        pickerToken: String? = null,
        pickerPurpose: PsbtPickerPurpose? = null
    ): PsbtHandoff? {
        if (_uiState.value.isSigning || _uiState.value.isBroadcasting) return null
        val data = psbtStore.consume(
            expectedWalletId = expectedWalletId,
            expectedDeviceType = PHONE_SIGNER_DEVICE,
            pickerToken = pickerToken,
            pickerPurpose = pickerPurpose
        ) ?: run {
            if (pickerToken != null || pickerPurpose != null) {
                invalidatePickerSession("The file hand-off expired or did not match this signing session")
            }
            return null
        }
        val baselineGeneration = maxOf(sessionGeneration, data.sourceSessionGeneration)
        check(baselineGeneration < Long.MAX_VALUE) { "Signing session generation is exhausted" }
        sessionGeneration = baselineGeneration + 1L
        unsignedPsbtBase64 = data.originalUnsignedPsbtBase64
        _uiState.update {
            UiState(
                walletId = data.walletId,
                psbtBase64 = data.currentPsbtBase64,
                signedPsbtBase64 = null,
                isReviewLoading = true,
                transactionReview = null,
                highFeeAcknowledged = false,
                biometricForSendEnabled = settingsManager.isBiometricForSendEnabled()
            )
        }
        val generation = sessionGeneration
        viewModelScope.launch {
            try {
                val review = bitcoinRepository.inspectPsbt(
                    data.walletId,
                    data.currentPsbtBase64
                )
                if (!isSessionCurrent(generation, data.walletId, data.currentPsbtBase64)) return@launch
                SendViewModel.feeSafetyError(review)?.let { error(it) }
                _uiState.update {
                    it.copy(
                        transactionReview = review,
                        isReviewLoading = false,
                        requiresHighFeeConfirmation = SendViewModel.requiresHighFeeConfirmation(review)
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!isSessionCurrent(generation, data.walletId, data.currentPsbtBase64)) return@launch
                _uiState.update {
                    it.copy(
                        isReviewLoading = false,
                        error = "Could not verify the PSBT for signing: ${e.message}"
                    )
                }
            }
        }
        return data
    }

    /** Restore the unsigned policy after DocumentsUI; signing/authentication is repeated. */
    fun stageForDocumentPicker(): String? {
        val current = _uiState.value
        if (current.isSigning || current.isBroadcasting ||
            current.walletId.isBlank() || current.psbtBase64.isBlank() ||
            unsignedPsbtBase64.isBlank() || sessionGeneration <= 0L
        ) {
            _uiState.update { it.copy(error = "No idle PSBT session is available for export") }
            return null
        }
        return try {
            psbtStore.stageForPicker(
                walletId = current.walletId,
                originalUnsignedPsbtBase64 = unsignedPsbtBase64,
                currentPsbtBase64 = current.psbtBase64,
                deviceType = PHONE_SIGNER_DEVICE,
                sourceSessionGeneration = sessionGeneration,
                purpose = PsbtPickerPurpose.PHONE_EXPORT
            )
        } catch (_: Throwable) {
            _uiState.update { it.copy(error = "Another signing hand-off is already pending") }
            null
        }
    }

    fun discardDocumentPickerStage(token: String) {
        psbtStore.discardPickerStage(token)
    }

    fun cancelDocumentPickerRoundTrip(token: String, message: String = "File selection was cancelled") {
        psbtStore.discardPickerStage(token)
        invalidatePickerSession(message)
    }

    private fun invalidatePickerSession(message: String) {
        pendingSigning = null
        sessionGeneration += 1L
        unsignedPsbtBase64 = ""
        _uiState.value = UiState(error = message)
    }

    /** Reserve the exact reviewed transaction before opening the authentication prompt. */
    fun beginPhoneSigning(walletId: String): SigningToken? {
        val current = _uiState.value
        if (current.isSigning || current.isBroadcasting) return null
        if (walletId.isBlank() || walletId != current.walletId || current.psbtBase64.isBlank()) {
            setError("Signing request does not belong to the active wallet")
            return null
        }
        if (current.isReviewLoading || current.transactionReview == null) {
            setError("Wait for transaction verification before signing")
            return null
        }
        if (current.requiresHighFeeConfirmation && !current.highFeeAcknowledged) {
            setError("Confirm the high network fee before signing")
            return null
        }
        return SigningToken(sessionGeneration, walletId, current.psbtBase64).also {
            pendingSigning = it
            _uiState.update { state -> state.copy(isSigning = true, error = null) }
        }
    }

    fun cancelPhoneSigning(token: SigningToken) {
        if (pendingSigning === token) {
            pendingSigning = null
            _uiState.update { it.copy(isSigning = false) }
        }
    }

    fun cancelPendingAuthentication() {
        pendingSigning?.let(::cancelPhoneSigning)
    }

    fun signWithPhoneKeys(token: SigningToken) {
        if (pendingSigning !== token) return
        pendingSigning = null // Single-use even if the repository fails.
        if (!isSessionCurrent(token.generation, token.walletId, token.psbt)) return
        viewModelScope.launch {
            try {
                val signed = bitcoinRepository.signMultisigPsbtWithPhoneKeys(token.walletId, token.psbt)
                if (isSessionCurrent(token.generation, token.walletId, token.psbt)) {
                    _uiState.update { it.copy(isSigning = false, signedPsbtBase64 = signed) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (isSessionCurrent(token.generation, token.walletId, token.psbt)) {
                    _uiState.update { it.copy(isSigning = false, error = e.message ?: "Phone signing failed") }
                }
            }
        }
    }

    fun broadcastIfComplete(walletId: String) {
        val current = _uiState.value
        if (current.isSigning || current.isBroadcasting) return
        if (walletId.isBlank() || walletId != current.walletId) {
            _uiState.update { it.copy(error = "Security: broadcast request does not belong to the active wallet") }
            return
        }
        val signed = current.signedPsbtBase64
        if (signed.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Sign the PSBT with the phone signer first") }
            return
        }
        val sessionUnsignedPsbt = unsignedPsbtBase64
        if (sessionUnsignedPsbt.isBlank()) {
            _uiState.update { it.copy(error = "No PSBT is loaded") }
            return
        }
        val generation = sessionGeneration
        _uiState.update { it.copy(isBroadcasting = true, error = null) }
        viewModelScope.launch {
            try {
                val assertSessionCurrent = {
                    if (sessionGeneration != generation || _uiState.value.walletId != walletId ||
                        unsignedPsbtBase64 != sessionUnsignedPsbt
                    ) {
                        throw SecurityException("Signer session changed before the transaction could be broadcast")
                    }
                }
                assertSessionCurrent()
                val txid = bitcoinRepository.applyAndBroadcastPsbt(
                    walletId,
                    signed,
                    sessionUnsignedPsbt,
                    assertBroadcastAuthorized = assertSessionCurrent
                )
                assertSessionCurrent()
                _uiState.update { it.copy(isBroadcasting = false, txid = txid) }
            } catch (e: SecurityException) {
                if (!isSessionCurrent(generation, walletId, current.psbtBase64)) return@launch
                _uiState.update {
                    it.copy(isBroadcasting = false, error = "Security: ${e.message}")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!isSessionCurrent(generation, walletId, current.psbtBase64)) return@launch
                _uiState.update {
                    it.copy(
                        isBroadcasting = false,
                        error = e.message ?: "Broadcast failed. If more signatures are required, export the signed PSBT and continue with another signer."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun acknowledgeHighFee() {
        _uiState.update {
            if (it.requiresHighFeeConfirmation && it.transactionReview != null) {
                it.copy(highFeeAcknowledged = true, error = null)
            } else it
        }
    }

    private companion object {
        const val PHONE_SIGNER_DEVICE = "PHONE_SIGNER"
    }
}
