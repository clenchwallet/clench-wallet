package net.clench.wallet.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.entity.TransactionLabelEntity
import net.clench.wallet.data.util.Bip329
import net.clench.wallet.domain.model.TransactionItem
import net.clench.wallet.domain.repository.BitcoinRepository
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class BalanceUnit { SATS, BTC, USD, HIDDEN }

@HiltViewModel
class HomeViewModel @Inject constructor(
    internal val bitcoinRepository: BitcoinRepository,
    internal val settingsManager: SettingsManager,
    private val transactionLabelDao: TransactionLabelDao
) : ViewModel() {

    data class UiState(
        val walletName: String = "Clench Wallet",
        val balanceSat: Long = 0L,
        val confirmedSat: Long = 0L,
        val pendingSat: Long = 0L,
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
        val isOfflineMode: Boolean = false,
        val isTorEnabled: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var lastPriceFetchMs: Long = 0L
    private val PRICE_CACHE_MS = 5 * 60 * 1000L // 5 minutes

    // R7-1/R7-22: Store auto-refresh job to prevent stacking — cancel before starting new one
    private var autoRefreshJob: Job? = null
    private var currentWalletId: String? = null

    fun load(walletId: String) {
        // Cancel previous auto-refresh loop to prevent stacking (R7-22)
        autoRefreshJob?.cancel()
        currentWalletId = walletId
        // Remember this as the last-viewed wallet for startup
        settingsManager.setLastViewedWalletId(walletId)

        viewModelScope.launch {
            val isTestnet = settingsManager.isTestnet()
            val savedUnit = try { BalanceUnit.valueOf(settingsManager.getBalanceUnit()) } catch (_: Exception) { BalanceUnit.SATS }
            // Testnet BTC has no real value — never show USD equivalent
            val effectiveUnit = if (isTestnet && (savedUnit == BalanceUnit.USD || savedUnit == BalanceUnit.HIDDEN)) BalanceUnit.SATS else savedUnit
            _uiState.update { it.copy(
                isLoading = true,
                isTestnet = isTestnet,
                mempoolUrl = settingsManager.getMempoolUrl(),
                isOfflineMode = settingsManager.isOfflineMode(),
                isTorEnabled = settingsManager.isTorEnabled(),
                balanceUnit = effectiveUnit
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
                // For watch-only wallets, BDK classifies all incoming funds as "untrustedPending"
                // because it can't verify they were created by this wallet's keys.
                // Treat untrustedPending as confirmed for display since the chain confirms them.
                val isWatchOnly = _uiState.value.isWatchOnly
                val displayConfirmed = if (isWatchOnly) {
                    balance.confirmedSat + balance.untrustedPendingSat
                } else balance.confirmedSat
                val displayPending = if (isWatchOnly) {
                    balance.trustedPendingSat
                } else balance.trustedPendingSat + balance.untrustedPendingSat
                _uiState.update {
                    it.copy(
                        balanceSat = balance.totalSat,
                        confirmedSat = displayConfirmed,
                        pendingSat = displayPending,
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

        // R7-22: Auto-refresh every 60 seconds — stored as cancellable Job
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                if (isActive) {
                    syncWallet(walletId)
                }
            }
        }
    }

    fun reload(walletId: String) = load(walletId)

    override fun onCleared() {
        super.onCleared()
        autoRefreshJob?.cancel()
    }

    fun cycleBalanceUnit() {
        _uiState.update { state ->
            val next = when (state.balanceUnit) {
                BalanceUnit.SATS -> BalanceUnit.BTC
                // Skip USD on testnet — testnet BTC has no real value
                BalanceUnit.BTC -> if (state.isTestnet) BalanceUnit.SATS else BalanceUnit.USD
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
        // Never phone home in offline mode
        if (settingsManager.isOfflineMode()) return

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
                    fetchPriceFromCoinbase()
                        ?: fetchPriceFromMempoolSpace()
                        ?: fetchPriceFromCoinGecko()
                        ?: throw Exception("All price sources failed")
                }
                android.util.Log.d("HomeVM", "BTC price: $$price")
                lastPriceFetchMs = System.currentTimeMillis()
                _uiState.update { it.copy(btcPriceUsd = price, priceStale = false) }
            } catch (e: Exception) {
                android.util.Log.w("HomeVM", "BTC price fetch failed: ${e.message}")
                _uiState.update { it.copy(priceStale = true) }
            }
        }
    }

    private fun fetchPriceFromCoinbase(): Double? {
        return try {
            val conn = URL("https://api.coinbase.com/v2/prices/BTC-USD/spot").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(json).getJSONObject("data").getDouble("amount")
        } catch (e: Exception) {
            android.util.Log.w("HomeVM", "Coinbase price failed: ${e.message}")
            null
        }
    }

    private fun fetchPriceFromMempoolSpace(): Double? {
        return try {
            val conn = URL("https://mempool.space/api/v1/prices").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(json).getDouble("USD")
        } catch (e: Exception) {
            android.util.Log.w("HomeVM", "mempool.space price failed: ${e.message}")
            null
        }
    }

    private fun fetchPriceFromCoinGecko(): Double? {
        return try {
            val conn = URL("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(json).getJSONObject("bitcoin").getDouble("usd")
        } catch (e: Exception) {
            android.util.Log.w("HomeVM", "CoinGecko price failed: ${e.message}")
            null
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
                val isWO = _uiState.value.isWatchOnly
                val syncConfirmed = if (isWO) balance.confirmedSat + balance.untrustedPendingSat else balance.confirmedSat
                val syncPending = if (isWO) balance.trustedPendingSat else balance.trustedPendingSat + balance.untrustedPendingSat
                _uiState.update {
                    it.copy(
                        balanceSat = balance.totalSat,
                        confirmedSat = syncConfirmed,
                        pendingSat = syncPending,
                        transactions = txs,
                        isSyncing = false,
                        syncError = null
                    )
                }
            } catch (e: Exception) {
                // Keep cached data but set sync error with user-friendly message
                val friendlyMsg = when {
                    e is kotlinx.coroutines.TimeoutCancellationException ||
                    e is java.util.concurrent.TimeoutException ->
                        "Sync timed out — server may be unreachable or port blocked by your network. Try a different server or switch to TCP (unencrypted)."
                    e.message?.contains("Connection refused") == true ->
                        "Connection refused — is your Electrum server running?\n\nTip: If your server uses a self-signed certificate, disable SSL and use port 50001 (plain TCP). Note: Clench uses BDK which does not support self-signed certificates over SSL."
                    e.message?.contains("Unable to resolve host") == true ||
                    e.message?.contains("UnknownHostException") == true ->
                        "Cannot reach server — check hostname and network"
                    e.message?.contains("SSL") == true || e.message?.contains("TLS") == true ||
                    e.message?.contains("certificate") == true || e.message?.contains("Certificate") == true ||
                    e.message?.contains("self-signed") == true || e.message?.contains("handshake") == true ->
                        "SSL/TLS error — your server may be using a self-signed certificate.\n\nClench uses BDK which does not support self-signed certificates. Disable SSL and use port 50001 (plain TCP) instead."
                    e.message?.contains("descriptor") == true ->
                        "Wallet descriptor error — try deleting and re-importing this wallet"
                    else -> e.message ?: "Unknown sync error"
                }
                _uiState.update { it.copy(isSyncing = false, syncError = friendlyMsg) }
            }
        }
    }

    // BIP-329 import result message for snackbar
    private val _importResult = MutableStateFlow<String?>(null)
    val importResult = _importResult.asStateFlow()

    fun clearImportResult() {
        _importResult.value = null
    }

    fun exportLabels(walletId: String, context: Context) {
        viewModelScope.launch {
            try {
                val labels = transactionLabelDao.getForWallet(walletId)
                if (labels.isEmpty()) {
                    _importResult.value = "No labels to export"
                    return@launch
                }
                val jsonl = Bip329.exportLabels(labels)
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val fileName = "clench-labels-$dateStr.jsonl"
                val file = File(context.cacheDir, fileName)
                file.writeText(jsonl)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/jsonl"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export labels"))
            } catch (e: Exception) {
                _importResult.value = "Export failed: ${e.message}"
            }
        }
    }

    fun importLabels(walletId: String, uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val jsonl = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                        ?: throw Exception("Could not read file")
                }
                val parsed = Bip329.importLabels(jsonl)
                if (parsed.isEmpty()) {
                    _importResult.value = "No transaction labels found in file"
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    for ((txid, label) in parsed) {
                        transactionLabelDao.upsert(
                            TransactionLabelEntity(
                                key = "$walletId:$txid",
                                walletId = walletId,
                                txid = txid,
                                label = label
                            )
                        )
                    }
                }
                _importResult.value = "Imported ${parsed.size} labels"
                // Reload transactions to show new labels
                currentWalletId?.let { reload(it) }
            } catch (e: Exception) {
                _importResult.value = "Import failed: ${e.message}"
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
