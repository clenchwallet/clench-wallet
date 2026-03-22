package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class WalletListViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val wallets: List<WalletData> = emptyList(),
        val isLoading: Boolean = false,
        val deletedWalletId: String? = null,
        // If wallets remain after deletion, navigate to this wallet's home
        val navigateToWalletId: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val wallets = bitcoinRepository.listWallets()
                _uiState.update { it.copy(wallets = wallets, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun deleteWallet(walletId: String) {
        viewModelScope.launch {
            try {
                bitcoinRepository.deleteWallet(walletId)
                val remaining = bitcoinRepository.listWallets()
                if (remaining.isEmpty()) {
                    // No wallets left — signal navigate to Welcome
                    _uiState.update { it.copy(wallets = remaining, deletedWalletId = walletId) }
                } else {
                    // Wallets remain — navigate to first remaining wallet
                    _uiState.update { it.copy(wallets = remaining, navigateToWalletId = remaining.first().id) }
                }
            } catch (_: Exception) { }
        }
    }

    fun clearDeletedState() {
        _uiState.update { it.copy(deletedWalletId = null, navigateToWalletId = null) }
    }
}
