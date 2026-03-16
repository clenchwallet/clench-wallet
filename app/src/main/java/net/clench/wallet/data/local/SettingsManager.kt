package net.clench.wallet.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.clench.wallet.domain.model.ElectrumConfig
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
        prefs.edit {
            putBoolean("use_custom_server", config.isCustom)
            putString("server_url", cleanUrl)
            putInt("server_port", config.port)
            putBoolean("use_ssl", config.useSsl)
        }
    }

    fun loadElectrumConfig(): ElectrumConfig {
        return ElectrumConfig(
            serverUrl = prefs.getString("server_url", "electrum.blockstream.info") ?: "electrum.blockstream.info",
            port = prefs.getInt("server_port", 50002),
            useSsl = prefs.getBoolean("use_ssl", true),
            isCustom = prefs.getBoolean("use_custom_server", false)
        )
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

    fun isBiometricForSeedEnabled(): Boolean = prefs.getBoolean("biometric_seed", true)
    fun setBiometricForSeedEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("biometric_seed", enabled) }
    }

    fun isBiometricForSendEnabled(): Boolean = prefs.getBoolean("biometric_send", true)
    fun setBiometricForSendEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("biometric_send", enabled) }
    }

    fun getAppLockMode(): String = prefs.getString("app_lock_mode", "biometric") ?: "biometric"
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

    // --- Onboarding ---

    fun isOnboarded(): Boolean = prefs.getBoolean("onboarded", false)
    fun setOnboarded() { prefs.edit { putBoolean("onboarded", true) } }

    // --- Balance display unit ---

    fun getBalanceUnit(): String = prefs.getString("balance_unit", "SATS") ?: "SATS"
    fun setBalanceUnit(unit: String) {
        prefs.edit { putString("balance_unit", unit) }
    }
}
