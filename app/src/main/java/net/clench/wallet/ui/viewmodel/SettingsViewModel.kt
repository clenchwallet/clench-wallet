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
import net.clench.wallet.data.network.ElectrumConnectionFactory
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
    private val pinManager: PinManager,
    private val electrumConnectionFactory: ElectrumConnectionFactory
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
        val isPinSet: Boolean = false,
        /** Base64-encoded DER certificate for TLS cert pinning */
        val pinnedCert: String? = null,
        /** Per-server Tor toggle (routes this server via SOCKS5) */
        val useServerTor: Boolean = false,
        /** Shows QR scanner for cert import */
        val showCertScanner: Boolean = false,
        /** Active connection mode label for display */
        val connectionModeLabel: String = ""
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
                isPinSet = pinManager.isPinSet(),
                pinnedCert = validatedConfig.pinnedCert,
                useServerTor = validatedConfig.useTor || validatedConfig.serverUrl.endsWith(".onion"),
                connectionModeLabel = computeConnectionModeLabel(validatedConfig)
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

    /**
     * Verify the current PIN. Returns true if the PIN is correct, false otherwise.
     * Used by SecurityScreen to require current-PIN verification before changing auth mode.
     */
    fun verifyPin(pin: CharArray): Boolean {
        val result = pinManager.verifyPin(pin)
        return result == null // null means success in PinManager.verifyPin()
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
                isCustom = true,
                pinnedCert = state.pinnedCert,
                useTor = state.useServerTor
            )
        } else {
            // Keep whatever public server was selected (don't reset to defaults)
            val current = settingsManager.loadElectrumConfig()
            ElectrumConfig(
                serverUrl = current.serverUrl,
                port = current.port,
                useSsl = current.useSsl,
                isCustom = false,
                useTor = state.useServerTor || current.serverUrl.endsWith(".onion")
            )
        }
        settingsManager.saveElectrumConfig(config)
        _uiState.update { it.copy(
            customServerUrl = cleanUrl,  // normalize displayed URL too
            savedSuccess = true,
            saveError = null,
            connectionModeLabel = computeConnectionModeLabel(config)
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
        val testConfig = ElectrumConfig(
            serverUrl = cleanUrl,
            port = port,
            useSsl = state.useSSL,
            isCustom = true,
            pinnedCert = state.pinnedCert,
            useTor = state.useServerTor
        )
        val resolved = electrumConnectionFactory.resolveConnection(testConfig)

        _uiState.update { it.copy(testingConnection = true, connectionTestResult = null) }
        viewModelScope.launch {
            val result = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val conn = electrumConnectionFactory.createConnection(testConfig)
                    conn.close()
                }
                "✓ Connected to $cleanUrl:$port (${resolved.mode.name})"
            } catch (e: net.clench.wallet.data.network.ElectrumConnectionException.TorProxyUnavailable) {
                "✗ Tor proxy not reachable — is Orbot running?\n${e.message}"
            } catch (e: net.clench.wallet.data.network.ElectrumConnectionException.TlsCertPinningFailed) {
                "✗ Certificate pinning failed — the server's cert doesn't match.\n${e.message}"
            } catch (e: net.clench.wallet.data.network.ElectrumConnectionException.TlsHandshakeFailed) {
                "✗ TLS handshake failed — check that the server supports TLS on this port.\n${e.message}"
            } catch (e: net.clench.wallet.data.network.ElectrumConnectionException.ConnectionFailed) {
                "✗ Connection failed — check host/port.\n${e.message}"
            } catch (e: Exception) {
                val msg = e.message ?: "Connection error"
                when {
                    msg.contains("SSL", ignoreCase = true) ||
                    msg.contains("TLS", ignoreCase = true) ||
                    msg.contains("certificate", ignoreCase = true) ||
                    msg.contains("handshake", ignoreCase = true) ->
                        "✗ SSL/TLS error — try pinning the server's certificate, or disable SSL and use port 50001."
                    msg.contains("Connection refused", ignoreCase = true) ->
                        "✗ Connection refused — check host/port and that your server is running."
                    msg.contains("SOCKS", ignoreCase = true) || msg.contains("Tor", ignoreCase = true) ->
                        "✗ Tor proxy error — is Orbot running?\n${msg.take(100)}"
                    else -> "✗ Failed: ${msg.take(150)}"
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
        val state = _uiState.value
        val useTor = state.useServerTor || server.host.endsWith(".onion")
        val config = ElectrumConfig(
            serverUrl = server.host,
            port = server.port,
            useSsl = server.useSsl,
            isCustom = false,
            useTor = useTor
        )
        settingsManager.saveElectrumConfig(config)
        _uiState.update { it.copy(
            publicServer = "${server.host}:${server.port}",
            useServerTor = useTor,
            savedSuccess = true,
            connectionModeLabel = computeConnectionModeLabel(config)
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
                "PASSPORT", "FOUNDATION_PASSPORT" -> "Foundation Passport"
                "COLDCARD_Q" -> "Coldcard Q"
                "COLDCARD_MK4" -> "Coldcard Mk4"
                "COLDCARD_MK5" -> "Coldcard Mk5"
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

    // ─── Cert pinning methods ───

    fun setPinnedCert(certBase64: String?) {
        _uiState.update { it.copy(pinnedCert = certBase64) }
    }

    fun clearPinnedCert() {
        _uiState.update { it.copy(pinnedCert = null) }
    }

    fun setShowCertScanner(show: Boolean) {
        _uiState.update { it.copy(showCertScanner = show) }
    }

    /**
     * Parse an `electrums://host:port?cert=BASE64` QR code and apply it.
     * Returns true if successfully parsed.
     */
    fun parseCertQr(qrText: String): Boolean {
        // Format: electrums://host:port?cert=BASE64
        val regex = Regex("""electrums://([^:]+):(\d+)\?cert=(.+)""")
        val match = regex.matchEntire(qrText.trim())
        if (match != null) {
            val host = match.groupValues[1]
            val port = match.groupValues[2]
            val cert = match.groupValues[3]
            _uiState.update { it.copy(
                customServerUrl = host,
                customServerPort = port,
                useSSL = true,
                pinnedCert = cert,
                useCustomServer = true,
                showCertScanner = false
            ) }
            return true
        }
        // Also accept raw base64 cert (manual paste or simple QR)
        return try {
            android.util.Base64.decode(qrText.trim(), android.util.Base64.NO_WRAP)
            _uiState.update { it.copy(
                pinnedCert = qrText.trim(),
                showCertScanner = false
            ) }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ─── Per-server Tor toggle ───

    fun setUseServerTor(enabled: Boolean) {
        _uiState.update { it.copy(useServerTor = enabled) }

        // Public-server mode has no Save button, so persist route changes immediately.
        if (!_uiState.value.useCustomServer) {
            saveServerSettings()
        }
    }

    // ─── Connection mode label ───

    private fun computeConnectionModeLabel(config: ElectrumConfig): String {
        val host = config.serverUrl.removePrefix("ssl://").removePrefix("tcp://").trim()
        val isOnion = host.endsWith(".onion")
        return when {
            isOnion -> "🧅 Tor (.onion)"
            config.useTor || settingsManager.isTorEnabled() -> "🧅 Tor (SOCKS5)"
            config.pinnedCert != null -> "🔒 TLS (pinned cert)"
            config.useSsl -> "🔒 TLS"
            else -> "📡 Plain TCP"
        }
    }
}
