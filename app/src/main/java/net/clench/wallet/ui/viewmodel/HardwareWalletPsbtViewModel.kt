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
class HardwareWalletPsbtViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val txid: String? = null,
        val error: String? = null,
        val isBroadcasting: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Called when a signed PSBT is received from the hardware wallet (via QR, NFC, or file).
     * Validates and broadcasts the transaction.
     */
    fun onSignedPsbtReceived(walletId: String, signedPsbtBase64: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBroadcasting = true, error = null) }
            try {
                val txid = bitcoinRepository.applyAndBroadcastPsbt(walletId, signedPsbtBase64)
                _uiState.update { it.copy(isBroadcasting = false, txid = txid) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBroadcasting = false, error = e.message ?: "Broadcast failed") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
