package net.clench.wallet.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.clench.wallet.domain.model.ElectrumConfig
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("clench_settings", Context.MODE_PRIVATE)

    fun saveElectrumConfig(config: ElectrumConfig) {
        // Always store hostname only — protocol prefix added at connection time
        val cleanUrl = config.serverUrl
            .removePrefix("ssl://")
            .removePrefix("tcp://")
            .trim()
        // Store per-network so mainnet and testnet configs don't interfere
        val suffix = if (isTestnet()) "_testnet" else "_mainnet"
        prefs.edit {
            putBoolean("use_custom_server$suffix", config.isCustom)
            putString("server_url$suffix", cleanUrl)
            putInt("server_port$suffix", config.port)
            putBoolean("use_ssl$suffix", config.useSsl)
            // New fields for multi-mode connections
            if (config.pinnedCert != null) {
                putString("pinned_cert$suffix", config.pinnedCert)
            } else {
                remove("pinned_cert$suffix")
            }
            putBoolean("use_tor$suffix", config.useTor)
        }
        // Also write to legacy keys for backward compat
        prefs.edit {
            putBoolean("use_custom_server", config.isCustom)
            putString("server_url", cleanUrl)
            putInt("server_port", config.port)
            putBoolean("use_ssl", config.useSsl)
        }
    }

    fun loadElectrumConfig(): ElectrumConfig {
        val suffix = if (isTestnet()) "_testnet" else "_mainnet"
        val defaultPort = if (isTestnet()) 60002 else 50002

        // Try per-network keys first, fall back to legacy keys, then defaults
        val hasPerNetworkConfig = prefs.contains("server_url$suffix")

        return if (hasPerNetworkConfig) {
            ElectrumConfig(
                serverUrl = prefs.getString("server_url$suffix", "electrum.blockstream.info") ?: "electrum.blockstream.info",
                port = prefs.getInt("server_port$suffix", defaultPort),
                useSsl = prefs.getBoolean("use_ssl$suffix", true),
                isCustom = prefs.getBoolean("use_custom_server$suffix", false),
                pinnedCert = prefs.getString("pinned_cert$suffix", null),
                useTor = prefs.getBoolean("use_tor$suffix", false)
            )
        } else {
            // No per-network config exists yet.
            // For mainnet: use legacy keys (user's saved server)
            // For testnet: use clean defaults (don't inherit mainnet server)
            if (isTestnet()) {
                ElectrumConfig(
                    serverUrl = "electrum.blockstream.info",
                    port = 60002,
                    useSsl = true,
                    isCustom = false
                )
            } else {
                ElectrumConfig(
                    serverUrl = prefs.getString("server_url", "electrum.blockstream.info") ?: "electrum.blockstream.info",
                    port = prefs.getInt("server_port", 50002),
                    useSsl = prefs.getBoolean("use_ssl", true),
                    isCustom = prefs.getBoolean("use_custom_server", false)
                )
            }
        }
    }

    fun isConfigured(): Boolean = prefs.contains("server_url")

    // --- Mempool explorer settings ---

    fun getMempoolUrl(): String {
        val useCustom = prefs.getBoolean("use_custom_mempool", false)
        return if (useCustom) {
            prefs.getString("mempool_url", "https://mempool.space") ?: "https://mempool.space"
        } else {
            "https://mempool.space"
        }
    }

    fun setMempoolUrl(url: String) {
        prefs.edit { putString("mempool_url", url) }
    }

    fun isCustomMempoolEnabled(): Boolean = prefs.getBoolean("use_custom_mempool", false)

    fun setCustomMempoolEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("use_custom_mempool", enabled) }
    }

    // --- Network settings (mainnet/testnet) ---

    private val _networkFlow = MutableStateFlow(prefs.getString("network", "mainnet") ?: "mainnet")
    val networkFlow: StateFlow<String> = _networkFlow.asStateFlow()

    fun getNetwork(): String = prefs.getString("network", "mainnet") ?: "mainnet"

    fun setNetwork(network: String) {
        prefs.edit { putString("network", network) }
        _networkFlow.value = network
    }

    fun isTestnet(): Boolean = getNetwork() == "testnet"

    // --- Biometric / Security settings ---

    /** Called only after the repository confirms that initial setup has no wallets. */
    internal fun initializeAuthenticationGates(canAuthenticate: Boolean) {
        if (prefs.contains("biometric_seed") || prefs.contains("biometric_send")) return
        check(prefs.edit()
            .putBoolean("biometric_seed", canAuthenticate)
            .putBoolean("biometric_send", canAuthenticate)
            .commit()) { "Could not persist initial authentication settings" }
    }

    fun isBiometricForSeedEnabled(): Boolean = prefs.getBoolean("biometric_seed", true)
    internal fun setBiometricForSeedEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean("biometric_seed", enabled).commit()) { "Could not persist seed authentication setting" }
    }

    fun isBiometricForSendEnabled(): Boolean = prefs.getBoolean("biometric_send", true)
    internal fun setBiometricForSendEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean("biometric_send", enabled).commit()) { "Could not persist send authentication setting" }
    }

    fun getAppLockMode(): String = prefs.getString("app_lock_mode", "none") ?: "none"
    fun setAppLockMode(mode: String) {
        prefs.edit { putString("app_lock_mode", mode) }
    }

    // --- Lock timeout ---

    fun getLockTimeoutMs(): Long {
        return when (prefs.getString("lock_timeout", "30s")) {
            "30s" -> 30_000L
            "1min" -> 60_000L
            "5min" -> 300_000L
            "never" -> Long.MAX_VALUE
            else -> 30_000L
        }
    }

    fun getLockTimeoutKey(): String = prefs.getString("lock_timeout", "30s") ?: "30s"

    fun setLockTimeout(key: String) {
        prefs.edit { putString("lock_timeout", key) }
    }

    // --- Offline mode ---

    fun isOfflineMode(): Boolean = prefs.getBoolean("offline_mode", false)
    fun setOfflineMode(enabled: Boolean) {
        prefs.edit { putBoolean("offline_mode", enabled) }
    }

    // --- Tor proxy settings ---

    fun isTorEnabled(): Boolean = prefs.getBoolean("tor_enabled", false)
    fun setTorEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("tor_enabled", enabled) }
    }

    fun getTorProxyHost(): String = prefs.getString("tor_proxy_host", "127.0.0.1") ?: "127.0.0.1"
    fun setTorProxyHost(host: String) {
        prefs.edit { putString("tor_proxy_host", host) }
    }

    fun getTorProxyPort(): Int = prefs.getInt("tor_proxy_port", 9050)
    fun setTorProxyPort(port: Int) {
        prefs.edit { putInt("tor_proxy_port", port) }
    }

    // --- Onboarding ---

    fun isOnboarded(): Boolean = prefs.getBoolean("onboarded", false)
    fun setOnboarded() { prefs.edit { putBoolean("onboarded", true) } }

    // --- Balance display unit ---

    fun getBalanceUnit(): String = prefs.getString("balance_unit", "SATS") ?: "SATS"
    fun setBalanceUnit(unit: String) {
        prefs.edit { putString("balance_unit", unit) }
    }

    // --- BTC Price display (H-5) ---

    fun isBtcPriceEnabled(): Boolean = prefs.getBoolean("btc_price_enabled", false)
    fun setBtcPriceEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("btc_price_enabled", enabled) }
    }

    // --- External fee lookup fallback ---

    fun isExternalFeeLookupEnabled(): Boolean = prefs.getBoolean("external_fee_lookup_enabled", false)
    fun setExternalFeeLookupEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("external_fee_lookup_enabled", enabled) }
    }

    // --- Advanced multisig signer options ---

    fun isPhoneSignerOptionsEnabled(): Boolean = prefs.getBoolean("phone_signer_options_enabled", false)
    fun setPhoneSignerOptionsEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("phone_signer_options_enabled", enabled) }
    }

    // --- Last viewed wallet ---

    fun getLastViewedWalletId(): String? = prefs.getString("last_viewed_wallet_id", null)
    fun setLastViewedWalletId(walletId: String) {
        prefs.edit { putString("last_viewed_wallet_id", walletId) }
    }

    // --- Diagnostics ---

    fun getLastSyncError(): String? = prefs.getString("last_sync_error", null)
    fun setLastSyncError(message: String?) {
        prefs.edit {
            if (message.isNullOrBlank()) {
                remove("last_sync_error")
            } else {
                putString("last_sync_error", message.take(500))
            }
        }
    }

    // --- State backup settings ---

    fun exportBackupSettings(): JSONObject {
        return JSONObject().apply {
            put("network", getNetwork())
            put("useCustomMempool", isCustomMempoolEnabled())
            put("mempoolUrl", getMempoolUrl())
            put("btcPriceEnabled", isBtcPriceEnabled())
            put("externalFeeLookupEnabled", isExternalFeeLookupEnabled())
            put("phoneSignerOptionsEnabled", isPhoneSignerOptionsEnabled())
            put("biometricForSeed", isBiometricForSeedEnabled())
            put("biometricForSend", isBiometricForSendEnabled())
            put("lockTimeoutKey", getLockTimeoutKey())
            put("offlineMode", isOfflineMode())
            put("torEnabled", isTorEnabled())
            put("torProxyHost", getTorProxyHost())
            put("torProxyPort", getTorProxyPort())
            put("balanceUnit", getBalanceUnit())
            put("electrumMainnet", electrumConfigToJson(loadElectrumConfigForNetwork("mainnet")))
            put("electrumTestnet", electrumConfigToJson(loadElectrumConfigForNetwork("testnet")))
        }
    }

    fun importBackupSettings(settings: JSONObject) {
        // State backups never silently replace connectivity or security policy. Only
        // presentation/feature preferences are restored here; sensitive settings must be
        // reviewed and changed explicitly in the app.
        if (settings.has("phoneSignerOptionsEnabled")) setPhoneSignerOptionsEnabled(settings.optBoolean("phoneSignerOptionsEnabled"))
        settings.optNullableString("balanceUnit")?.let { setBalanceUnit(it) }
    }

    private fun loadElectrumConfigForNetwork(network: String): ElectrumConfig {
        val suffix = if (network == "testnet") "_testnet" else "_mainnet"
        val defaultPort = if (network == "testnet") 60002 else 50002
        val hasPerNetworkConfig = prefs.contains("server_url$suffix")
        return if (hasPerNetworkConfig) {
            ElectrumConfig(
                serverUrl = prefs.getString("server_url$suffix", "electrum.blockstream.info") ?: "electrum.blockstream.info",
                port = prefs.getInt("server_port$suffix", defaultPort),
                useSsl = prefs.getBoolean("use_ssl$suffix", true),
                isCustom = prefs.getBoolean("use_custom_server$suffix", false),
                pinnedCert = prefs.getString("pinned_cert$suffix", null),
                useTor = prefs.getBoolean("use_tor$suffix", false)
            )
        } else if (network == "testnet") {
            ElectrumConfig(serverUrl = "electrum.blockstream.info", port = 60002, useSsl = true, isCustom = false)
        } else {
            ElectrumConfig(
                serverUrl = prefs.getString("server_url", "electrum.blockstream.info") ?: "electrum.blockstream.info",
                port = prefs.getInt("server_port", 50002),
                useSsl = prefs.getBoolean("use_ssl", true),
                isCustom = prefs.getBoolean("use_custom_server", false)
            )
        }
    }

    private fun saveElectrumConfigForNetwork(network: String, config: ElectrumConfig) {
        val suffix = if (network == "testnet") "_testnet" else "_mainnet"
        val cleanUrl = config.serverUrl.removePrefix("ssl://").removePrefix("tcp://").trim()
        prefs.edit {
            putBoolean("use_custom_server$suffix", config.isCustom)
            putString("server_url$suffix", cleanUrl)
            putInt("server_port$suffix", config.port)
            putBoolean("use_ssl$suffix", config.useSsl)
            if (config.pinnedCert != null) putString("pinned_cert$suffix", config.pinnedCert) else remove("pinned_cert$suffix")
            putBoolean("use_tor$suffix", config.useTor)
        }
    }

    private fun electrumConfigToJson(config: ElectrumConfig): JSONObject {
        return JSONObject().apply {
            put("serverUrl", config.serverUrl)
            put("port", config.port)
            put("useSsl", config.useSsl)
            put("isCustom", config.isCustom)
            putNullable("pinnedCert", config.pinnedCert)
            put("useTor", config.useTor)
        }
    }

    private fun electrumConfigFromJson(json: JSONObject, network: String): ElectrumConfig {
        val defaultPort = if (network == "testnet") 60002 else 50002
        return ElectrumConfig(
            serverUrl = json.optString("serverUrl", "electrum.blockstream.info"),
            port = json.optInt("port", defaultPort),
            useSsl = json.optBoolean("useSsl", true),
            isCustom = json.optBoolean("isCustom", false),
            pinnedCert = json.optNullableString("pinnedCert"),
            useTor = json.optBoolean("useTor", false)
        )
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value == null) put(name, JSONObject.NULL) else put(name, value)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (!has(name) || isNull(name)) null else optString(name)
    }
}
