package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.BoundedBlockingCall
import net.clench.wallet.data.network.ElectrumConnectionFactory
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.data.repository.nativeCloseAction
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.FeeEstimates
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.domain.repository.BuiltTransactionReview
import net.clench.wallet.domain.repository.TransactionReviewOutput
import org.bitcoindevkit.Amount
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.DerivationPath
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.Wallet
import net.clench.wallet.ui.util.shouldRethrowForUiBoundary
import javax.inject.Inject

enum class SweepSeedScriptType { LEGACY, NESTED_SEGWIT, NATIVE_SEGWIT, TAPROOT }
enum class SweepWifScriptType { LEGACY, NESTED_SEGWIT, NATIVE_SEGWIT }

internal object SweepDescriptorFactory {
    fun create(
        key: DescriptorSecretKey,
        network: Network,
        type: SweepSeedScriptType,
        account: UInt,
        operationBarrier: SensitiveWalletOperationBarrier
    ): Pair<Descriptor, Descriptor> {
        require(account <= 100u) { "Account index must be from 0 to 100" }
        val purpose = when (type) {
            SweepSeedScriptType.LEGACY -> 44
            SweepSeedScriptType.NESTED_SEGWIT -> 49
            SweepSeedScriptType.NATIVE_SEGWIT -> 84
            SweepSeedScriptType.TAPROOT -> 86
        }
        val coinType = if (network == Network.BITCOIN) 0 else 1
        val accountPathText = "m/${purpose}'/${coinType}'/${account}'"
        val accountPath = DerivationPath(accountPathText)
        var accountKey: DescriptorSecretKey? = null
        var external: Descriptor? = null
        var internal: Descriptor? = null
        try {
            val derivedAccountKey = key.derive(accountPath)
            accountKey = derivedAccountKey
            val accountKeyText = derivedAccountKey.toString().removeSuffix("/*")
            fun descriptor(branch: Int): Descriptor {
                // BDK's derived secret key string already carries the master fingerprint
                // and origin path plus a wildcard. Replace that wildcard with the standard
                // external/change branch and address wildcard.
                val derivedKey = "$accountKeyText/$branch/*"
                val expression = when (type) {
                    SweepSeedScriptType.LEGACY -> "pkh($derivedKey)"
                    SweepSeedScriptType.NESTED_SEGWIT -> "sh(wpkh($derivedKey))"
                    SweepSeedScriptType.NATIVE_SEGWIT -> "wpkh($derivedKey)"
                    SweepSeedScriptType.TAPROOT -> "tr($derivedKey)"
                }
                return Descriptor(expression, network)
            }
            external = descriptor(0)
            internal = descriptor(1)
            return checkNotNull(external) to checkNotNull(internal)
        } catch (failure: Throwable) {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(internal) { it.close() },
                    nativeCloseAction(external) { it.close() }
                )
            )
            throw failure
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(accountKey) { it.destroy() },
                    nativeCloseAction(accountPath) { it.destroy() }
                )
            )
        }
    }
}

internal object SweepWifDescriptorFactory {
    private const val NUMS_PUBLIC_KEY =
        "0250929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0"

    fun create(
        wif: String,
        network: Network,
        script: SweepWifScriptType,
        operationBarrier: SensitiveWalletOperationBarrier
    ): Pair<Descriptor, Descriptor> {
        val externalExpression = when (script) {
            SweepWifScriptType.LEGACY -> "pkh($wif)"
            SweepWifScriptType.NESTED_SEGWIT -> "sh(wpkh($wif))"
            SweepWifScriptType.NATIVE_SEGWIT -> "wpkh($wif)"
        }
        val external = Descriptor(externalExpression, network)
        return try {
            // A single WIF has no change branch. BIP341's secp256k1 NUMS point has
            // no known private key and gives BDK a distinct, valid descriptor.
            external to Descriptor("wpkh($NUMS_PUBLIC_KEY)", network)
        } catch (e: Throwable) {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(nativeCloseAction(external) { it.close() })
            )
            throw e
        }
    }
}

@HiltViewModel
class SweepViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val electrumConnectionFactory: ElectrumConnectionFactory,
    private val operationBarrier: SensitiveWalletOperationBarrier
) : ViewModel() {

    private data class SweepBalanceSnapshot(
        val confirmedSat: Long,
        val pendingSat: Long
    )

    data class UiState(
        val walletName: String = "",
        val destinationAddress: String = "",
        val defaultDestinationAddress: String = "",
        val externalDestinationConfirmed: Boolean = false,
        val sourceBalanceSat: Long = 0L,
        val sourcePendingSat: Long = 0L,
        val feeEstimates: FeeEstimates? = null,
        val selectedFeeTier: FeeTier = FeeTier.STANDARD,
        val feeRate: String = "2",
        val isLoadingBalance: Boolean = false,
        val isSweeping: Boolean = false,
        val error: String? = null,
        val broadcastTxid: String? = null,
        val preparedTxHex: String? = null,
        val transactionReview: BuiltTransactionReview? = null,
        val requiresHighFeeConfirmation: Boolean = false,
        val highFeeAcknowledged: Boolean = false,
        val isBroadcasting: Boolean = false,
        val seedValidated: Boolean = false,
        val isTestnet: Boolean = false,
        val biometricForSendEnabled: Boolean = true,
        val seedScriptType: SweepSeedScriptType = SweepSeedScriptType.NATIVE_SEGWIT,
        val seedAccount: UInt = 0u,
        val wifScriptType: SweepWifScriptType = SweepWifScriptType.LEGACY
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var currentWalletId: String = ""

    fun load(walletId: String) {
        currentWalletId = walletId
        viewModelScope.launch {
            try {
                val wallets = bitcoinRepository.listWallets()
                val wallet = wallets.find { it.id == walletId }
                val address = bitcoinRepository.getReceiveAddress(walletId)
                _uiState.update {
                    it.copy(
                        walletName = wallet?.name ?: "",
                        destinationAddress = address.address,
                        defaultDestinationAddress = address.address,
                        isTestnet = settingsManager.isTestnet(),
                        biometricForSendEnabled = settingsManager.isBiometricForSendEnabled()
                    )
                }
                fetchFeeEstimates()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun setDestinationAddress(addr: String) {
        _uiState.update {
            it.copy(
                destinationAddress = addr,
                externalDestinationConfirmed = false,
                preparedTxHex = null,
                transactionReview = null,
                requiresHighFeeConfirmation = false,
                highFeeAcknowledged = false
            )
        }
    }

    fun confirmExternalDestination(confirmed: Boolean) {
        _uiState.update { it.copy(externalDestinationConfirmed = confirmed) }
    }

    fun selectFeeTier(tier: FeeTier) {
        _uiState.update { state ->
            val estimates = state.feeEstimates
            val feeRate = if (tier == FeeTier.CUSTOM) state.feeRate
            else if (estimates != null) {
                when (tier) {
                    FeeTier.ECONOMY -> estimates.economy.toInt().coerceAtLeast(1).toString()
                    FeeTier.STANDARD -> estimates.standard.toInt().coerceAtLeast(1).toString()
                    FeeTier.PRIORITY -> estimates.priority.toInt().coerceAtLeast(1).toString()
                    FeeTier.CUSTOM -> state.feeRate
                }
            } else state.feeRate
            state.copy(
                selectedFeeTier = tier,
                feeRate = feeRate,
                preparedTxHex = null,
                transactionReview = null,
                requiresHighFeeConfirmation = false,
                highFeeAcknowledged = false
            )
        }
    }

    fun setFeeRate(rate: String) {
        _uiState.update {
            it.copy(
                feeRate = rate,
                selectedFeeTier = FeeTier.CUSTOM,
                preparedTxHex = null,
                transactionReview = null,
                requiresHighFeeConfirmation = false,
                highFeeAcknowledged = false
            )
        }
    }

    fun setSeedScriptType(type: SweepSeedScriptType) {
        _uiState.update { it.copy(seedScriptType = type) }
        clearSourceValidation()
    }

    fun setSeedAccount(account: UInt) {
        require(account <= 100u) { "Account index must be from 0 to 100" }
        _uiState.update { it.copy(seedAccount = account) }
        clearSourceValidation()
    }

    fun setWifScriptType(type: SweepWifScriptType) {
        _uiState.update { it.copy(wifScriptType = type) }
        clearSourceValidation()
    }

    fun clearSourceValidation() {
        _uiState.update {
            it.copy(
                sourceBalanceSat = 0L,
                sourcePendingSat = 0L,
                seedValidated = false,
                error = null,
                broadcastTxid = null,
                preparedTxHex = null,
                transactionReview = null,
                requiresHighFeeConfirmation = false,
                highFeeAcknowledged = false
            )
        }
    }

    fun acknowledgeHighFee() {
        _uiState.update {
            if (it.requiresHighFeeConfirmation && it.preparedTxHex != null) {
                it.copy(highFeeAcknowledged = true, error = null)
            } else it
        }
    }

    fun discardPreparedSweep() {
        _uiState.update {
            it.copy(
                preparedTxHex = null,
                transactionReview = null,
                requiresHighFeeConfirmation = false,
                highFeeAcknowledged = false,
                error = null
            )
        }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    private fun fetchFeeEstimates() {
        viewModelScope.launch {
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
                        feeRate = feeRate.toInt().coerceAtLeast(1).toString()
                    )
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Validate the source seed phrase and fetch its balance.
     * Security: mnemonic CharArray zeroed in finally block per audit requirement.
     */
    fun validateSeedAndFetchBalance(mnemonicWords: CharArray, passphrase: CharArray?) {
        val ownedMnemonic = mnemonicWords.copyOf().also { mnemonicWords.fill('0') }
        val ownedPassphrase = passphrase?.copyOf().also { passphrase?.fill('0') }
        _uiState.update { it.copy(isLoadingBalance = true, error = null, seedValidated = false) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    val snapshot = withSeedWallet(
                        mnemonicWords = ownedMnemonic,
                        passphrase = ownedPassphrase,
                        script = _uiState.value.seedScriptType,
                        account = _uiState.value.seedAccount
                    ) { wallet ->
                        fullScanWallet(wallet, "Electrum seed-sweep balance scan")
                        readBalanceSnapshot(wallet)
                    }
                    currentCoroutineContext().ensureActive()
                    _uiState.update {
                        it.copy(
                            isLoadingBalance = false,
                            sourceBalanceSat = snapshot.confirmedSat,
                            sourcePendingSat = snapshot.pendingSat,
                            seedValidated = true
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update {
                    it.copy(
                        isLoadingBalance = false,
                        error = "Invalid seed or connection error: ${t.message}"
                    )
                }
            } finally {
                ownedMnemonic.fill('0')
                ownedPassphrase?.fill('0')
            }
        }
    }

    fun validateWifAndFetchBalance(wifInput: CharArray) {
        val ownedWif = wifInput.copyOf().also { wifInput.fill('0') }
        _uiState.update { it.copy(isLoadingBalance = true, error = null, seedValidated = false) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    val snapshot = operationBarrier.withLease {
                        withWifWallet(ownedWif, _uiState.value.wifScriptType) { tempWallet ->
                            syncWifWallet(tempWallet)
                            readBalanceSnapshot(tempWallet)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    _uiState.update {
                        it.copy(
                            isLoadingBalance = false,
                            sourceBalanceSat = snapshot.confirmedSat,
                            sourcePendingSat = snapshot.pendingSat,
                            seedValidated = true
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update {
                    it.copy(
                        isLoadingBalance = false,
                        error = "Invalid WIF or connection error: ${t.message}"
                    )
                }
            } finally {
                ownedWif.fill('0')
            }
        }
    }

    /**
     * Perform the sweep: send all confirmed funds from source seed to destination address.
     * Security: mnemonic CharArray zeroed in finally block per audit requirement.
     */
    fun sweep(mnemonicWords: CharArray, passphrase: CharArray?) {
        val state = _uiState.value
        if (state.sourceBalanceSat <= 0) {
            _uiState.update { it.copy(error = "No confirmed funds to sweep") }
            mnemonicWords.fill('0')
            passphrase?.fill('0')
            return
        }
        val ownedMnemonic = mnemonicWords.copyOf().also { mnemonicWords.fill('0') }
        val ownedPassphrase = passphrase?.copyOf().also { passphrase?.fill('0') }
        _uiState.update { it.copy(isSweeping = true, error = null) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    withSeedWallet(
                        mnemonicWords = ownedMnemonic,
                        passphrase = ownedPassphrase,
                        state.seedScriptType,
                        state.seedAccount
                    ) { wallet ->
                        fullScanWallet(wallet, "Electrum seed-sweep rescan")
                        buildAndPrepareSweep(wallet, state)
                    }
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update { it.copy(isSweeping = false, error = t.message ?: "Sweep failed") }
            } finally {
                ownedMnemonic.fill('0')
                ownedPassphrase?.fill('0')
            }
        }
    }

    fun sweepWif(wifInput: CharArray) {
        val state = _uiState.value
        if (state.sourceBalanceSat <= 0) {
            _uiState.update { it.copy(error = "No confirmed funds to sweep") }
            wifInput.fill('0')
            return
        }
        val ownedWif = wifInput.copyOf().also { wifInput.fill('0') }
        _uiState.update { it.copy(isSweeping = true, error = null) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    operationBarrier.withLease {
                        withWifWallet(ownedWif, state.wifScriptType) { tempWallet ->
                            syncWifWallet(tempWallet)
                            buildAndPrepareSweep(tempWallet, state)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update { it.copy(isSweeping = false, error = t.message ?: "Sweep failed") }
            } finally {
                ownedWif.fill('0')
            }
        }
    }

    fun sweepSatscardPrivateKey(privateKey: ByteArray, sourceIsTestnet: Boolean) {
        val ownedPrivateKey = privateKey.copyOf().also { privateKey.fill(0) }
        _uiState.update { it.copy(isSweeping = true, error = null) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var wifChars: CharArray? = null
            try {
                withContext(Dispatchers.IO) {
                    val walletIsTestnet = settingsManager.isTestnet()
                    if (sourceIsTestnet != walletIsTestnet) {
                        val cardNetwork = if (sourceIsTestnet) "testnet" else "mainnet"
                        val walletNetwork = if (walletIsTestnet) "testnet" else "mainnet"
                        throw Exception("SATSCARD is $cardNetwork but this wallet is $walletNetwork")
                    }
                    val network = if (sourceIsTestnet) Network.TESTNET else Network.BITCOIN
                    // BDK/WIF APIs require an immutable String internally. Keep its scope inside
                    // this leased operation and wipe the mutable source/copy; do not claim the
                    // JVM String itself can be zeroized.
                    val wif = WifPrivateKeyParser.fromRawPrivateKey(ownedPrivateKey, network, compressed = true)
                    wifChars = wif.value.toCharArray()
                    operationBarrier.withLease {
                        withWifWallet(wifChars, SweepWifScriptType.NATIVE_SEGWIT) { tempWallet ->
                            syncWifWallet(tempWallet)
                            if (readBalanceSnapshot(tempWallet).confirmedSat <= 0L) {
                                throw Exception("No confirmed funds found on the unsealed SATSCARD slot")
                            }
                            buildAndPrepareSweep(tempWallet, _uiState.value)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update { it.copy(isSweeping = false, error = t.message ?: "SATSCARD sweep failed") }
            } finally {
                ownedPrivateKey.fill(0)
                wifChars?.fill('0')
            }
        }
    }

    fun broadcastPreparedSweep() {
        val state = _uiState.value
        val txHex = state.preparedTxHex ?: run {
            _uiState.update { it.copy(error = "Prepare and review the sweep before broadcasting") }
            return
        }
        if (state.requiresHighFeeConfirmation && !state.highFeeAcknowledged) {
            _uiState.update { it.copy(error = "Confirm the high network fee before broadcasting") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBroadcasting = true, error = null) }
            try {
                val txid = bitcoinRepository.broadcastTransaction(settingsManager.loadElectrumConfig(), txHex)
                currentCoroutineContext().ensureActive()
                _uiState.update {
                    it.copy(
                        isBroadcasting = false,
                        broadcastTxid = txid,
                        preparedTxHex = null,
                        transactionReview = null,
                        requiresHighFeeConfirmation = false,
                        highFeeAcknowledged = false
                    )
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update { it.copy(isBroadcasting = false, error = t.message ?: "Sweep broadcast failed") }
            }
        }
    }

    private fun validatedDestination(state: UiState, network: Network): String {
        val raw = state.destinationAddress.trim()
        if (raw.isBlank()) throw IllegalArgumentException("Destination address is empty")
        val address = org.bitcoindevkit.Address(raw, network)
        try {
            check(address.isValidForNetwork(network)) { "Destination address is for the wrong Bitcoin network" }
            val normalized = address.toString()
            if (normalized != state.defaultDestinationAddress && !state.externalDestinationConfirmed) {
                throw SecurityException(
                    "This destination is not the receive address Clench generated for the selected wallet. Confirm the external destination before preparing the sweep."
                )
            }
            return normalized
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(nativeCloseAction(address) { it.close() })
            )
        }
    }

    private fun validatedFeeRate(raw: String): FeeRate {
        val value = raw.toDoubleOrNull()
        require(value != null && value.isFinite() && value in 1.0..SendViewModel.MAX_FEE_RATE_SAT_VB.toDouble()) {
            "Enter a fee rate from 1 to ${SendViewModel.MAX_FEE_RATE_SAT_VB.toInt()} sat/vB"
        }
        return FeeRate.fromSatPerVb(kotlin.math.ceil(value).toLong().toULong())
    }

    /** Construct every immutable TxBuilder stage explicitly so each native Arc is closed. */
    private suspend fun buildAndPrepareSweep(sourceWallet: Wallet, draft: UiState) {
        val destinationAddress = validatedDestination(draft, sourceWallet.network())
        var address: org.bitcoindevkit.Address? = null
        var script: org.bitcoindevkit.Script? = null
        var feeRate: FeeRate? = null
        var initialBuilder: TxBuilder? = null
        var drainBuilder: TxBuilder? = null
        var destinationBuilder: TxBuilder? = null
        var feeBuilder: TxBuilder? = null
        var psbt: org.bitcoindevkit.Psbt? = null
        try {
            address = org.bitcoindevkit.Address(destinationAddress, sourceWallet.network())
            script = address.scriptPubkey()
            feeRate = validatedFeeRate(draft.feeRate)
            initialBuilder = TxBuilder()
            drainBuilder = initialBuilder.drainWallet()
            destinationBuilder = drainBuilder.drainTo(script)
            feeBuilder = destinationBuilder.feeRate(feeRate)
            psbt = feeBuilder.finish(sourceWallet)

            check(sourceWallet.sign(psbt)) { "Transaction signing incomplete" }
            prepareSweepTransaction(sourceWallet, psbt.extractTx(), draft, destinationAddress)
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(psbt) { it.close() },
                    nativeCloseAction(feeBuilder) { it.close() },
                    nativeCloseAction(destinationBuilder) { it.close() },
                    nativeCloseAction(drainBuilder) { it.close() },
                    nativeCloseAction(initialBuilder) { it.close() },
                    nativeCloseAction(feeRate) { it.close() },
                    nativeCloseAction(script) { it.close() },
                    nativeCloseAction(address) { it.close() }
                )
            )
        }
    }

    private suspend fun prepareSweepTransaction(
        sourceWallet: Wallet,
        tx: Transaction,
        draft: UiState,
        destinationAddress: String
    ) {
        var outputs: List<org.bitcoindevkit.TxOut> = emptyList()
        var inputs: List<org.bitcoindevkit.TxIn> = emptyList()
        var expectedAddress: org.bitcoindevkit.Address? = null
        var expectedScript: org.bitcoindevkit.Script? = null
        var feeAmount: Amount? = null
        var txid: org.bitcoindevkit.Txid? = null
        try {
            outputs = tx.output()
            check(outputs.size == 1) { "Sweep transaction contains an unexpected output" }
            expectedAddress = org.bitcoindevkit.Address(destinationAddress, sourceWallet.network())
            expectedScript = expectedAddress.scriptPubkey()
            check(outputs.single().scriptPubkey.toBytes().contentEquals(expectedScript.toBytes())) {
                "Sweep transaction destination differs from the reviewed address"
            }
            feeAmount = sourceWallet.calculateFee(tx)
            val feeSat = feeAmount.toSat().toLong()
            val vsize = tx.vsize().toLong()
            txid = tx.computeTxid()
            inputs = tx.input()
            val review = BuiltTransactionReview(
                txid = txid.toString(),
                feeSat = feeSat,
                vsize = vsize,
                feeRateSatPerVbyte = if (vsize > 0) feeSat.toDouble() / vsize else 0.0,
                inputs = inputs.map { "${it.previousOutput.txid}:${it.previousOutput.vout}" },
                outputs = listOf(
                    TransactionReviewOutput(
                        index = 0,
                        amountSat = outputs.single().value.toSat().toLong(),
                        address = destinationAddress,
                        belongsToWallet = false
                    )
                )
            )
            SendViewModel.feeSafetyError(review)?.let { error(it) }
            val txHex = tx.serialize().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            currentCoroutineContext().ensureActive()
            _uiState.update { current ->
                if (current.destinationAddress.trim() != draft.destinationAddress.trim() ||
                    current.feeRate != draft.feeRate
                ) {
                    current.copy(
                        isSweeping = false,
                        error = "Sweep details changed while the transaction was prepared. Review the draft and try again."
                    )
                } else {
                    current.copy(
                        isSweeping = false,
                        preparedTxHex = txHex,
                        transactionReview = review,
                        requiresHighFeeConfirmation = SendViewModel.requiresHighFeeConfirmation(review),
                        highFeeAcknowledged = false,
                        error = null
                    )
                }
            }
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                buildList {
                    inputs.forEach { add(checkNotNull(nativeCloseAction(it) { value -> value.destroy() })) }
                    outputs.forEach { add(checkNotNull(nativeCloseAction(it) { value -> value.destroy() })) }
                    addAll(
                        listOfNotNull(
                            nativeCloseAction(txid) { it.close() },
                            nativeCloseAction(feeAmount) { it.close() },
                            nativeCloseAction(expectedScript) { it.close() },
                            nativeCloseAction(expectedAddress) { it.close() },
                            nativeCloseAction(tx) { it.close() }
                        )
                    )
                }
            )
        }
    }

    private suspend fun <T> withSeedWallet(
        mnemonicWords: CharArray,
        passphrase: CharArray?,
        script: SweepSeedScriptType,
        account: UInt,
        block: suspend (Wallet) -> T
    ): T = operationBarrier.withLease {
        val network = if (settingsManager.isTestnet()) Network.TESTNET else Network.BITCOIN
        var mnemonic: Mnemonic? = null
        var secretKey: DescriptorSecretKey? = null
        var externalDesc: Descriptor? = null
        var internalDesc: Descriptor? = null
        var tempPersister: Persister? = null
        var tempWallet: Wallet? = null
        try {
            // BDK constructors require immutable strings. Keep them expression-local; mutable
            // caller copies are wiped immediately at API entry and again when this lease ends.
            mnemonic = Mnemonic.fromString(String(mnemonicWords))
            secretKey = DescriptorSecretKey(network, mnemonic, passphrase?.let(::String).orEmpty())
            val descriptors = SweepDescriptorFactory.create(
                secretKey,
                network,
                script,
                account,
                operationBarrier
            )
            externalDesc = descriptors.first
            internalDesc = descriptors.second
            tempPersister = Persister.newInMemory()
            tempWallet = Wallet(externalDesc, internalDesc, network, tempPersister)
            block(tempWallet)
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(tempWallet) { it.close() },
                    nativeCloseAction(tempPersister) { it.close() },
                    nativeCloseAction(externalDesc) { it.close() },
                    nativeCloseAction(internalDesc) { it.close() },
                    nativeCloseAction(secretKey) { it.destroy() },
                    nativeCloseAction(mnemonic) { it.destroy() }
                )
            )
        }
    }

    private suspend fun <T> withWifWallet(
        wifInput: CharArray,
        script: SweepWifScriptType,
        block: suspend (Wallet) -> T
    ): T {
        val network = if (settingsManager.isTestnet()) Network.TESTNET else Network.BITCOIN
        val wif = WifPrivateKeyParser.extract(wifInput, network)
        var externalDesc: Descriptor? = null
        var internalDesc: Descriptor? = null
        var tempPersister: Persister? = null
        var tempWallet: Wallet? = null
        try {
            val descriptors = SweepWifDescriptorFactory.create(
                wif.value,
                network,
                script,
                operationBarrier
            )
            externalDesc = descriptors.first
            internalDesc = descriptors.second
            tempPersister = Persister.newInMemory()
            tempWallet = Wallet(externalDesc, internalDesc, network, tempPersister)
            return block(tempWallet)
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(tempWallet) { it.close() },
                    nativeCloseAction(tempPersister) { it.close() },
                    nativeCloseAction(externalDesc) { it.close() },
                    nativeCloseAction(internalDesc) { it.close() }
                )
            )
        }
    }

    private fun fullScanWallet(wallet: Wallet, operation: String) {
        var builder: org.bitcoindevkit.FullScanRequestBuilder? = null
        var request: org.bitcoindevkit.FullScanRequest? = null
        var update: org.bitcoindevkit.Update? = null
        try {
            builder = wallet.startFullScan()
            request = builder.build()
            val ownedRequest = request
            update = withBoundedElectrum(settingsManager.loadElectrumConfig(), operation) {
                it.client.fullScan(
                    ownedRequest,
                    stopGap = 20uL,
                    batchSize = 10uL,
                    fetchPrevTxouts = false
                )
            }
            wallet.applyUpdate(update)
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(update) { it.close() },
                    nativeCloseAction(request) { it.close() },
                    nativeCloseAction(builder) { it.close() }
                )
            )
        }
    }

    private fun readBalanceSnapshot(wallet: Wallet): SweepBalanceSnapshot {
        val balance = wallet.balance()
        try {
            return SweepBalanceSnapshot(
                confirmedSat = balance.confirmed.toSat().toLong(),
                pendingSat = (
                    balance.trustedPending.toSat() + balance.untrustedPending.toSat()
                    ).toLong()
            )
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(nativeCloseAction(balance) { it.destroy() })
            )
        }
    }

    /**
     * Execute sweep networking behind a hard deadline. Coroutine cancellation
     * cannot pre-empt BDK's blocking native Electrum calls, so the connection and
     * operation both run on a disposable executor whose transport is closed
     * before a timed-out Future is cancelled.
     */
    private fun <T> withBoundedElectrum(
        config: ElectrumConfig,
        operation: String,
        block: (net.clench.wallet.data.network.ActiveElectrumConnection) -> T
    ): T {
        val timeoutMs = if (config.isCustom) 60_000L else 30_000L
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var activeConnection: net.clench.wallet.data.network.ActiveElectrumConnection? = null
        try {
            activeConnection = BoundedBlockingCall.awaitResource(
                executor = executor,
                timeoutMs = timeoutMs,
                operation = "$operation connection",
                create = { electrumConnectionFactory.createConnection(config) },
                close = { it.close() },
                onCloseFailure = operationBarrier::quarantineNativeResource
            )
            val connection = activeConnection
            val operationFuture = executor.submit(java.util.concurrent.Callable {
                block(connection)
            })
            return BoundedBlockingCall.await(
                future = operationFuture,
                timeoutMs = timeoutMs,
                operation = operation,
                onTimeout = { connection.cancelTransport() }
            )
        } finally {
            activeConnection?.cancelTransport()
            BoundedBlockingCall.shutdownAndAwaitTermination(
                executor = executor,
                operation = "$operation worker",
                onTerminationStalled = operationBarrier::markFailedRestartRequiredFromOperation
            )
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(nativeCloseAction(activeConnection) { it.close() })
            )
        }
    }

    private fun syncWifWallet(wallet: Wallet) {
        // A WIF controls one fixed, non-wildcard script. Reveal that script and use
        // BDK's bounded sync request; gap-based full scans are intended for ranged
        // descriptors rather than this one fixed script.
        val addressInfo = wallet.revealNextAddress(org.bitcoindevkit.KeychainKind.EXTERNAL)
        var builder: org.bitcoindevkit.SyncRequestBuilder? = null
        var request: org.bitcoindevkit.SyncRequest? = null
        var update: org.bitcoindevkit.Update? = null
        try {
            builder = wallet.startSyncWithRevealedSpks()
            request = builder.build()
            val ownedRequest = request
            update = withBoundedElectrum(
                settingsManager.loadElectrumConfig(),
                "Electrum WIF-sweep sync"
            ) { activeConn ->
                activeConn.client.sync(
                    ownedRequest,
                    batchSize = 1uL,
                    fetchPrevTxouts = false
                )
            }
            wallet.applyUpdate(update)
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(update) { it.close() },
                    nativeCloseAction(request) { it.close() },
                    nativeCloseAction(builder) { it.close() },
                    nativeCloseAction(addressInfo) { it.destroy() }
                )
            )
        }
    }

}
