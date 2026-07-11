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

    fun initFromStore(): Pair<String, String>? {
        val data = psbtStore.consume() ?: return null
        unsignedPsbtBase64 = data.second
        _uiState.update {
            it.copy(
                walletId = data.first,
                psbtBase64 = data.second,
                isReviewLoading = true,
                transactionReview = null,
                highFeeAcknowledged = false,
                biometricForSendEnabled = settingsManager.isBiometricForSendEnabled()
            )
        }
        viewModelScope.launch {
            try {
                val review = bitcoinRepository.inspectPsbt(data.first, data.second)
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
        return data.first to data.second
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
        val signed = _uiState.value.signedPsbtBase64
        if (signed.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Sign the PSBT with the phone signer first") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBroadcasting = true, error = null) }
            try {
                val txid = bitcoinRepository.applyAndBroadcastPsbt(walletId, signed, unsignedPsbtBase64)
                _uiState.update { it.copy(isBroadcasting = false, txid = txid) }
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
}
