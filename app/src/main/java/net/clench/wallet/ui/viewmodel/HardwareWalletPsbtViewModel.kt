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
import javax.inject.Inject

@HiltViewModel
class HardwareWalletPsbtViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val psbtStore: PsbtStore
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val txid: String? = null,
        val error: String? = null,
        val isBroadcasting: Boolean = false,
        val isProcessingSignedPsbt: Boolean = false,
        val signedPsbtBase64: String? = null,
        val readyToBroadcast: Boolean = false,
        val hasCollectedSignature: Boolean = false,
        val signingMessage: String? = null,
        val walletId: String = "",
        val psbtBase64: String = "",
        val deviceType: String = "",
        val transactionReview: BuiltTransactionReview? = null,
        val isReviewLoading: Boolean = false,
        val reviewAcknowledged: Boolean = false,
        val requiresHighFeeConfirmation: Boolean = false,
        val highFeeAcknowledged: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    // Store the original unsigned PSBT for validation at broadcast time
    private var unsignedPsbtBase64: String = ""

    /**
     * Initialize from PsbtStore — called once when the screen opens.
     */
    fun initFromStore(): Triple<String, String, String>? {
        val data = psbtStore.consume()
        if (data != null) {
            unsignedPsbtBase64 = data.second
            _uiState.update {
                it.copy(
                    walletId = data.first,
                    psbtBase64 = data.second,
                    deviceType = data.third,
                    signedPsbtBase64 = null,
                    readyToBroadcast = false,
                    hasCollectedSignature = false,
                    signingMessage = null,
                    transactionReview = null,
                    isReviewLoading = true,
                    reviewAcknowledged = false,
                    requiresHighFeeConfirmation = false,
                    highFeeAcknowledged = false
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
                            error = "Could not verify the PSBT before hardware signing: ${e.message}"
                        )
                    }
                }
            }
        }
        return data
    }

    /**
     * Called when signer data is received from the hardware wallet (via QR, NFC, or file).
     * Merge it into the current PSBT and only enable broadcast after the policy finalizes.
     */
    fun onSignedPsbtReceived(walletId: String, signedPsbtPayload: String) {
        val current = _uiState.value
        if (!current.reviewAcknowledged || current.transactionReview == null) {
            _uiState.update { it.copy(error = "Review and approve the unsigned transaction before importing signatures") }
            return
        }
        val currentPsbtBase64 = current.psbtBase64
        if (currentPsbtBase64.isBlank() || unsignedPsbtBase64.isBlank()) {
            _uiState.update { it.copy(error = "No PSBT is loaded") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    walletId = walletId,
                    isProcessingSignedPsbt = true,
                    signedPsbtBase64 = null,
                    readyToBroadcast = false,
                    signingMessage = null,
                    error = null
                )
            }
            try {
                val progress = bitcoinRepository.mergeSignedPsbt(
                    unsignedPsbtBase64 = unsignedPsbtBase64,
                    currentPsbtBase64 = currentPsbtBase64,
                    signedPsbtPayload = signedPsbtPayload
                )
                _uiState.update {
                    it.copy(
                        isProcessingSignedPsbt = false,
                        psbtBase64 = progress.psbtBase64,
                        signedPsbtBase64 = if (progress.readyToBroadcast) progress.psbtBase64 else null,
                        readyToBroadcast = progress.readyToBroadcast,
                        hasCollectedSignature = true,
                        signingMessage = progress.message,
                        error = null
                    )
                }
            } catch (e: SecurityException) {
                _uiState.update {
                    it.copy(
                        isProcessingSignedPsbt = false,
                        error = "Security: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessingSignedPsbt = false,
                        error = e.message ?: "Signed PSBT import failed"
                    )
                }
            }
        }
    }

    /**
     * Validate outputs match the original unsigned PSBT, finalize, and broadcast.
     */
    fun broadcastSignedPsbt(walletId: String) {
        val signedPsbtBase64 = _uiState.value.signedPsbtBase64
        if (signedPsbtBase64.isNullOrBlank() || !_uiState.value.readyToBroadcast) {
            _uiState.update { it.copy(error = "Collect enough signatures before broadcasting") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBroadcasting = true, error = null) }
            try {
                val txid = bitcoinRepository.applyAndBroadcastPsbt(walletId, signedPsbtBase64, unsignedPsbtBase64)
                _uiState.update {
                    it.copy(
                        isBroadcasting = false,
                        signedPsbtBase64 = null,
                        readyToBroadcast = false,
                        txid = txid
                    )
                }
            } catch (e: SecurityException) {
                _uiState.update { it.copy(isBroadcasting = false, error = "Security: ${e.message}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBroadcasting = false, error = e.message ?: "Broadcast failed") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun acknowledgeHighFee() {
        _uiState.update {
            if (it.requiresHighFeeConfirmation && it.transactionReview != null) {
                it.copy(highFeeAcknowledged = true, error = null)
            } else it
        }
    }

    fun acknowledgeReview() {
        _uiState.update {
            if (it.transactionReview != null &&
                (!it.requiresHighFeeConfirmation || it.highFeeAcknowledged)
            ) it.copy(reviewAcknowledged = true, error = null) else it
        }
    }
}
