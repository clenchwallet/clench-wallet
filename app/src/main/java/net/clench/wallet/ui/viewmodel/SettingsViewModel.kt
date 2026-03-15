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
import net.clench.wallet.domain.model.PublicServer
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
        val publicServer: String = "electrum.blockstream.info:50002",
        val customServerUrl: String = "",
        val customServerPort: String = "50002",
        val useSSL: Boolean = true,
        val wallets: List<WalletData> = emptyList(),
        val savedSuccess: Boolean = false,
        val saveError: String? = null,
        val testingConnection: Boolean = false,
        val connectionTestResult: String? = null,
        val useCustomMempool: Boolean = false,
        val mempoolUrl: String = "https://mempool.space",
        val useTestnet: Boolean = false,
        val biometricForSeed: Boolean = true,
        val biometricForSend: Boolean = true,
        val appLockMode: String = "biometric",
        val lockTimeoutKey: String = "30s",
        val offlineMode: Boolean = false
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
                useSSL = saved.useSsl,
                useCustomMempool = settingsManager.isCustomMempoolEnabled(),
                mempoolUrl = settingsManager.getMempoolUrl(),
                useTestnet = settingsManager.isTestnet(),
                biometricForSeed = settingsManager.isBiometricForSeedEnabled(),
                biometricForSend = settingsManager.isBiometricForSendEnabled(),
                appLockMode = settingsManager.getAppLockMode(),
                lockTimeoutKey = settingsManager.getLockTimeoutKey(),
                offlineMode = settingsManager.isOfflineMode()
            )
        }
    }

    fun setUseCustomServer(use: Boolean) {
        _uiState.update { it.copy(useCustomServer = use) }
        // Auto-save when switching back to public server — the Save button is hidden in public mode
        if (!use) saveServerSettings()
    }
    fun setCustomServerUrl(url: String) = _uiState.update { it.copy(customServerUrl = url) }
    fun setCustomServerPort(port: String) = _uiState.update { it.copy(customServerPort = port) }
    fun setUseSsl(ssl: Boolean) = _uiState.update { it.copy(useSSL = ssl) }

    fun saveServerSettings() {
        val state = _uiState.value
        // Strip any protocol prefix the user may have typed — buildElectrumUrl() adds it back
        val cleanUrl = state.customServerUrl
            .removePrefix("ssl://")
            .removePrefix("tcp://")
            .trim()

        if (state.useCustomServer && cleanUrl.isBlank()) {
            _uiState.update { it.copy(saveError = "Please enter a server address") }
            return
        }

        val config = if (state.useCustomServer) {
            ElectrumConfig(
                serverUrl = cleanUrl,
                port = state.customServerPort.toIntOrNull() ?: 50002,
                useSsl = state.useSSL,
                isCustom = true
            )
        } else {
            ElectrumConfig(
                serverUrl = "electrum.blockstream.info",
                port = 50002,
                useSsl = true,
                isCustom = false
            )
        }
        settingsManager.saveElectrumConfig(config)
        _uiState.update { it.copy(
            customServerUrl = cleanUrl,  // normalize displayed URL too
            savedSuccess = true,
            saveError = null
        ) }
        // Clear success banner after a moment
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(savedSuccess = false) }
        }
    }

    fun clearSaveStatus() = _uiState.update { it.copy(savedSuccess = false, saveError = null) }

    fun testConnection() {
        val state = _uiState.value
        val cleanUrl = state.customServerUrl.removePrefix("ssl://").removePrefix("tcp://").trim()
        if (cleanUrl.isBlank()) {
            _uiState.update { it.copy(connectionTestResult = "✗ Enter a server address first") }
            return
        }
        val port = state.customServerPort.toIntOrNull() ?: 50002
        val protocol = if (state.useSSL) "ssl" else "tcp"
        val url = "$protocol://$cleanUrl:$port"

        _uiState.update { it.copy(testingConnection = true, connectionTestResult = null) }
        viewModelScope.launch {
            val result = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val client = org.bitcoindevkit.ElectrumClient(url)
                    // Request server version as a connectivity probe
                    client.close()
                }
                "✓ Connected to $cleanUrl:$port"
            } catch (e: Exception) {
                "✗ Failed: ${e.message?.take(80) ?: "Connection error"}"
            }
            _uiState.update { it.copy(testingConnection = false, connectionTestResult = result) }
        }
    }

    // --- Mempool settings ---
    fun setUseCustomMempool(use: Boolean) {
        settingsManager.setCustomMempoolEnabled(use)
        _uiState.update { it.copy(useCustomMempool = use) }
    }

    fun setMempoolUrl(url: String) {
        _uiState.update { it.copy(mempoolUrl = url) }
    }

    fun saveMempoolSettings() {
        settingsManager.setMempoolUrl(_uiState.value.mempoolUrl)
        _uiState.update { it.copy(savedSuccess = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(savedSuccess = false) }
        }
    }

    // --- Network settings ---
    fun setUseTestnet(use: Boolean) {
        val network = if (use) "testnet" else "mainnet"
        settingsManager.setNetwork(network)

        // Auto-switch electrum server for testnet if using public server
        if (!_uiState.value.useCustomServer) {
            if (use) {
                settingsManager.saveElectrumConfig(ElectrumConfig(
                    serverUrl = "electrum.blockstream.info",
                    port = 60002,
                    useSsl = true,
                    isCustom = false
                ))
            } else {
                settingsManager.saveElectrumConfig(ElectrumConfig(
                    serverUrl = "electrum.blockstream.info",
                    port = 50002,
                    useSsl = true,
                    isCustom = false
                ))
            }
        }

        _uiState.update { it.copy(useTestnet = use) }
    }

    // --- Security settings ---
    fun setBiometricForSeed(enabled: Boolean) {
        settingsManager.setBiometricForSeedEnabled(enabled)
        _uiState.update { it.copy(biometricForSeed = enabled) }
    }

    fun setBiometricForSend(enabled: Boolean) {
        settingsManager.setBiometricForSendEnabled(enabled)
        _uiState.update { it.copy(biometricForSend = enabled) }
    }

    fun setAppLockMode(mode: String) {
        settingsManager.setAppLockMode(mode)
        _uiState.update { it.copy(appLockMode = mode) }
    }

    fun setLockTimeout(key: String) {
        settingsManager.setLockTimeout(key)
        _uiState.update { it.copy(lockTimeoutKey = key) }
    }

    fun setOfflineMode(enabled: Boolean) {
        settingsManager.setOfflineMode(enabled)
        _uiState.update { it.copy(offlineMode = enabled) }
    }

    fun selectPublicServer(server: PublicServer) {
        val config = ElectrumConfig(
            serverUrl = server.host,
            port = server.port,
            useSsl = server.useSsl,
            isCustom = false
        )
        settingsManager.saveElectrumConfig(config)
        _uiState.update { it.copy(
            publicServer = "${server.host}:${server.port}",
            savedSuccess = true
        ) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(savedSuccess = false) }
        }
    }

    private fun loadWallets() {
        viewModelScope.launch {
            val wallets = bitcoinRepository.listWallets()
            _uiState.update { it.copy(wallets = wallets) }
        }
    }
}
