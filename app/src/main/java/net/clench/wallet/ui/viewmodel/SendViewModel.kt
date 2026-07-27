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
import net.clench.wallet.data.local.dao.AddressBookDao
import net.clench.wallet.data.local.entity.AddressBookEntryEntity
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.domain.model.AddressVerificationResult
import net.clench.wallet.domain.model.BitcoinAddressVerifier
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.FeeEstimates
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.domain.repository.BuiltTransactionReview
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject

enum class FeeTier { ECONOMY, STANDARD, PRIORITY, CUSTOM }
enum class AmountUnit { SATS, BTC, USD }

data class RecipientEntry(
    val address: String = "",
    val amountSat: String = "",
    val label: String = ""
)

data class SavedPayee(
    val key: String,
    val label: String,
    val address: String
)

@HiltViewModel
class SendViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val addressBookDao: AddressBookDao,
    private val psbtStore: PsbtStore,
    private val torAwareHttpClient: TorAwareHttpClient
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val isWatchOnly: Boolean = false,
        val hasPhoneSigner: Boolean = false,
        val preferredHardwareWallet: String? = null,
        val toAddress: String = "",
        val amountSat: String = "",
        val amountDisplay: String = "", // display value in selected unit
        val amountUnit: AmountUnit = AmountUnit.SATS,
        val feeRate: String = "2",
        val sendMax: Boolean = false,
        val txHex: String? = null,
        val transactionReview: BuiltTransactionReview? = null,
        val proposalFingerprint: String? = null,
        val requiresHighFeeConfirmation: Boolean = false,
        val highFeeAcknowledged: Boolean = false,
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
        val recipients: List<RecipientEntry> = listOf(RecipientEntry()),
        val addressVerification: AddressVerificationResult? = null,
        val addressWarning: String? = null,
        val savedPayees: List<SavedPayee> = emptyList(),
        val savePayeeAfterSend: Boolean = false,
        val cpFpMode: Boolean = false
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

    private inline fun updateDraft(crossinline transform: (UiState) -> UiState) {
        _uiState.update { state ->
            transform(state).copy(
                txHex = null,
                transactionReview = null,
                proposalFingerprint = null,
                requiresHighFeeConfirmation = false,
                highFeeAcknowledged = false
            )
        }
    }

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
                            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "resolveSelectedUtxoAmounts failed: ${e.message}")
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
                            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "resolveUtxoAmount failed: ${e.message}")
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
                            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("SendVM", "Subtracting $frozenAmount frozen sats (${frozenUtxos.size} UTXOs) from available balance")
                        }
                        (balance.spendableSat - frozenAmount).coerceAtLeast(0L)
                    } catch (e: Exception) {
                        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "Could not compute frozen balance: ${e.message}")
                        balance.spendableSat
                    }
                }

                _uiState.update { it.copy(
                    isWatchOnly = wallet?.isWatchOnly ?: false,
                    hasPhoneSigner = if (wallet?.isMultisig == true) {
                        bitcoinRepository.hasMultisigPhoneSigner(walletId)
                    } else false,
                    preferredHardwareWallet = wallet?.preferredHardwareWallet,
                    availableBalanceSat = effectiveBalance,
                    frozenUtxoCount = frozenCount,
                    frozenAmountSat = frozenSats
                ) }
            } catch (e: Exception) { /* show 0 */ }
        }
        loadSavedPayees(walletId)
        fetchFeeEstimates()
        fetchBtcPrice()
    }

    private fun loadSavedPayees(walletId: String) {
        viewModelScope.launch {
            try {
                val payees = withContext(Dispatchers.IO) {
                    addressBookDao.getForWallet(walletId).map {
                        SavedPayee(key = it.key, label = it.label, address = it.address)
                    }
                }
                _uiState.update { it.copy(savedPayees = payees) }
            } catch (e: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "loadSavedPayees failed: ${e.message}")
            }
        }
    }

    fun selectPayee(payee: SavedPayee) {
        setAddress(payee.address)
        if (_uiState.value.label.isBlank()) {
            _uiState.update { it.copy(label = payee.label) }
        }
    }

    fun setSavePayeeAfterSend(enabled: Boolean) {
        _uiState.update { it.copy(savePayeeAfterSend = enabled) }
    }

    fun saveCurrentPayee(label: String? = null) {
        val state = _uiState.value
        val address = state.toAddress.trim()
        if (address.isBlank()) {
            _uiState.update { it.copy(error = "Enter an address before saving a payee") }
            return
        }
        val verification = runCatching {
            BitcoinAddressVerifier.verify(address, settingsManager.isTestnet())
        }.getOrElse { e ->
            _uiState.update { it.copy(error = e.message ?: "Address is not valid for this wallet") }
            return
        }
        val resolvedLabel = (label ?: state.label).trim().ifBlank {
            verification.normalizedAddress.take(8) + "..." + verification.normalizedAddress.takeLast(6)
        }.take(80)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val existing = addressBookDao.getByAddress(state.walletId, verification.normalizedAddress)
                    val now = System.currentTimeMillis()
                    addressBookDao.upsert(
                        AddressBookEntryEntity(
                            key = existing?.key ?: addressBookKey(state.walletId, verification.normalizedAddress),
                            walletId = state.walletId,
                            label = resolvedLabel,
                            address = verification.normalizedAddress,
                            lastUsedAt = now,
                            createdAt = existing?.createdAt ?: now
                        )
                    )
                }
                loadSavedPayees(state.walletId)
                _uiState.update { it.copy(error = null, savePayeeAfterSend = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Could not save payee: ${e.message}") }
            }
        }
    }

    fun deletePayee(payee: SavedPayee) {
        val walletId = _uiState.value.walletId
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { addressBookDao.delete(payee.key) }
                loadSavedPayees(walletId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Could not delete payee: ${e.message}") }
            }
        }
    }

    fun prepareCpfpSend(walletId: String, outpoints: List<String>) {
        if (outpoints.isEmpty()) return
        viewModelScope.launch {
            try {
                val selfAddress = bitcoinRepository.getLastAddress(walletId).address
                val fee = _uiState.value.feeEstimates?.priority?.toInt()?.coerceAtLeast(2)?.toString()
                    ?: _uiState.value.feeRate.toIntOrNull()?.coerceAtLeast(2)?.toString()
                    ?: "10"
                updateDraft {
                    it.copy(
                        selectedUtxoOutpoints = outpoints,
                        toAddress = selfAddress,
                        sendMax = true,
                        amountSat = if (it.availableBalanceSat > 0) it.availableBalanceSat.toString() else it.amountSat,
                        amountDisplay = if (it.availableBalanceSat > 0) it.availableBalanceSat.toString() else it.amountDisplay,
                        amountUnit = AmountUnit.SATS,
                        selectedFeeTier = FeeTier.PRIORITY,
                        feeRate = fee,
                        cpFpMode = true,
                        addressVerification = runCatching {
                            BitcoinAddressVerifier.verify(selfAddress, settingsManager.isTestnet())
                        }.getOrNull(),
                        error = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Could not prepare CPFP transaction: ${e.message}") }
            }
        }
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
                        feeRate = if (state.txHex == null) {
                            feeRate.toInt().coerceAtLeast(1).toString()
                        } else {
                            state.feeRate
                        }
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
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("SendVM", "BTC price fetch disabled by user setting")
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
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("SendVM", "BTC price: $$price")
                _uiState.update { it.copy(btcPriceUsd = price) }
            } catch (e: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "BTC price fetch failed: ${e.message}")
            }
        }
    }

    private fun fetchPriceFromCoinbase(): Double? {
        return try {
            val json = torAwareHttpClient.fetchText("https://api.coinbase.com/v2/prices/BTC-USD/spot")
            JSONObject(json).getJSONObject("data").getDouble("amount")
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "Coinbase price failed: ${e.message}")
            null
        }
    }

    private fun fetchPriceFromMempoolSpace(): Double? {
        return try {
            val baseUrl = settingsManager.getMempoolUrl().trim().trimEnd('/')
            val json = torAwareHttpClient.fetchText("$baseUrl/api/v1/prices")
            JSONObject(json).getDouble("USD")
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "mempool API price failed: ${e.message}")
            null
        }
    }

    private fun fetchPriceFromCoinGecko(): Double? {
        return try {
            val json = torAwareHttpClient.fetchText("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd")
            JSONObject(json).getJSONObject("bitcoin").getDouble("usd")
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "CoinGecko price failed: ${e.message}")
            null
        }
    }

    fun selectFeeTier(tier: FeeTier) {
        updateDraft { state ->
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
        updateDraft { it.copy(utxoTxid = txid, utxoVout = vout) }
    }

    fun setSelectedUtxos(outpoints: List<String>) {
        updateDraft { it.copy(selectedUtxoOutpoints = outpoints) }
    }

    fun setAddress(addr: String) {
        // Parse BIP-21 URI: bitcoin:address?amount=X&label=...
        val parsed = runCatching { BitcoinAddressVerifier.parseBip21(addr) }.getOrElse { e ->
            _uiState.update { it.copy(error = e.message ?: "Could not parse Bitcoin payment URI") }
            return
        }
        val verification = runCatching {
            if (parsed.address.isNotBlank()) BitcoinAddressVerifier.verify(parsed.address, settingsManager.isTestnet()) else null
        }.getOrNull()
        updateDraft {
            it.copy(
                toAddress = verification?.normalizedAddress ?: parsed.address,
                addressVerification = verification,
                addressWarning = parsed.warning,
                label = parsed.label ?: it.label,
                error = null
            )
        }
        // If BIP-21 included an amount, set it
        parsed.amountSat?.let { sats ->
            if (sats > 0) setAmount(sats.toString())
        }
    }

    fun setError(msg: String) = _uiState.update { it.copy(error = msg) }
    fun setAmount(amt: String) = updateDraft { it.copy(amountSat = amt) }

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
        updateDraft { it.copy(amountDisplay = input, error = null) }
        // Convert display value to sats for internal use
        val sats = when (state.amountUnit) {
            AmountUnit.SATS -> input.toLongOrNull()
            AmountUnit.BTC -> input.toBigDecimalOrNull()?.let { btc ->
                runCatching {
                    val normalized = btc.stripTrailingZeros()
                    require(normalized.signum() >= 0 && normalized.scale() <= 8)
                    normalized.movePointRight(8).longValueExact()
                }.getOrNull()
            }
            AmountUnit.USD -> input.toDoubleOrNull()?.let { usd ->
                state.btcPriceUsd?.let { price -> (usd / price * 100_000_000).toLong() }
            }
        }
        updateDraft { it.copy(amountSat = sats?.toString() ?: input) }
    }
    fun setFeeRate(rate: String) = updateDraft { it.copy(feeRate = rate, selectedFeeTier = FeeTier.CUSTOM) }
    fun setSendMax(max: Boolean) {
        if (max) {
            // Show the available balance as the amount (fees will reduce it at build time)
            val balance = _uiState.value.availableBalanceSat
            updateDraft { it.copy(
                sendMax = true,
                amountSat = if (balance > 0) balance.toString() else "",
                amountDisplay = if (balance > 0) balance.toString() else "",
                amountUnit = AmountUnit.SATS
            ) }
        } else {
            updateDraft { it.copy(sendMax = false, amountSat = "", amountDisplay = "") }
        }
    }
    fun setLabel(label: String) = _uiState.update { it.copy(label = label) }

    // --- Batch recipient management ---

    fun addRecipient() {
        updateDraft { it.copy(recipients = it.recipients + RecipientEntry()) }
    }

    fun removeRecipient(index: Int) {
        updateDraft { state ->
            if (state.recipients.size <= 1) state
            else state.copy(recipients = state.recipients.filterIndexed { i, _ -> i != index })
        }
    }

    fun updateRecipientAddress(index: Int, address: String) {
        updateDraft { state ->
            state.copy(recipients = state.recipients.mapIndexed { i, r ->
                if (i == index) r.copy(address = address) else r
            }, error = null)
        }
    }

    fun updateRecipientAmount(index: Int, amount: String) {
        updateDraft { state ->
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
        if (feeRate == null || !feeRate.isFinite() || feeRate < 1f || feeRate > MAX_FEE_RATE_SAT_VB) {
            _uiState.update { it.copy(error = "Enter a fee rate from 1 to ${MAX_FEE_RATE_SAT_VB.toInt()} sat/vB") }
            return
        }

        val draftFingerprint = proposalFingerprint(state)

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
                val review = bitcoinRepository.inspectBuiltTransaction(state.walletId, txHex)
                validateReviewMatchesDraft(state, review)
                feeSafetyError(review)?.let { error(it) }
                _uiState.update { current ->
                    if (proposalFingerprint(current) != draftFingerprint) {
                        current.copy(
                            isLoading = false,
                            error = "Transaction details changed while it was being built. Review the updated draft and try again."
                        )
                    } else {
                        current.copy(
                            txHex = txHex,
                            transactionReview = review,
                            proposalFingerprint = draftFingerprint,
                            requiresHighFeeConfirmation = requiresHighFeeConfirmation(review),
                            highFeeAcknowledged = false,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Validate fields needed before hardware wallet PSBT signing.
     * This lets the watch-only UI show input errors before opening the signer sheet.
     */
    fun validatePsbtInputs(): Boolean {
        val state = _uiState.value
        val isBatch = state.recipients.size > 1

        if (isBatch) {
            if (state.sendMax) {
                _uiState.update { it.copy(error = "Send max is not available with multiple recipients") }
                return false
            }
            for ((idx, r) in state.recipients.withIndex()) {
                if (r.address.isBlank()) {
                    _uiState.update { it.copy(error = "Recipient ${idx + 1}: Please enter an address") }
                    return false
                }
                val addrErr = validateAddressForNetwork(r.address, settingsManager.isTestnet())
                if (addrErr != null) {
                    _uiState.update { it.copy(error = "Recipient ${idx + 1}: $addrErr") }
                    return false
                }
                val amt = r.amountSat.toLongOrNull()
                if (amt == null || amt <= 0) {
                    _uiState.update { it.copy(error = "Recipient ${idx + 1}: Please enter a valid amount") }
                    return false
                }
            }
        } else {
            if (!state.sendMax) {
                val amount = state.amountSat.toLongOrNull()
                if (amount == null || amount <= 0) {
                    _uiState.update { it.copy(error = "Please enter a valid amount in satoshis") }
                    return false
                }
            }

            if (state.toAddress.isBlank()) {
                _uiState.update { it.copy(error = "Please enter a recipient address") }
                return false
            }

            val addressValidation = validateAddressForNetwork(state.toAddress, settingsManager.isTestnet())
            if (addressValidation != null) {
                _uiState.update { it.copy(error = addressValidation) }
                return false
            }
        }

        val feeRate = state.feeRate.toFloatOrNull()
        if (feeRate == null || !feeRate.isFinite() || feeRate < 1f || feeRate > MAX_FEE_RATE_SAT_VB) {
            _uiState.update { it.copy(error = "Enter a fee rate from 1 to ${MAX_FEE_RATE_SAT_VB.toInt()} sat/vB") }
            return false
        }

        _uiState.update { it.copy(error = null) }
        return true
    }

    /**
     * Create an unsigned PSBT for hardware wallet signing.
     * Validates inputs the same way as buildTx, but produces a PSBT instead of signing.
     */
    fun createPsbt(onPsbtReady: (psbtBase64: String) -> Unit) {
        if (!validatePsbtInputs()) return

        val state = _uiState.value
        val isBatch = state.recipients.size > 1
        val feeRate = state.feeRate.toFloatOrNull() ?: return

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
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "createPsbt failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Could not create the unsigned transaction. Check the recipient, amount, fee rate, and selected UTXO, then try again."
                    )
                }
            }
        }
    }

    fun broadcast(onSuccess: (walletId: String) -> Unit) {
        val state = _uiState.value
        val txHex = state.txHex ?: return
        if (state.proposalFingerprint == null || proposalFingerprint(state) != state.proposalFingerprint) {
            updateDraft { it.copy(error = "Transaction details changed. Build and review the transaction again before broadcasting.") }
            return
        }
        if (state.requiresHighFeeConfirmation && !state.highFeeAcknowledged) {
            _uiState.update { it.copy(error = "Confirm that you understand the unusually high fee before broadcasting") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val config = settingsManager.loadElectrumConfig()
                val txid = bitcoinRepository.broadcastTransaction(config, txHex)
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("SendVM", "Broadcast success: txid=$txid")
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
                            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "Failed to save batch labels: ${e.message}")
                        }
                    }
                } else if (state.label.isNotBlank()) {
                    try {
                        bitcoinRepository.setTransactionLabel(state.walletId, txid, state.label)
                    } catch (e: Exception) {
                        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("SendVM", "Failed to save transaction label: ${e.message}")
                    }
                }
                if (!isBatch && state.savePayeeAfterSend && state.toAddress.isNotBlank()) {
                    savePayeeAfterBroadcast(state)
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

    fun acknowledgeHighFee() {
        _uiState.update { state ->
            if (state.requiresHighFeeConfirmation && state.txHex != null) {
                state.copy(highFeeAcknowledged = true, error = null)
            } else state
        }
    }

    fun discardBuiltTransaction() {
        updateDraft { it.copy(error = null) }
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
        return runCatching {
            val verification = BitcoinAddressVerifier.verify(address, isTestnet)
            _uiState.update { it.copy(addressVerification = verification) }
            null
        }.getOrElse { e -> e.message ?: "Address is not valid for this wallet network" }
    }

    private suspend fun savePayeeAfterBroadcast(state: UiState) {
        val verification = runCatching {
            BitcoinAddressVerifier.verify(state.toAddress, settingsManager.isTestnet())
        }.getOrNull() ?: return
        val label = state.label.trim().ifBlank {
            verification.normalizedAddress.take(8) + "..." + verification.normalizedAddress.takeLast(6)
        }.take(80)
        withContext(Dispatchers.IO) {
            val existing = addressBookDao.getByAddress(state.walletId, verification.normalizedAddress)
            val now = System.currentTimeMillis()
            addressBookDao.upsert(
                AddressBookEntryEntity(
                    key = existing?.key ?: addressBookKey(state.walletId, verification.normalizedAddress),
                    walletId = state.walletId,
                    label = label,
                    address = verification.normalizedAddress,
                    lastUsedAt = now,
                    createdAt = existing?.createdAt ?: now
                )
            )
        }
        loadSavedPayees(state.walletId)
    }

    private fun addressBookKey(walletId: String, address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$walletId:$address".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$walletId:$digest"
    }

    private fun proposalFingerprint(state: UiState): String {
        val payload = buildString {
            append(state.walletId).append('|')
            append(state.toAddress.trim()).append('|')
            append(state.amountSat).append('|')
            append(state.feeRate).append('|')
            append(state.sendMax).append('|')
            append(state.utxoTxid).append(':').append(state.utxoVout).append('|')
            append(state.selectedUtxoOutpoints.sorted().joinToString(",")).append('|')
            state.recipients.forEach {
                append(it.address.trim()).append(':').append(it.amountSat).append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun validateReviewMatchesDraft(state: UiState, review: BuiltTransactionReview) {
        val intended = if (state.recipients.size > 1) {
            state.recipients.groupBy { it.address.trim() }
                .mapValues { (_, entries) -> entries.sumOf { it.amountSat.toLong() } }
        } else {
            mapOf(state.toAddress.trim() to if (state.sendMax) null else state.amountSat.toLong())
        }

        intended.forEach { (address, expectedAmount) ->
            val actualAmount = review.outputs.filter { it.address == address }.sumOf { it.amountSat }
            if (expectedAmount == null) {
                check(actualAmount > 0) { "Built transaction does not pay the reviewed destination" }
            } else {
                check(actualAmount == expectedAmount) {
                    "Built transaction output differs from the reviewed amount for $address"
                }
            }
        }

        val unexpectedExternal = review.outputs.filter { output ->
            !output.belongsToWallet && output.address !in intended.keys
        }
        check(unexpectedExternal.isEmpty()) {
            "Built transaction contains an unexpected external output"
        }
    }

    companion object {
        const val MAX_FEE_RATE_SAT_VB = 1_000f
        const val MAX_ABSOLUTE_FEE_SAT = 1_000_000L
        const val HIGH_FEE_WARNING_PERCENT = 5.0
        const val MAX_RELATIVE_FEE_PERCENT = 50.0
        private const val MAX_BITCOIN_SUPPLY_SAT = 2_100_000_000_000_000L

        fun requiresHighFeeConfirmation(review: BuiltTransactionReview): Boolean {
            if (review.feeSat < 0 || review.outputs.any { it.amountSat < 0 }) return false
            val reviewedAmount = feeComparisonAmount(review)
            if (reviewedAmount <= 0) return false
            return review.feeSat.toDouble() / reviewedAmount.toDouble() * 100.0 >
                HIGH_FEE_WARNING_PERCENT
        }

        fun feeSafetyError(review: BuiltTransactionReview): String? {
            if (review.feeSat < 0 || review.vsize <= 0 || !review.feeRateSatPerVbyte.isFinite() ||
                review.feeRateSatPerVbyte < 0 ||
                review.outputs.any { it.amountSat !in 0..MAX_BITCOIN_SUPPLY_SAT } ||
                review.totalOutputAmountSat > MAX_BITCOIN_SUPPLY_SAT
            ) {
                return "Transaction contains invalid fee or amount metadata"
            }
            if (review.feeSat > MAX_ABSOLUTE_FEE_SAT) {
                return "Transaction fee is ${review.feeSat} sats, above Clench's ${MAX_ABSOLUTE_FEE_SAT}-sat safety limit"
            }
            val reviewedAmount = feeComparisonAmount(review)
            if (reviewedAmount <= 0) return null
            val percent = review.feeSat.toDouble() / reviewedAmount.toDouble() * 100.0
            return if (percent > MAX_RELATIVE_FEE_PERCENT) {
                "Transaction fee is ${String.format("%.1f", percent)}% of the amount, above Clench's safety limit"
            } else null
        }

        private fun feeComparisonAmount(review: BuiltTransactionReview): Long =
            review.externalAmountSat.takeIf { it > 0 }
                ?: review.totalOutputAmountSat
    }
}
