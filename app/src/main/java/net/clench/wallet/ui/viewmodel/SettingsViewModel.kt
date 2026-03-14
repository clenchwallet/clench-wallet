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
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
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
        val saved = settingsManager.loadElectrumConfig()
        _uiState.update {
            it.copy(
                useCustomServer = saved.isCustom,
                customServerUrl = if (saved.isCustom) saved.serverUrl.removePrefix("ssl://").removePrefix("tcp://") else "",
                customServerPort = saved.port.toString(),
                useSSL = saved.useSsl
            )
        }
    }

    fun setUseCustomServer(use: Boolean) = _uiState.update { it.copy(useCustomServer = use) }
    fun setCustomServerUrl(url: String) = _uiState.update { it.copy(customServerUrl = url) }
    fun setCustomServerPort(port: String) = _uiState.update { it.copy(customServerPort = port) }
    fun setUseSsl(ssl: Boolean) = _uiState.update { it.copy(useSSL = ssl) }

    fun saveServerSettings() {
        val state = _uiState.value
        val config = if (state.useCustomServer) {
            ElectrumConfig(
                serverUrl = state.customServerUrl,
                port = state.customServerPort.toIntOrNull() ?: 50002,
                useSsl = state.useSSL,
                isCustom = true
            )
        } else {
            ElectrumConfig(
                serverUrl = "ssl://electrum.blockstream.info",
                port = 700,
                useSsl = true,
                isCustom = false
            )
        }
        settingsManager.saveElectrumConfig(config)
    }

    private fun loadWallets() {
        viewModelScope.launch {
            val wallets = bitcoinRepository.listWallets()
            _uiState.update { it.copy(wallets = wallets) }
        }
    }
}
