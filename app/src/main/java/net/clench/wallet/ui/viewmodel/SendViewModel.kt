package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class SendViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val toAddress: String = "",
        val amountSat: String = "",
        val feeRate: String = "2",
        val sendMax: Boolean = false,
        val txHex: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) = _uiState.update { it.copy(walletId = walletId) }
    fun setAddress(addr: String) = _uiState.update { it.copy(toAddress = addr, error = null) }
    fun setAmount(amt: String) = _uiState.update { it.copy(amountSat = amt) }
    fun setFeeRate(rate: String) = _uiState.update { it.copy(feeRate = rate) }
    fun setSendMax(max: Boolean) = _uiState.update { it.copy(sendMax = max, amountSat = if (max) "" else it.amountSat) }

    fun buildTx() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val amountSat = if (state.sendMax) null else state.amountSat.toLongOrNull()
                val txHex = bitcoinRepository.buildTransaction(
                    walletId = state.walletId,
                    toAddress = state.toAddress.trim(),
                    amountSat = amountSat,
                    feeRateSatPerVbyte = state.feeRate.toFloatOrNull() ?: 2f
                )
                _uiState.update { it.copy(txHex = txHex, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun broadcast(onSuccess: () -> Unit) {
        val state = _uiState.value
        val txHex = state.txHex ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                bitcoinRepository.broadcastTransaction(ElectrumConfig(), txHex)
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
