package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.PinManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.PublicElectrumServers
import net.clench.wallet.domain.model.PublicServer
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val pinManager: PinManager
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
        val appLockMode: String = "none",
        val lockTimeoutKey: String = "30s",
        val offlineMode: Boolean = false,
        val torEnabled: Boolean = false,
        val torProxyHost: String = "127.0.0.1",
        val torProxyPort: String = "9050",
        val preferredHardwareWalletLabel: String = "None",
        val isPinSet: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadWallets()
        loadHardwareWalletLabel()
        val saved = settingsManager.loadElectrumConfig()
        // Validate saved public server is still in the known list; reset if not
        val isTestnet = settingsManager.isTestnet()
        val validatedConfig = if (!saved.isCustom) {
            val knownServers = PublicElectrumServers.forNetwork(isTestnet)
            val isKnown = knownServers.any { it.host == saved.serverUrl && it.port == saved.port }
            if (!isKnown && knownServers.isNotEmpty()) {
                val fallback = knownServers.first()
                val fallbackConfig = ElectrumConfig(
                    serverUrl = fallback.host,
                    port = fallback.port,
                    useSsl = fallback.useSsl,
                    isCustom = false
                )
                settingsManager.saveElectrumConfig(fallbackConfig)
                fallbackConfig
            } else saved
        } else saved
        _uiState.update {
            it.copy(
                useCustomServer = validatedConfig.isCustom,
                publicServer = "${validatedConfig.serverUrl}:${validatedConfig.port}",
                customServerUrl = if (validatedConfig.isCustom) validatedConfig.serverUrl.removePrefix("ssl://").removePrefix("tcp://") else "",
                customServerPort = validatedConfig.port.toString(),
                useSSL = validatedConfig.useSsl,
                useCustomMempool = settingsManager.isCustomMempoolEnabled(),
                mempoolUrl = settingsManager.getMempoolUrl(),
                useTestnet = settingsManager.isTestnet(),
                biometricForSeed = settingsManager.isBiometricForSeedEnabled(),
                biometricForSend = settingsManager.isBiometricForSendEnabled(),
                appLockMode = settingsManager.getAppLockMode(),
                lockTimeoutKey = settingsManager.getLockTimeoutKey(),
                offlineMode = settingsManager.isOfflineMode(),
                torEnabled = settingsManager.isTorEnabled(),
                torProxyHost = settingsManager.getTorProxyHost(),
                torProxyPort = settingsManager.getTorProxyPort().toString(),
                isPinSet = pinManager.isPinSet()
            )
        }
    }

    fun setPin(pin: CharArray): String? {
        val err = pinManager.setPin(pin)
        if (err == null) {
            _uiState.update { it.copy(isPinSet = true) }
        }
        return err
    }

    fun clearPin() {
        pinManager.clearPin()
        _uiState.update { it.copy(isPinSet = false) }
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
            // Keep whatever public server was selected (don't reset to defaults)
            val current = settingsManager.loadElectrumConfig()
            ElectrumConfig(
                serverUrl = current.serverUrl,
                port = current.port,
                useSsl = current.useSsl,
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
                val msg = e.message ?: "Connection error"
                when {
                    msg.contains("SSL", ignoreCase = true) ||
                    msg.contains("TLS", ignoreCase = true) ||
                    msg.contains("certificate", ignoreCase = true) ||
                    msg.contains("handshake", ignoreCase = true) ->
                        "✗ SSL/TLS error — self-signed certificates are not supported by BDK.\nDisable SSL and use port 50001 (plain TCP)."
                    msg.contains("Connection refused", ignoreCase = true) ->
                        "✗ Connection refused — check host/port and that your server is running.\nFor self-signed certs: disable SSL, use port 50001."
                    else -> "✗ Failed: ${msg.take(100)}"
                }
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

        // Per-network server config: loadElectrumConfig() now auto-returns the right one.
        // If no per-network config exists for the target network, seed it with defaults.
        val targetConfig = settingsManager.loadElectrumConfig()
        if (!targetConfig.isCustom) {
            // Ensure correct default port for the target network
            val defaultPort = if (use) 60002 else 50002
            if (targetConfig.port != defaultPort) {
                settingsManager.saveElectrumConfig(ElectrumConfig(
                    serverUrl = "electrum.blockstream.info",
                    port = defaultPort,
                    useSsl = true,
                    isCustom = false
                ))
            }
        }

        // Reload the persisted config so the UI reflects the correct server for this network
        val reloaded = settingsManager.loadElectrumConfig()
        _uiState.update { it.copy(
            useTestnet = use,
            publicServer = "${reloaded.serverUrl}:${reloaded.port}",
            useCustomServer = reloaded.isCustom,
            customServerUrl = if (reloaded.isCustom) reloaded.serverUrl.removePrefix("ssl://").removePrefix("tcp://") else "",
            customServerPort = reloaded.port.toString(),
            useSSL = reloaded.useSsl
        ) }
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

    // --- Tor proxy settings ---
    fun setTorEnabled(enabled: Boolean) {
        settingsManager.setTorEnabled(enabled)
        _uiState.update { it.copy(torEnabled = enabled) }
    }

    fun setTorProxyHost(host: String) {
        _uiState.update { it.copy(torProxyHost = host) }
    }

    fun setTorProxyPort(port: String) {
        _uiState.update { it.copy(torProxyPort = port) }
    }

    fun saveTorSettings() {
        val state = _uiState.value
        settingsManager.setTorProxyHost(state.torProxyHost)
        settingsManager.setTorProxyPort(state.torProxyPort.toIntOrNull() ?: 9050)
        settingsManager.setTorEnabled(state.torEnabled)
        _uiState.update { it.copy(savedSuccess = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(savedSuccess = false) }
        }
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

    private fun loadHardwareWalletLabel() {
        viewModelScope.launch {
            val wallets = bitcoinRepository.listWallets()
            // Use the first wallet's hardware wallet preference
            val hwWallet = wallets.firstOrNull()?.preferredHardwareWallet
            val label = when (hwWallet) {
                "SEEDSIGNER" -> "SeedSigner"
                "KEYSTONE" -> "Keystone"
                "PASSPORT" -> "Foundation Passport"
                "COLDCARD_Q" -> "Coldcard Q"
                "COLDCARD_MK4" -> "Coldcard Mk4"
                "JADE" -> "Jade"
                else -> "None"
            }
            _uiState.update { it.copy(preferredHardwareWalletLabel = label) }
        }
    }

    private fun loadWallets() {
        viewModelScope.launch {
            val wallets = bitcoinRepository.listWallets()
            _uiState.update { it.copy(wallets = wallets) }
        }
    }
}
