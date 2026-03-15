package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.TransactionItem
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    data class UiState(
        val walletName: String = "Clench Wallet",
        val balanceSat: Long = 0L,
        val transactions: List<TransactionItem> = emptyList(),
        val isLoading: Boolean = false,
        val isSyncing: Boolean = false,
        val syncError: String? = null,
        val error: String? = null,
        val isWatchOnly: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Load wallet name from DB
                try {
                    val wallets = bitcoinRepository.listWallets()
                    val thisWallet = wallets.find { it.id == walletId }
                    _uiState.update { it.copy(
                        walletName = thisWallet?.name ?: "My Wallet",
                        isWatchOnly = thisWallet?.isWatchOnly ?: false
                    ) }
                } catch (e: Exception) { /* ignore */ }

                // First show cached balance and transactions
                val balance = bitcoinRepository.getBalance(walletId)
                val txs = bitcoinRepository.getTransactions(walletId)
                _uiState.update {
                    it.copy(
                        balanceSat = balance.totalSat,
                        transactions = txs,
                        isLoading = false
                    )
                }

                // Then sync in background
                syncWallet(walletId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun syncWallet(walletId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncError = null) }
            try {
                val config = settingsManager.loadElectrumConfig()
                val balance = bitcoinRepository.syncWallet(walletId, config)
                val txs = bitcoinRepository.getTransactions(walletId)

                // Update balance and transactions after successful sync
                _uiState.update {
                    it.copy(
                        balanceSat = balance.totalSat,
                        transactions = txs,
                        isSyncing = false,
                        syncError = null
                    )
                }
            } catch (e: Exception) {
                // Keep cached data but set sync error
                _uiState.update { it.copy(isSyncing = false, syncError = e.message) }
            }
        }
    }
}
