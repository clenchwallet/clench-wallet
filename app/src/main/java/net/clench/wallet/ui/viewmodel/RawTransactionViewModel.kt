package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.RawTransactionPayload
import net.clench.wallet.domain.model.RawTransactionPreview
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.Network
import javax.inject.Inject

@HiltViewModel
class RawTransactionViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    data class UiState(
        val input: String = "",
        val preview: RawTransactionPreview? = null,
        val error: String? = null,
        val isBroadcasting: Boolean = false,
        val broadcastTxid: String? = null,
        val isOfflineMode: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState(isOfflineMode = settingsManager.isOfflineMode()))
    val uiState = _uiState.asStateFlow()

    fun setInput(input: String) {
        _uiState.update { it.copy(input = input, preview = null, error = null, broadcastTxid = null) }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message, preview = null, broadcastTxid = null) }
    }

    fun preview() {
        val input = _uiState.value.input
        val network = if (settingsManager.isTestnet()) Network.TESTNET else Network.BITCOIN
        val preview = runCatching { RawTransactionPayload.parse(input, network) }.getOrElse { e ->
            _uiState.update { it.copy(preview = null, error = e.message ?: "Could not parse raw transaction") }
            return
        }
        _uiState.update { it.copy(preview = preview, error = null) }
    }

    fun broadcast() {
        val preview = _uiState.value.preview ?: run {
            preview()
            _uiState.value.preview
        } ?: return
        if (settingsManager.isOfflineMode()) {
            _uiState.update { it.copy(error = "Offline mode blocks transaction broadcast") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBroadcasting = true, error = null) }
            try {
                val txid = bitcoinRepository.broadcastTransaction(settingsManager.loadElectrumConfig(), preview.normalizedHex)
                _uiState.update { it.copy(isBroadcasting = false, broadcastTxid = txid) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBroadcasting = false, error = e.message ?: "Broadcast failed") }
            }
        }
    }
}
