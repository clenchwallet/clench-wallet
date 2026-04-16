package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.FeeEstimates
import net.clench.wallet.domain.repository.BitcoinRepository
import org.json.JSONObject
import javax.inject.Inject

enum class FeeTier { ECONOMY, STANDARD, PRIORITY, CUSTOM }
enum class AmountUnit { SATS, BTC, USD }

data class RecipientEntry(
    val address: String = "",
    val amountSat: String = "",
    val label: String = ""
)

@HiltViewModel
class SendViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val psbtStore: PsbtStore,
    private val torAwareHttpClient: TorAwareHttpClient
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val isWatchOnly: Boolean = false,
        val preferredHardwareWallet: String? = null,
        val toAddress: String = "",
        val amountSat: String = "",
        val amountDisplay: String = "", // display value in selected unit
        val amountUnit: AmountUnit = AmountUnit.SATS,
        val feeRate: String = "2",
        val sendMax: Boolean = false,
        val txHex: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val availableBalanceSat: Long = 0L,
        val frozenUtxoCount: Int = 0,
        val frozenAmountSat: Long = 0L,
        val utxoTxid: String? = null,
        val utxoVout: Int? = null,
        val selectedUtxoOutpoints: List<String> = emptyList(),
        val biometricForSendEnabled: Boolean = true,
        // Fee estimation
        val feeEstimates: FeeEstimates? = null,
        val selectedFeeTier: FeeTier = FeeTier.STANDARD,
        val feeEstimateError: String? = null,
        val isEstimatingFees: Boolean = false,
        val btcPriceUsd: Double? = null,
        val label: String = "",
        val broadcastSuccess: Boolean = false,
        val broadcastTxid: String? = null,
        val recipients: List<RecipientEntry> = listOf(RecipientEntry())
    )

    // Helper to distinguish single-UTXO mode from full wallet drain
    private val UiState.isSingleUtxoMode: Boolean get() = utxoTxid != null && selectedUtxoOutpoints.isEmpty()

    // Live check: does the entered amount exceed the selected UTXO(s)?
    fun exceedsUtxoSelection(state: UiState): Boolean {
        if (state.utxoTxid == null && state.selectedUtxoOutpoints.isEmpty()) return false
        if (state.availableBalanceSat <= 0) return false
        if (state.sendMax) return false
        val amount = state.amountSat.toLongOrNull() ?: return false
        return amount > state.availableBalanceSat
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(
        walletId: String,
        preselectedUtxoTxid: String? = null,
        preselectedUtxoVout: Int? = null,
        preselectedOutpoints: List<String> = emptyList()
    ) {
        _uiState.update { it.copy(
            walletId = walletId,
            biometricForSendEnabled = settingsManager.isBiometricForSendEnabled()
        ) }
        viewModelScope.launch {
            try {
                val wallets = bitcoinRepository.listWallets()
                val wallet = wallets.find { it.id == walletId }
                val balance = bitcoinRepository.getBalance(walletId)

                // Resolve available balance based on coin control selection — atomic, no race condition
                val resolvedAmount: Long? = when {
                    // Multiple UTXOs from coin control — sum their amounts
                    preselectedOutpoints.isNotEmpty() -> {
                        try {
                            val utxos = bitcoinRepository.listUnspent(walletId)
                            val utxoMap = utxos.associateBy { "${it.txid}:${it.vout}" }
                            val sum = preselectedOutpoints.sumOf { utxoMap[it]?.amountSat ?: 0L }
                            if (sum > 0) sum else null
                        } catch (e: Exception) {
                            android.util.Log.w("SendVM", "resolveSelectedUtxoAmounts failed: ${e.message}")
                            null
                        }
                    }
                    // Single UTXO from "Spend this UTXO" — match by txid
                    preselectedUtxoTxid != null -> {
                        try {
                            val utxos = bitcoinRepository.listUnspent(walletId)
                            val matches = utxos.filter { it.txid == preselectedUtxoTxid }
                            when {
                                preselectedUtxoVout != null && preselectedUtxoVout >= 0 ->
                                    matches.find { it.vout.toInt() == preselectedUtxoVout }?.amountSat
                                        ?: matches.firstOrNull()?.amountSat
                                else -> matches.firstOrNull()?.amountSat
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("SendVM", "resolveUtxoAmount failed: ${e.message}")
                            null
                        }
                    }
                    else -> null
                }

                // When no coin control is active, subtract frozen UTXO amounts from available balance
                var frozenCount = 0
                var frozenSats = 0L
                val effectiveBalance = if (resolvedAmount != null) {
                    resolvedAmount
                } else {
                    try {
                        val utxos = bitcoinRepository.listUnspent(walletId)
                        val frozenUtxos = utxos.filter { it.isFrozen }
                        val frozenAmount = frozenUtxos.sumOf { it.amountSat }
                        frozenCount = frozenUtxos.size
                        frozenSats = frozenAmount
                        if (frozenAmount > 0) {
                            android.util.Log.d("SendVM", "Subtracting $frozenAmount frozen sats (${frozenUtxos.size} UTXOs) from available balance")
                        }
                        (balance.spendableSat - frozenAmount).coerceAtLeast(0L)
                    } catch (e: Exception) {
                        android.util.Log.w("SendVM", "Could not compute frozen balance: ${e.message}")
                        balance.spendableSat
                    }
                }

                _uiState.update { it.copy(
                    isWatchOnly = wallet?.isWatchOnly ?: false,
                    preferredHardwareWallet = wallet?.preferredHardwareWallet,
                    availableBalanceSat = effectiveBalance,
                    frozenUtxoCount = frozenCount,
                    frozenAmountSat = frozenSats
                ) }
            } catch (e: Exception) { /* show 0 */ }
        }
        fetchFeeEstimates()
        fetchBtcPrice()
    }

    /**
     * After a specific UTXO is pre-selected (from "Spend this UTXO"),
     * look up that UTXO's amount and show it as the available balance
     * so the user sees the correct spendable amount for that output, not the full wallet balance.
     */
    fun resolveUtxoAmount(walletId: String, txid: String, vout: Int) {
        viewModelScope.launch {
            try {
                val utxos = bitcoinRepository.listUnspent(walletId)
                val match = utxos.find { it.txid == txid && it.vout.toInt() == vout }
                if (match != null) {
                    _uiState.update { it.copy(availableBalanceSat = match.amountSat) }
                }
            } catch (_: Exception) { }
        }
    }

    private fun fetchFeeEstimates() {
        viewModelScope.launch {
            _uiState.update { it.copy(isEstimatingFees = true, feeEstimateError = null) }
            try {
                val estimates = bitcoinRepository.estimateFees()
                _uiState.update { state ->
                    val feeRate = when (state.selectedFeeTier) {
                        FeeTier.ECONOMY -> estimates.economy
                        FeeTier.STANDARD -> estimates.standard
                        FeeTier.PRIORITY -> estimates.priority
                        FeeTier.CUSTOM -> state.feeRate.toFloatOrNull() ?: estimates.standard
                    }
                    state.copy(
                        feeEstimates = estimates,
                        isEstimatingFees = false,
                        feeRate = feeRate.toInt().coerceAtLeast(1).toString()
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isEstimatingFees = false,
                    feeEstimateError = "Fee estimation failed — using manual input"
                ) }
            }
        }
    }

    private fun fetchBtcPrice() {
        // Never phone home in offline mode
        if (settingsManager.isOfflineMode()) return
        // H-5: Respect user's BTC price preference
        if (!settingsManager.isBtcPriceEnabled()) {
            android.util.Log.d("SendVM", "BTC price fetch disabled by user setting")
            return
        }

        viewModelScope.launch {
            try {
                val price = withContext(Dispatchers.IO) {
                    fetchPriceFromCoinbase()
                        ?: fetchPriceFromMempoolSpace()
                        ?: fetchPriceFromCoinGecko()
                        ?: throw Exception("All price sources failed")
                }
                android.util.Log.d("SendVM", "BTC price: $$price")
                _uiState.update { it.copy(btcPriceUsd = price) }
            } catch (e: Exception) {
                android.util.Log.w("SendVM", "BTC price fetch failed: ${e.message}")
            }
        }
    }

    private fun fetchPriceFromCoinbase(): Double? {
        return try {
            val json = torAwareHttpClient.fetchText("https://api.coinbase.com/v2/prices/BTC-USD/spot")
            JSONObject(json).getJSONObject("data").getDouble("amount")
        } catch (e: Exception) {
            android.util.Log.w("SendVM", "Coinbase price failed: ${e.message}")
            null
        }
    }

    private fun fetchPriceFromMempoolSpace(): Double? {
        return try {
            val json = torAwareHttpClient.fetchText("https://mempool.space/api/v1/prices")
            JSONObject(json).getDouble("USD")
        } catch (e: Exception) {
            android.util.Log.w("SendVM", "mempool.space price failed: ${e.message}")
            null
        }
    }

    private fun fetchPriceFromCoinGecko(): Double? {
        return try {
            val json = torAwareHttpClient.fetchText("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd")
            JSONObject(json).getJSONObject("bitcoin").getDouble("usd")
        } catch (e: Exception) {
            android.util.Log.w("SendVM", "CoinGecko price failed: ${e.message}")
            null
        }
    }

    fun selectFeeTier(tier: FeeTier) {
        _uiState.update { state ->
            val estimates = state.feeEstimates
            val feeRate = if (tier == FeeTier.CUSTOM) {
                state.feeRate // keep current
            } else if (estimates != null) {
                when (tier) {
                    FeeTier.ECONOMY -> estimates.economy.toInt().coerceAtLeast(1).toString()
                    FeeTier.STANDARD -> estimates.standard.toInt().coerceAtLeast(1).toString()
                    FeeTier.PRIORITY -> estimates.priority.toInt().coerceAtLeast(1).toString()
                    FeeTier.CUSTOM -> state.feeRate
                }
            } else state.feeRate

            state.copy(selectedFeeTier = tier, feeRate = feeRate)
        }
    }

    fun setUtxo(txid: String?, vout: Int? = 0) {
        _uiState.update { it.copy(utxoTxid = txid, utxoVout = vout) }
    }

    fun setSelectedUtxos(outpoints: List<String>) {
        _uiState.update { it.copy(selectedUtxoOutpoints = outpoints) }
    }

    fun setAddress(addr: String) {
        // Parse BIP-21 URI: bitcoin:address?amount=X&label=...
        val parsed = parseBip21(addr)
        _uiState.update {
            it.copy(
                toAddress = parsed.address,
                error = null
            )
        }
        // If BIP-21 included an amount, set it
        parsed.amountBtc?.let { btcAmount ->
            val sats = (btcAmount * 100_000_000).toLong()
            if (sats > 0) setAmount(sats.toString())
        }
    }

    private data class Bip21Parsed(
        val address: String,
        val amountBtc: Double? = null,
        val label: String? = null
    )

    private fun parseBip21(input: String): Bip21Parsed {
        val trimmed = input.trim()
        if (!trimmed.startsWith("bitcoin:", ignoreCase = true)) {
            // Normalize bech32 to lowercase when scanned as plain address
            val addr = if (trimmed.startsWith("bc1", ignoreCase = true) ||
                trimmed.startsWith("tb1", ignoreCase = true)) {
                trimmed.lowercase()
            } else {
                trimmed
            }
            return Bip21Parsed(address = addr)
        }
        val withoutScheme = trimmed.substringAfter(":")
        val address = withoutScheme.substringBefore("?")
        val queryString = if (withoutScheme.contains("?")) withoutScheme.substringAfter("?") else null

        var amount: Double? = null
        var label: String? = null

        queryString?.split("&")?.forEach { param ->
            val key = param.substringBefore("=").lowercase()
            val value = param.substringAfter("=", "")
            when (key) {
                "amount" -> amount = value.toDoubleOrNull()
                "label" -> label = java.net.URLDecoder.decode(value, "UTF-8")
            }
        }

        // Bech32/bech32m addresses are case-insensitive (BIP-173/BIP-350).
        // QR codes encode them uppercase for efficiency. Normalize to lowercase
        // since lowercase is the canonical convention.
        val normalizedAddress = if (address.startsWith("bc1", ignoreCase = true) ||
            address.startsWith("tb1", ignoreCase = true)) {
            address.lowercase()
        } else {
            address
        }

        return Bip21Parsed(
            address = normalizedAddress,
            amountBtc = amount,
            label = label
        )
    }

    fun setError(msg: String) = _uiState.update { it.copy(error = msg) }
    fun setAmount(amt: String) = _uiState.update { it.copy(amountSat = amt) }

    fun cycleAmountUnit() {
        val state = _uiState.value
        val isTestnet = settingsManager.isTestnet()
        val newUnit = when (state.amountUnit) {
            AmountUnit.SATS -> AmountUnit.BTC
            // Skip USD on testnet — testnet BTC has no real value
            AmountUnit.BTC -> if (!isTestnet && state.btcPriceUsd != null) AmountUnit.USD else AmountUnit.SATS
            AmountUnit.USD -> AmountUnit.SATS
        }
        // Convert current sats value to new unit for display
        val currentSats = state.amountSat.toLongOrNull() ?: 0L
        val newDisplay = when (newUnit) {
            AmountUnit.SATS -> if (currentSats > 0) currentSats.toString() else ""
            AmountUnit.BTC -> if (currentSats > 0) String.format("%.8f", currentSats / 100_000_000.0).trimEnd('0').trimEnd('.') else ""
            AmountUnit.USD -> if (currentSats > 0 && state.btcPriceUsd != null) String.format("%.2f", currentSats / 100_000_000.0 * state.btcPriceUsd) else ""
        }
        _uiState.update { it.copy(amountUnit = newUnit, amountDisplay = newDisplay) }
    }

    fun setAmountDisplay(input: String) {
        val state = _uiState.value
        _uiState.update { it.copy(amountDisplay = input, error = null) }
        // Convert display value to sats for internal use
        val sats = when (state.amountUnit) {
            AmountUnit.SATS -> input.toLongOrNull()
            AmountUnit.BTC -> input.toDoubleOrNull()?.let { (it * 100_000_000).toLong() }
            AmountUnit.USD -> input.toDoubleOrNull()?.let { usd ->
                state.btcPriceUsd?.let { price -> (usd / price * 100_000_000).toLong() }
            }
        }
        _uiState.update { it.copy(amountSat = sats?.toString() ?: input) }
    }
    fun setFeeRate(rate: String) = _uiState.update { it.copy(feeRate = rate, selectedFeeTier = FeeTier.CUSTOM) }
    fun setSendMax(max: Boolean) {
        if (max) {
            // Show the available balance as the amount (fees will reduce it at build time)
            val balance = _uiState.value.availableBalanceSat
            _uiState.update { it.copy(
                sendMax = true,
                amountSat = if (balance > 0) balance.toString() else "",
                amountDisplay = if (balance > 0) balance.toString() else "",
                amountUnit = AmountUnit.SATS
            ) }
        } else {
            _uiState.update { it.copy(sendMax = false, amountSat = "", amountDisplay = "") }
        }
    }
    fun setLabel(label: String) = _uiState.update { it.copy(label = label) }

    // --- Batch recipient management ---

    fun addRecipient() {
        _uiState.update { it.copy(recipients = it.recipients + RecipientEntry()) }
    }

    fun removeRecipient(index: Int) {
        _uiState.update { state ->
            if (state.recipients.size <= 1) state
            else state.copy(recipients = state.recipients.filterIndexed { i, _ -> i != index })
        }
    }

    fun updateRecipientAddress(index: Int, address: String) {
        _uiState.update { state ->
            state.copy(recipients = state.recipients.mapIndexed { i, r ->
                if (i == index) r.copy(address = address) else r
            }, error = null)
        }
    }

    fun updateRecipientAmount(index: Int, amount: String) {
        _uiState.update { state ->
            state.copy(recipients = state.recipients.mapIndexed { i, r ->
                if (i == index) r.copy(amountSat = amount) else r
            }, error = null)
        }
    }

    fun updateRecipientLabel(index: Int, label: String) {
        _uiState.update { state ->
            state.copy(recipients = state.recipients.mapIndexed { i, r ->
                if (i == index) r.copy(label = label) else r
            })
        }
    }

    /** True when batch mode is active (more than 1 recipient) */
    val isBatchMode: Boolean get() = _uiState.value.recipients.size > 1

    /** Sum of all recipient amounts in sats (for display) */
    fun batchTotalSats(): Long {
        return _uiState.value.recipients.sumOf { it.amountSat.toLongOrNull() ?: 0L }
    }

    fun buildTx() {
        val state = _uiState.value
        val isBatch = state.recipients.size > 1

        if (isBatch) {
            // --- Batch mode validation ---
            if (state.sendMax) {
                _uiState.update { it.copy(error = "Send max is not available with multiple recipients") }
                return
            }
            for ((idx, r) in state.recipients.withIndex()) {
                if (r.address.isBlank()) {
                    _uiState.update { it.copy(error = "Recipient ${idx + 1}: Please enter an address") }
                    return
                }
                val addrErr = validateAddressForNetwork(r.address, settingsManager.isTestnet())
                if (addrErr != null) {
                    _uiState.update { it.copy(error = "Recipient ${idx + 1}: $addrErr") }
                    return
                }
                val amt = r.amountSat.toLongOrNull()
                if (amt == null || amt <= 0) {
                    _uiState.update { it.copy(error = "Recipient ${idx + 1}: Please enter a valid amount") }
                    return
                }
            }
        } else {
            // --- Single recipient validation (legacy path) ---
            if (!state.sendMax) {
                val amount = state.amountSat.toLongOrNull()
                if (amount == null || amount <= 0) {
                    _uiState.update { it.copy(error = "Please enter a valid amount in satoshis") }
                    return
                }
            }

            if (state.toAddress.isBlank()) {
                _uiState.update { it.copy(error = "Please enter a recipient address") }
                return
            }

            // R7-21: Validate address for current network before calling BDK
            val addressValidation = validateAddressForNetwork(state.toAddress, settingsManager.isTestnet())
            if (addressValidation != null) {
                _uiState.update { it.copy(error = addressValidation) }
                return
            }
        }

        val feeRate = state.feeRate.toFloatOrNull()
        if (feeRate == null || feeRate < 1f) {
            _uiState.update { it.copy(error = "Please enter a valid fee rate (min 1 sat/vB)") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Watch-only wallets must use the PSBT/hardware wallet flow — cannot sign locally
            if (_uiState.value.isWatchOnly) {
                _uiState.update { it.copy(isLoading = false, error = "Use the hardware wallet signing flow to send from a watch-only wallet") }
                return@launch
            }

            try {
                val txHex = if (isBatch) {
                    val recipients = state.recipients.map { r ->
                        net.clench.wallet.domain.repository.Recipient(
                            address = r.address.trim(),
                            amountSat = r.amountSat.toLongOrNull() ?: 0L
                        )
                    }
                    bitcoinRepository.buildBatchTransaction(
                        walletId = state.walletId,
                        recipients = recipients,
                        feeRateSatPerVbyte = feeRate,
                        selectedOutpoints = state.selectedUtxoOutpoints
                    )
                } else {
                    val amountSat = if (state.sendMax) null else state.amountSat.toLongOrNull()
                    bitcoinRepository.buildTransaction(
                        walletId = state.walletId,
                        toAddress = state.toAddress.trim(),
                        amountSat = amountSat,
                        feeRateSatPerVbyte = state.feeRate.toFloatOrNull() ?: 2f,
                        utxoTxid = state.utxoTxid,
                        utxoVout = state.utxoVout?.toUInt(),
                        selectedOutpoints = state.selectedUtxoOutpoints
                    )
                }
                _uiState.update { it.copy(txHex = txHex, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Create an unsigned PSBT for hardware wallet signing.
     * Validates inputs the same way as buildTx, but produces a PSBT instead of signing.
     */
    fun createPsbt(onPsbtReady: (psbtBase64: String) -> Unit) {
        val state = _uiState.value
        val isBatch = state.recipients.size > 1

        if (isBatch) {
            if (state.sendMax) {
                _uiState.update { it.copy(error = "Send max is not available with multiple recipients") }
                return
            }
            for ((idx, r) in state.recipients.withIndex()) {
                if (r.address.isBlank()) {
                    _uiState.update { it.copy(error = "Recipient ${idx + 1}: Please enter an address") }
                    return
                }
                val addrErr = validateAddressForNetwork(r.address, settingsManager.isTestnet())
                if (addrErr != null) {
                    _uiState.update { it.copy(error = "Recipient ${idx + 1}: $addrErr") }
                    return
                }
                val amt = r.amountSat.toLongOrNull()
                if (amt == null || amt <= 0) {
                    _uiState.update { it.copy(error = "Recipient ${idx + 1}: Please enter a valid amount") }
                    return
                }
            }
        } else {
            if (!state.sendMax) {
                val amount = state.amountSat.toLongOrNull()
                if (amount == null || amount <= 0) {
                    _uiState.update { it.copy(error = "Please enter a valid amount in satoshis") }
                    return
                }
            }

            if (state.toAddress.isBlank()) {
                _uiState.update { it.copy(error = "Please enter a recipient address") }
                return
            }

            val addressValidation = validateAddressForNetwork(state.toAddress, settingsManager.isTestnet())
            if (addressValidation != null) {
                _uiState.update { it.copy(error = addressValidation) }
                return
            }
        }

        val feeRate = state.feeRate.toFloatOrNull()
        if (feeRate == null || feeRate < 1f) {
            _uiState.update { it.copy(error = "Please enter a valid fee rate (min 1 sat/vB)") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val psbtBase64 = if (isBatch) {
                    val recipients = state.recipients.map { r ->
                        net.clench.wallet.domain.repository.Recipient(
                            address = r.address.trim(),
                            amountSat = r.amountSat.toLongOrNull() ?: 0L
                        )
                    }
                    bitcoinRepository.createBatchPsbt(
                        walletId = state.walletId,
                        recipients = recipients,
                        feeRateSatPerVbyte = feeRate,
                        selectedOutpoints = state.selectedUtxoOutpoints
                    )
                } else {
                    val amountSat = if (state.sendMax) null else state.amountSat.toLongOrNull()
                    bitcoinRepository.createPsbt(
                        walletId = state.walletId,
                        toAddress = state.toAddress.trim(),
                        amountSat = amountSat,
                        feeRateSatPerVbyte = feeRate,
                        utxoTxid = state.utxoTxid,
                        utxoVout = state.utxoVout?.toUInt(),
                        selectedOutpoints = state.selectedUtxoOutpoints
                    )
                }
                _uiState.update { it.copy(isLoading = false) }
                onPsbtReady(psbtBase64)
            } catch (e: Exception) {
                android.util.Log.e("SendVM", "createPsbt failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun broadcast(onSuccess: (walletId: String) -> Unit) {
        val state = _uiState.value
        val txHex = state.txHex ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val config = settingsManager.loadElectrumConfig()
                val txid = bitcoinRepository.broadcastTransaction(config, txHex)
                android.util.Log.d("SendVM", "Broadcast success: txid=$txid")
                // Save labels — batch mode: combine all recipient labels; single mode: use label field
                val isBatch = state.recipients.size > 1
                if (isBatch) {
                    val batchLabels = state.recipients
                        .filter { it.label.isNotBlank() }
                        .mapIndexed { idx, r -> "${r.address.take(8)}…: ${r.label}" }
                    if (batchLabels.isNotEmpty()) {
                        try {
                            bitcoinRepository.setTransactionLabel(
                                state.walletId, txid,
                                "Batch send: " + batchLabels.joinToString("; ")
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("SendVM", "Failed to save batch labels: ${e.message}")
                        }
                    }
                } else if (state.label.isNotBlank()) {
                    try {
                        bitcoinRepository.setTransactionLabel(state.walletId, txid, state.label)
                    } catch (e: Exception) {
                        android.util.Log.w("SendVM", "Failed to save transaction label: ${e.message}")
                    }
                }
                _uiState.update { it.copy(
                    isLoading = false,
                    broadcastSuccess = true,
                    broadcastTxid = txid
                ) }
                // Trigger a sync in background so HomeScreen shows updated balance
                try { bitcoinRepository.syncWallet(state.walletId, config) } catch (_: Exception) {}
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun dismissBroadcastSuccess() {
        _uiState.update { it.copy(broadcastSuccess = false, broadcastTxid = null) }
    }

    fun getMempoolUrl(): String {
        val base = settingsManager.getMempoolUrl().trimEnd('/')
        return if (settingsManager.isTestnet() && !base.contains("/testnet")) {
            "$base/testnet"
        } else base
    }

    /**
     * Store PSBT in PsbtStore before navigating to hardware wallet screen.
     * This avoids passing large base64 strings via navigation route arguments.
     */
    fun storePsbtForNavigation(walletId: String, psbtBase64: String, deviceType: String) {
        psbtStore.store(walletId, psbtBase64, deviceType)
    }

    /**
     * R7-21: Validate address is appropriate for the current network.
     * Returns null if valid, or an error message if invalid.
     * 
     * Mainnet addresses: 1..., 3..., bc1...
     * Testnet addresses: m..., n..., 2..., tb1...
     */
    private fun validateAddressForNetwork(address: String, isTestnet: Boolean): String? {
        val trimmed = address.trim()

        // Mainnet patterns
        val isMainnetP2PKH = trimmed.startsWith("1")  // Legacy P2PKH
        val isMainnetP2SH = trimmed.startsWith("3")   // P2SH
        val isMainnetBech32 = trimmed.startsWith("bc1") // Native SegWit
        
        // Testnet patterns  
        val isTestnetP2PKH = trimmed.startsWith("m") || trimmed.startsWith("n") // Testnet P2PKH
        val isTestnetP2SH = trimmed.startsWith("2") || trimmed.startsWith("3") // Testnet P2SH (2 is testnet)
        val isTestnetBech32 = trimmed.startsWith("tb1") // Testnet native SegWit
        
        val isMainnetAddress = isMainnetP2PKH || isMainnetP2SH || isMainnetBech32
        val isTestnetAddress = isTestnetP2PKH || isTestnetP2SH || isTestnetBech32
        
        return when {
            // On mainnet, got a testnet address
            !isTestnet && isTestnetAddress -> 
                "This address is for testnet, but you're on mainnet. Use a mainnet address (starts with 1, 3, or bc1)."
            
            // On testnet, got a mainnet address  
            isTestnet && isMainnetAddress ->
                "This address is for mainnet, but you're on testnet. Use a testnet address (starts with m, n, 2, or tb1)."
            
            else -> null // Valid for current network
        }
    }
}
