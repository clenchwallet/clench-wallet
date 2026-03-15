package net.clench.wallet.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
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

    fun getNetwork(): String = prefs.getString("network", "mainnet") ?: "mainnet"

    fun setNetwork(network: String) {
        prefs.edit { putString("network", network) }
    }

    fun isTestnet(): Boolean = getNetwork() == "testnet"
}
