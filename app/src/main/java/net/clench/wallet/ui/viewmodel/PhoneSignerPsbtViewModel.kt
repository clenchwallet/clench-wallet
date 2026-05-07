package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class PhoneSignerPsbtViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val psbtStore: PsbtStore
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val psbtBase64: String = "",
        val signedPsbtBase64: String? = null,
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
        _uiState.update { it.copy(walletId = data.first, psbtBase64 = data.second) }
        return data.first to data.second
    }

    fun signWithPhoneKeys(walletId: String) {
        val psbt = _uiState.value.psbtBase64
        if (psbt.isBlank()) {
            _uiState.update { it.copy(error = "No PSBT is loaded") }
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
}
