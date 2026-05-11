package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.Address
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.KeychainKind
import javax.inject.Inject

@HiltViewModel
class AddressListViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val selectedTab: Int = 0, // 0 = Receive, 1 = Change
        val receiveAddresses: List<Address> = emptyList(),
        val changeAddresses: List<Address> = emptyList(),
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val error: String? = null,
        val receiveCount: Int = 20,
        val changeCount: Int = 20
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) {
        _uiState.update { it.copy(walletId = walletId, isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                runCatching {
                    bitcoinRepository.syncWallet(walletId, settingsManager.loadElectrumConfig())
                }
                val receiveAddrs = bitcoinRepository.getAddresses(walletId, KeychainKind.EXTERNAL, 20)
                val changeAddrs = bitcoinRepository.getAddresses(walletId, KeychainKind.INTERNAL, 20)

                // Calculate how many to show: max(20, usedCount + 20)
                val receiveUsed = receiveAddrs.count { it.used }
                val changeUsed = changeAddrs.count { it.used }
                val receiveCount = maxOf(20, receiveUsed + 20)
                val changeCount = maxOf(20, changeUsed + 20)

                // Re-fetch if we need more
                val finalReceive = if (receiveCount > 20) {
                    bitcoinRepository.getAddresses(walletId, KeychainKind.EXTERNAL, receiveCount)
                } else receiveAddrs

                val finalChange = if (changeCount > 20) {
                    bitcoinRepository.getAddresses(walletId, KeychainKind.INTERNAL, changeCount)
                } else changeAddrs

                _uiState.update { it.copy(
                    receiveAddresses = finalReceive,
                    changeAddresses = finalChange,
                    receiveCount = receiveCount,
                    changeCount = changeCount,
                    isLoading = false
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun loadMore() {
        val state = _uiState.value
        val walletId = state.walletId
        if (walletId.isBlank()) return

        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            try {
                if (state.selectedTab == 0) {
                    val newCount = state.receiveCount + 20
                    val addrs = bitcoinRepository.getAddresses(walletId, KeychainKind.EXTERNAL, newCount)
                    _uiState.update { it.copy(
                        receiveAddresses = addrs,
                        receiveCount = newCount,
                        isLoadingMore = false
                    ) }
                } else {
                    val newCount = state.changeCount + 20
                    val addrs = bitcoinRepository.getAddresses(walletId, KeychainKind.INTERNAL, newCount)
                    _uiState.update { it.copy(
                        changeAddresses = addrs,
                        changeCount = newCount,
                        isLoadingMore = false
                    ) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingMore = false, error = e.message) }
            }
        }
    }
}
