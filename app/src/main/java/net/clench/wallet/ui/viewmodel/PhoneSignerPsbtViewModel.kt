package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

    internal fun initFromStore(
        expectedWalletId: String,
        pickerToken: String? = null,
        pickerPurpose: PsbtPickerPurpose? = null
    ): PsbtHandoff? {
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
            it.copy(
                walletId = data.walletId,
                psbtBase64 = data.currentPsbtBase64,
                signedPsbtBase64 = null,
                isReviewLoading = true,
                transactionReview = null,
                highFeeAcknowledged = false,
                biometricForSendEnabled = settingsManager.isBiometricForSendEnabled()
            )
        }
        viewModelScope.launch {
            try {
                val review = bitcoinRepository.inspectPsbt(
                    data.walletId,
                    data.currentPsbtBase64
                )
                SendViewModel.feeSafetyError(review)?.let { error(it) }
                _uiState.update {
                    it.copy(
                        transactionReview = review,
                        isReviewLoading = false,
                        requiresHighFeeConfirmation = SendViewModel.requiresHighFeeConfirmation(review)
                    )
                }
            } catch (e: Exception) {
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
        sessionGeneration += 1L
        unsignedPsbtBase64 = ""
        _uiState.value = UiState(error = message)
    }

    fun signWithPhoneKeys(walletId: String) {
        val psbt = _uiState.value.psbtBase64
        if (psbt.isBlank()) {
            _uiState.update { it.copy(error = "No PSBT is loaded") }
            return
        }
        if (_uiState.value.transactionReview == null) {
            _uiState.update { it.copy(error = "Wait for transaction verification before signing") }
            return
        }
        if (_uiState.value.requiresHighFeeConfirmation && !_uiState.value.highFeeAcknowledged) {
            _uiState.update { it.copy(error = "Confirm the high network fee before signing") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSigning = true, error = null) }
            try {
                val signed = bitcoinRepository.signMultisigPsbtWithPhoneKeys(walletId, psbt)
                _uiState.update { it.copy(isSigning = false, signedPsbtBase64 = signed) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSigning = false, error = e.message ?: "Phone signing failed") }
            }
        }
    }

    fun broadcastIfComplete(walletId: String) {
        val current = _uiState.value
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
        viewModelScope.launch {
            _uiState.update { it.copy(isBroadcasting = true, error = null) }
            try {
                val assertSessionCurrent = {
                    if (_uiState.value.walletId != walletId ||
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
                _uiState.update {
                    it.copy(isBroadcasting = false, error = "Security: ${e.message}")
                }
            } catch (e: Exception) {
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
