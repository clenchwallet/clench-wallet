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
    private val bitcoinRepository: BitcoinRepository,
    private val psbtStore: PsbtStore
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val txid: String? = null,
        val error: String? = null,
        val isBroadcasting: Boolean = false,
        val walletId: String = "",
        val psbtBase64: String = "",
        val deviceType: String = ""
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
            _uiState.update { it.copy(walletId = data.first, psbtBase64 = data.second, deviceType = data.third) }
        }
        return data
    }

    /**
     * Called when a signed PSBT is received from the hardware wallet (via QR, NFC, or file).
     * Validates outputs match the original unsigned PSBT, then broadcasts.
     */
    fun onSignedPsbtReceived(walletId: String, signedPsbtBase64: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBroadcasting = true, error = null) }
            try {
                val txid = bitcoinRepository.applyAndBroadcastPsbt(walletId, signedPsbtBase64, unsignedPsbtBase64)
                _uiState.update { it.copy(isBroadcasting = false, txid = txid) }
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
}
