package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class SendViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val isWatchOnly: Boolean = false,
        val toAddress: String = "",
        val amountSat: String = "",
        val feeRate: String = "2",
        val sendMax: Boolean = false,
        val txHex: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val availableBalanceSat: Long = 0L,
        val utxoTxid: String? = null,
        val utxoVout: Int? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) {
        _uiState.update { it.copy(walletId = walletId) }
        viewModelScope.launch {
            try {
                val wallets = bitcoinRepository.listWallets()
                val wallet = wallets.find { it.id == walletId }
                val balance = bitcoinRepository.getBalance(walletId)
                _uiState.update { it.copy(
                    isWatchOnly = wallet?.isWatchOnly ?: false,
                    availableBalanceSat = balance.spendableSat
                ) }
            } catch (e: Exception) { /* show 0 */ }
        }
    }
    fun setUtxo(txid: String?, vout: Int? = 0) {
        _uiState.update { it.copy(utxoTxid = txid, utxoVout = vout) }
    }

    fun setAddress(addr: String) = _uiState.update { it.copy(toAddress = addr, error = null) }
    fun setError(msg: String) = _uiState.update { it.copy(error = msg) }
    fun setAmount(amt: String) = _uiState.update { it.copy(amountSat = amt) }
    fun setFeeRate(rate: String) = _uiState.update { it.copy(feeRate = rate) }
    fun setSendMax(max: Boolean) = _uiState.update { it.copy(sendMax = max, amountSat = if (max) "" else it.amountSat) }

    fun buildTx() {
        val state = _uiState.value

        // Validate inputs before building transaction
        if (!state.sendMax) {
            val amount = state.amountSat.toLongOrNull()
            if (amount == null || amount <= 0) {
                _uiState.update { it.copy(error = "Please enter a valid amount in satoshis") }
                return
            }
        }

        if (state.toAddress.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a recipient address") }
            return
        }

        val feeRate = state.feeRate.toFloatOrNull()
        if (feeRate == null || feeRate < 1f) {
            _uiState.update { it.copy(error = "Please enter a valid fee rate (min 1 sat/vB)") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Block send from watch-only wallet at VM level (belt-and-suspenders)
            if (_uiState.value.isWatchOnly) {
                _uiState.update { it.copy(isLoading = false, error = "Cannot send from a watch-only wallet") }
                return@launch
            }

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

    fun broadcast(onSuccess: (walletId: String) -> Unit) {
        val state = _uiState.value
        val txHex = state.txHex ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val config = settingsManager.loadElectrumConfig()
                bitcoinRepository.broadcastTransaction(config, txHex)
                // Trigger a sync so HomeScreen shows updated balance immediately
                try { bitcoinRepository.syncWallet(state.walletId, config) } catch (_: Exception) {}
                onSuccess(state.walletId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
