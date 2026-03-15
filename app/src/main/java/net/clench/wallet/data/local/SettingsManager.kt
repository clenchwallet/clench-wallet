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
            serverUrl = prefs.getString("server_url", "ssl://electrum.blockstream.info") ?: "ssl://electrum.blockstream.info",
            port = prefs.getInt("server_port", 700),
            useSsl = prefs.getBoolean("use_ssl", true),
            isCustom = prefs.getBoolean("use_custom_server", false)
        )
    }

    fun isConfigured(): Boolean = prefs.contains("server_url")
}
