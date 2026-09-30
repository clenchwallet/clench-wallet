package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.RawTransactionPayload
import net.clench.wallet.domain.model.RawTransactionPreview
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.security.ContextFreeRawSignaturePolicy
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

    private var inputGeneration = 0L
    private var activeBroadcast: Any? = null

    fun setInput(input: String) {
        inputGeneration += 1L
        _uiState.update { it.copy(input = input, preview = null, error = null, broadcastTxid = null) }
    }

    fun setError(message: String) {
        inputGeneration += 1L
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
        if (activeBroadcast != null) return
        val preview = _uiState.value.preview ?: run {
            preview()
            _uiState.value.preview
        } ?: return
        if (settingsManager.isOfflineMode()) {
            _uiState.update { it.copy(error = "Offline mode blocks transaction broadcast") }
            return
        }
        val generation = inputGeneration
        val operation = Any()
        activeBroadcast = operation
        _uiState.update { it.copy(isBroadcasting = true, error = null) }
        viewModelScope.launch {
            try {
                // Editing before dispatch revokes the queued request. An already sent
                // request cannot be undone, so keep it reserved until it completes.
                if (generation != inputGeneration) return@launch
                // The raw tool has no original PSBT or prevouts, so it cannot
                // perform the coordinator's full policy validation. Still fail
                // closed on every recognizable weak signature-hash flag before
                // loading network configuration or making a network request.
                ContextFreeRawSignaturePolicy.validate(
                    RawTransactionPayload.decode(preview.normalizedHex)
                )
                val txid = bitcoinRepository.broadcastTransaction(settingsManager.loadElectrumConfig(), preview.normalizedHex)
                if (generation == inputGeneration) {
                    _uiState.update { it.copy(broadcastTxid = txid) }
                }
            } catch (e: SecurityException) {
                if (generation != inputGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isBroadcasting = false,
                        error = "Security check failed: ${e.message ?: "unsafe raw transaction"}"
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == inputGeneration) {
                    _uiState.update { it.copy(error = e.message ?: "Broadcast failed") }
                }
            } finally {
                if (activeBroadcast === operation) {
                    activeBroadcast = null
                    _uiState.update { it.copy(isBroadcasting = false) }
                }
            }
        }
    }
}
