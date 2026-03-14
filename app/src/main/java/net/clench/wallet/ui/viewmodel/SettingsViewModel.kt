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
class SettingsViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val useCustomServer: Boolean = false,
        val publicServer: String = "electrum.blockstream.info:700",
        val customServerUrl: String = "",
        val customServerPort: String = "50002",
        val useSSL: Boolean = true,
        val wallets: List<WalletData> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadWallets()
    }

    fun setUseCustomServer(use: Boolean) = _uiState.update { it.copy(useCustomServer = use) }
    fun setCustomServerUrl(url: String) = _uiState.update { it.copy(customServerUrl = url) }
    fun setCustomServerPort(port: String) = _uiState.update { it.copy(customServerPort = port) }
    fun setUseSsl(ssl: Boolean) = _uiState.update { it.copy(useSSL = ssl) }

    fun saveServerSettings() {
        // TODO: persist to SharedPreferences
    }

    private fun loadWallets() {
        viewModelScope.launch {
            val wallets = bitcoinRepository.listWallets()
            _uiState.update { it.copy(wallets = wallets) }
        }
    }
}
