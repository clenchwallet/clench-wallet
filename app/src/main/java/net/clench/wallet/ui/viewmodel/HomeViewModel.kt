package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.TransactionItem
import net.clench.wallet.domain.repository.BitcoinRepository
import org.json.JSONObject
import java.net.URL
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

enum class BalanceUnit { SATS, BTC, USD, HIDDEN }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    data class UiState(
        val walletName: String = "Clench Wallet",
        val balanceSat: Long = 0L,
        val transactions: List<TransactionItem> = emptyList(),
        val isLoading: Boolean = false,
        val isSyncing: Boolean = false,
        val syncError: String? = null,
        val error: String? = null,
        val isWatchOnly: Boolean = false,
        val balanceUnit: BalanceUnit = BalanceUnit.SATS,
        val btcPriceUsd: Double? = null,
        val priceStale: Boolean = false,
        val isTestnet: Boolean = false,
        val mempoolUrl: String = "https://mempool.space",
        val isOfflineMode: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var lastPriceFetchMs: Long = 0L
    private val PRICE_CACHE_MS = 5 * 60 * 1000L // 5 minutes

    fun load(walletId: String) {
        viewModelScope.launch {
            val savedUnit = try { BalanceUnit.valueOf(settingsManager.getBalanceUnit()) } catch (_: Exception) { BalanceUnit.SATS }
            _uiState.update { it.copy(
                isLoading = true,
                isTestnet = settingsManager.isTestnet(),
                mempoolUrl = settingsManager.getMempoolUrl(),
                isOfflineMode = settingsManager.isOfflineMode(),
                balanceUnit = savedUnit
            ) }
            try {
                // Load wallet name from DB
                try {
                    val wallets = bitcoinRepository.listWallets()
                    val thisWallet = wallets.find { it.id == walletId }
                    _uiState.update { it.copy(
                        walletName = thisWallet?.name ?: "My Wallet",
                        isWatchOnly = thisWallet?.isWatchOnly ?: false
                    ) }
                } catch (e: Exception) { /* ignore */ }

                // First show cached balance and transactions
                val balance = bitcoinRepository.getBalance(walletId)
                val txs = bitcoinRepository.getTransactions(walletId)
                _uiState.update {
                    it.copy(
                        balanceSat = balance.totalSat,
                        transactions = txs,
                        isLoading = false
                    )
                }

                // Then sync in background
                syncWallet(walletId)

                // Fetch BTC price for USD display
                fetchBtcPrice()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }

        // Auto-refresh every 60 seconds
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                syncWallet(walletId)
            }
        }
    }

    fun reload(walletId: String) = load(walletId)

    fun cycleBalanceUnit() {
        _uiState.update { state ->
            val next = when (state.balanceUnit) {
                BalanceUnit.SATS -> BalanceUnit.BTC
                BalanceUnit.BTC -> BalanceUnit.USD
                BalanceUnit.USD -> BalanceUnit.HIDDEN
                BalanceUnit.HIDDEN -> BalanceUnit.SATS
            }
            // Refresh price when cycling to USD if stale
            if (next == BalanceUnit.USD) {
                fetchBtcPrice()
            }
            settingsManager.setBalanceUnit(next.name)
            state.copy(balanceUnit = next)
        }
    }

    fun fetchBtcPrice() {
        val now = System.currentTimeMillis()
        val cached = _uiState.value.btcPriceUsd
        if (cached != null && now - lastPriceFetchMs < PRICE_CACHE_MS) {
            // Still fresh
            return
        }
        // Mark stale if we have an old price
        if (cached != null && now - lastPriceFetchMs >= PRICE_CACHE_MS) {
            _uiState.update { it.copy(priceStale = true) }
        }

        viewModelScope.launch {
            try {
                val price = withContext(Dispatchers.IO) {
                    val url = URL("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd")
                    val json = url.readText()
                    val obj = JSONObject(json)
                    obj.getJSONObject("bitcoin").getDouble("usd")
                }
                lastPriceFetchMs = System.currentTimeMillis()
                _uiState.update { it.copy(btcPriceUsd = price, priceStale = false) }
            } catch (e: Exception) {
                // Keep old price if available, mark stale
                _uiState.update { it.copy(priceStale = true) }
            }
        }
    }

    private fun syncWallet(walletId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncError = null) }
            try {
                val config = settingsManager.loadElectrumConfig()
                val balance = bitcoinRepository.syncWallet(walletId, config)
                val txs = bitcoinRepository.getTransactions(walletId)

                // Update balance and transactions after successful sync
                _uiState.update {
                    it.copy(
                        balanceSat = balance.totalSat,
                        transactions = txs,
                        isSyncing = false,
                        syncError = null
                    )
                }
            } catch (e: Exception) {
                // Keep cached data but set sync error with user-friendly message
                val friendlyMsg = when {
                    e is kotlinx.coroutines.TimeoutCancellationException ->
                        "Sync timed out — check your Electrum server connection"
                    e.message?.contains("Connection refused") == true ->
                        "Connection refused — is your Electrum server running?"
                    e.message?.contains("Unable to resolve host") == true ||
                    e.message?.contains("UnknownHostException") == true ->
                        "Cannot reach server — check hostname and network"
                    e.message?.contains("SSL") == true || e.message?.contains("TLS") == true ->
                        "SSL error — try toggling SSL in server settings"
                    e.message?.contains("descriptor") == true ->
                        "Wallet descriptor error — try deleting and re-importing this wallet"
                    else -> e.message ?: "Unknown sync error"
                }
                _uiState.update { it.copy(isSyncing = false, syncError = friendlyMsg) }
            }
        }
    }

    companion object {
        fun formatBalance(sats: Long, unit: BalanceUnit, btcPriceUsd: Double?, priceStale: Boolean): String {
            return when (unit) {
                BalanceUnit.SATS -> {
                    val fmt = NumberFormat.getNumberInstance(Locale.US)
                    "${fmt.format(sats)} sats"
                }
                BalanceUnit.BTC -> {
                    val btc = sats / 100_000_000.0
                    val formatted = String.format(Locale.US, "%.8f", btc)
                        .trimEnd('0')
                        .let { if (it.endsWith('.')) it + "0" else it }
                    "$formatted BTC"
                }
                BalanceUnit.USD -> {
                    if (btcPriceUsd == null) {
                        "USD unavailable"
                    } else {
                        val btc = sats / 100_000_000.0
                        val usd = btc * btcPriceUsd
                        val fmt = NumberFormat.getCurrencyInstance(Locale.US)
                        val prefix = if (priceStale) "~" else ""
                        "$prefix${fmt.format(usd)}"
                    }
                }
                BalanceUnit.HIDDEN -> "••••••"
            }
        }
    }
}
