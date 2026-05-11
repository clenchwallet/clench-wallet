package net.clench.wallet.ui.viewmodel

import android.content.Context
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
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.components.SatscardFundingSlotResult
import net.clench.wallet.ui.util.copyToClipboardWithAutoClear
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class FundSatscardViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val torAwareHttpClient: TorAwareHttpClient,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val walletName: String = "",
        val isTestnet: Boolean = false,
        val isLoading: Boolean = false,
        val isNfcBusy: Boolean = false,
        val isCheckingBalance: Boolean = false,
        val slot: Long? = null,
        val address: String = "",
        val summary: String? = null,
        val confirmedSat: Long = 0L,
        val pendingSat: Long = 0L,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) {
        if (_uiState.value.walletId == walletId && _uiState.value.walletName.isNotBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(walletId = walletId, isLoading = true, error = null) }
            try {
                val wallet = bitcoinRepository.getWalletEntity(walletId)
                val isTestnet = when (wallet?.network) {
                    "testnet" -> true
                    "mainnet" -> false
                    else -> settingsManager.isTestnet()
                }
                _uiState.update {
                    it.copy(
                        walletName = wallet?.name ?: "This wallet",
                        isTestnet = isTestnet,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Could not load wallet"
                    )
                }
            }
        }
    }

    fun setNfcBusy(busy: Boolean) {
        _uiState.update { it.copy(isNfcBusy = busy, error = if (busy) null else it.error) }
    }

    fun applySlotResult(result: SatscardFundingSlotResult) {
        _uiState.update {
            it.copy(
                isNfcBusy = false,
                slot = result.slot,
                address = result.address,
                summary = result.summary,
                confirmedSat = 0L,
                pendingSat = 0L,
                error = null
            )
        }
        refreshBalance()
    }

    fun showError(message: String) {
        _uiState.update {
            it.copy(
                isNfcBusy = false,
                isCheckingBalance = false,
                error = message
            )
        }
    }

    fun refreshBalance() {
        val address = _uiState.value.address
        if (address.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingBalance = true, error = null) }
            try {
                val balance = withContext(Dispatchers.IO) { fetchAddressBalance(address, _uiState.value.isTestnet) }
                _uiState.update {
                    it.copy(
                        isCheckingBalance = false,
                        confirmedSat = balance.confirmedSat,
                        pendingSat = balance.pendingSat
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCheckingBalance = false,
                        error = "Could not check SATSCARD address balance: ${e.message}"
                    )
                }
            }
        }
    }

    fun copyAddress() {
        val address = _uiState.value.address
        if (address.isBlank()) return
        copyToClipboardWithAutoClear(context, "SATSCARD address", address)
    }

    fun bip21Uri(): String {
        val address = _uiState.value.address.trim()
        return if (address.isBlank()) "" else "bitcoin:$address"
    }

    fun formattedBalance(): String {
        val total = _uiState.value.confirmedSat + _uiState.value.pendingSat
        return NumberFormat.getNumberInstance(Locale.US).format(total)
    }

    private fun fetchAddressBalance(address: String, isTestnet: Boolean): AddressBalance {
        val baseUrl = mempoolApiBaseUrl(isTestnet)
        val json = JSONObject(torAwareHttpClient.fetchText("$baseUrl/api/address/$address"))
        val chainStats = json.getJSONObject("chain_stats")
        val mempoolStats = json.getJSONObject("mempool_stats")
        val confirmed = chainStats.getLong("funded_txo_sum") - chainStats.getLong("spent_txo_sum")
        val pending = mempoolStats.getLong("funded_txo_sum") - mempoolStats.getLong("spent_txo_sum")
        return AddressBalance(
            confirmedSat = confirmed.coerceAtLeast(0L),
            pendingSat = pending.coerceAtLeast(0L)
        )
    }

    private fun mempoolApiBaseUrl(isTestnet: Boolean): String {
        val baseUrl = settingsManager.getMempoolUrl().trim().trimEnd('/')
        if (!isTestnet) return baseUrl
        val lower = baseUrl.lowercase(Locale.US)
        return if (lower.endsWith("/testnet") || lower.contains("/testnet/")) {
            baseUrl
        } else {
            "$baseUrl/testnet"
        }
    }

    private data class AddressBalance(
        val confirmedSat: Long,
        val pendingSat: Long
    )
}
