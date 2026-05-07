package net.clench.wallet.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.clench.wallet.data.backup.ClenchStateBackupManager
import net.clench.wallet.data.local.PinManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.ElectrumConnectionFactory
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.PublicElectrumServers
import net.clench.wallet.domain.model.PublicServer
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.util.connectionRuntimeMessage
import net.clench.wallet.ui.util.shouldRethrowForUiBoundary
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val pinManager: PinManager,
    private val electrumConnectionFactory: ElectrumConnectionFactory,
    private val backupManager: ClenchStateBackupManager,
    @ApplicationContext private val context: Context
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
        val btcPriceEnabled: Boolean = false,
        val externalFeeLookupEnabled: Boolean = false,
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
        val connectionModeLabel: String = "",
        val testingServerHealth: Boolean = false,
        val serverHealthResult: String? = null,
        val isBackupBusy: Boolean = false,
        val backupStatus: String? = null
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
                btcPriceEnabled = settingsManager.isBtcPriceEnabled(),
                externalFeeLookupEnabled = settingsManager.isExternalFeeLookupEnabled(),
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
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                "✗ ${t.connectionRuntimeMessage()}"
            }
            _uiState.update { it.copy(testingConnection = false, connectionTestResult = result) }
        }
    }

    fun runServerHealthCheck() {
        val state = _uiState.value
        if (state.offlineMode) {
            _uiState.update {
                it.copy(serverHealthResult = "Offline mode is enabled. Active server diagnostics would make a network connection.")
            }
            return
        }

        val config = runCatching { electrumConfigFromState(state) }.getOrElse { e ->
            _uiState.update { it.copy(serverHealthResult = "Could not build Electrum config: ${e.message}") }
            return
        }
        val resolved = electrumConnectionFactory.resolveConnection(config)
        val startedAt = System.currentTimeMillis()

        _uiState.update { it.copy(testingServerHealth = true, serverHealthResult = null) }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    electrumConnectionFactory.createRawSocket(config).use { socket ->
                        socket.soTimeout = 12_000
                        val request = """
                            {"id":1,"method":"server.version","params":["Clench Wallet","1.4"]}
                            {"id":2,"method":"blockchain.headers.subscribe","params":[]}
                        """.trimIndent() + "\n"
                        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()

                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        var serverVersion: String? = null
                        var protocolVersion: String? = null
                        var tipHeight: Int? = null

                        var reads = 0
                        while (reads < 4 && (serverVersion == null || tipHeight == null)) {
                            val line = reader.readLine() ?: break
                            reads++
                            val response = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            when (response.optInt("id", -1)) {
                                1 -> {
                                    val versionArray = response.optJSONArray("result")
                                    serverVersion = versionArray?.optString(0)?.takeIf { it.isNotBlank() }
                                        ?: response.optString("result").takeIf { it.isNotBlank() }
                                    protocolVersion = versionArray?.optString(1)?.takeIf { it.isNotBlank() }
                                }
                                2 -> {
                                    val header = response.optJSONObject("result")
                                    val height = header?.optInt("height", -1) ?: -1
                                    if (height > 0) tipHeight = height
                                }
                            }
                        }

                        val elapsed = System.currentTimeMillis() - startedAt
                        buildString {
                            appendLine("✓ Server healthy")
                            appendLine("Target: ${config.serverUrl}:${config.port}")
                            appendLine("Mode: ${resolved.mode.name}")
                            appendLine("Route: ${routeDescription(config)}")
                            appendLine("TLS pin: ${if (config.pinnedCert != null) "enabled" else "none"}")
                            if (serverVersion != null) appendLine("Server: $serverVersion")
                            if (protocolVersion != null) appendLine("Protocol: $protocolVersion")
                            if (tipHeight != null) appendLine("Tip height: $tipHeight")
                            append("Checked in ${elapsed}ms")
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                diagnosticFailureMessage(t, config, resolved.mode.name)
            }
            _uiState.update { it.copy(testingServerHealth = false, serverHealthResult = result) }
        }
    }

    fun clearServerHealthResult() {
        _uiState.update { it.copy(serverHealthResult = null) }
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

    fun setBtcPriceEnabled(enabled: Boolean) {
        settingsManager.setBtcPriceEnabled(enabled)
        _uiState.update { it.copy(btcPriceEnabled = enabled) }
    }

    fun setExternalFeeLookupEnabled(enabled: Boolean) {
        settingsManager.setExternalFeeLookupEnabled(enabled)
        _uiState.update { it.copy(externalFeeLookupEnabled = enabled) }
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

    // --- State backup ---

    fun exportStateBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBackupBusy = true, backupStatus = null) }
            try {
                val json = withContext(Dispatchers.IO) { backupManager.exportStateBackupJson() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Could not open backup file")
                }
                _uiState.update { it.copy(isBackupBusy = false, backupStatus = "State backup exported. Seed phrases were not included.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBackupBusy = false, backupStatus = "Backup export failed: ${e.message}") }
            }
        }
    }

    fun importStateBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBackupBusy = true, backupStatus = null) }
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Could not read backup file")
                }
                val result = withContext(Dispatchers.IO) { backupManager.importStateBackupJson(json) }
                loadWallets()
                loadHardwareWalletLabel()
                _uiState.update { it.copy(isBackupBusy = false, backupStatus = result.toUserMessage()) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBackupBusy = false, backupStatus = "Backup import failed: ${e.message}") }
            }
        }
    }

    fun clearBackupStatus() {
        _uiState.update { it.copy(backupStatus = null) }
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
            if (!isValidX509Certificate(cert)) {
                _uiState.update { it.copy(saveError = "Certificate QR did not contain a valid base64 DER certificate") }
                return false
            }
            _uiState.update { it.copy(
                customServerUrl = host,
                customServerPort = port,
                useSSL = true,
                pinnedCert = cert,
                useCustomServer = true,
                showCertScanner = false,
                saveError = null
            ) }
            return true
        }
        // Also accept raw base64 cert (manual paste or simple QR)
        return try {
            require(isValidX509Certificate(qrText.trim())) { "invalid X.509 certificate" }
            _uiState.update { it.copy(
                pinnedCert = qrText.trim(),
                showCertScanner = false,
                saveError = null
            ) }
            true
        } catch (e: Exception) {
            _uiState.update {
                it.copy(saveError = "Certificate must be base64 DER or electrums://host:port?cert=BASE64 (${e.message ?: "invalid input"})")
            }
            false
        }
    }

    private fun isValidX509Certificate(certBase64: String): Boolean {
        val decoded = runCatching {
            android.util.Base64.decode(certBase64.trim(), android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return false
        if (decoded.isEmpty()) return false

        return runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(decoded))
        }.isSuccess
    }

    // ─── Per-server Tor toggle ───

    fun setUseServerTor(enabled: Boolean) {
        _uiState.update { it.copy(useServerTor = enabled) }

        // Public-server mode has no Save button, so persist route changes immediately.
        if (!_uiState.value.useCustomServer) {
            saveServerSettings()
        }
    }

    private fun electrumConfigFromState(state: UiState): ElectrumConfig {
        return if (state.useCustomServer) {
            val cleanUrl = state.customServerUrl
                .removePrefix("ssl://")
                .removePrefix("tcp://")
                .trim()
            require(cleanUrl.isNotBlank()) { "Enter a server address first" }
            val useTor = state.useServerTor || cleanUrl.endsWith(".onion")
            ElectrumConfig(
                serverUrl = cleanUrl,
                port = state.customServerPort.toIntOrNull() ?: 50002,
                useSsl = state.useSSL,
                isCustom = true,
                pinnedCert = if (state.useSSL) state.pinnedCert else null,
                useTor = useTor
            )
        } else {
            val selected = publicServerFromState(state)
            val useTor = state.useServerTor || selected.host.endsWith(".onion")
            ElectrumConfig(
                serverUrl = selected.host,
                port = selected.port,
                useSsl = selected.useSsl,
                isCustom = false,
                useTor = useTor
            )
        }
    }

    private fun publicServerFromState(state: UiState): PublicServer {
        val known = PublicElectrumServers.forNetwork(state.useTestnet)
        known.firstOrNull { "${it.host}:${it.port}" == state.publicServer }?.let { return it }

        val host = state.publicServer.substringBeforeLast(":").trim()
        val port = state.publicServer.substringAfterLast(":", "").toIntOrNull()
            ?: if (state.useTestnet) 60002 else 50002
        val useSsl = port == 50002 || port == 60002
        return PublicServer(
            name = "Selected server",
            host = host.ifBlank { "electrum.blockstream.info" },
            port = port,
            useSsl = useSsl,
            description = "Selected Electrum server"
        )
    }

    private fun routeDescription(config: ElectrumConfig): String {
        val isOnion = config.serverUrl.endsWith(".onion")
        return when {
            isOnion -> "Tor .onion"
            config.useTor || settingsManager.isTorEnabled() ->
                "Tor SOCKS5 ${settingsManager.getTorProxyHost()}:${settingsManager.getTorProxyPort()}"
            else -> "Direct clearnet"
        }
    }

    private fun diagnosticFailureMessage(e: Throwable, config: ElectrumConfig, mode: String): String {
        val prefix = "✗ Server health check failed\nTarget: ${config.serverUrl}:${config.port}\nMode: $mode"
        return when (e) {
            is net.clench.wallet.data.network.ElectrumConnectionException.TorProxyUnavailable ->
                "$prefix\nTor SOCKS5 proxy is not reachable. Is Orbot running?\n${e.message}"
            is net.clench.wallet.data.network.ElectrumConnectionException.TlsCertPinningFailed ->
                "$prefix\nCertificate pinning failed. The server certificate does not match the pinned certificate.\n${e.message}"
            is net.clench.wallet.data.network.ElectrumConnectionException.TlsHandshakeFailed ->
                "$prefix\nTLS handshake failed. Check SSL/TLS and port settings.\n${e.message}"
            is net.clench.wallet.data.network.ElectrumConnectionException.ConnectionFailed ->
                "$prefix\nConnection failed. Check host, port, and network reachability.\n${e.message}"
            is LinkageError ->
                "$prefix\n${e.connectionRuntimeMessage()}"
            else -> "$prefix\n${e.message ?: e.javaClass.simpleName}"
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
