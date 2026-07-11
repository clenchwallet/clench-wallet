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
import net.clench.wallet.data.network.ElectrumConnectionFactory
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
import javax.inject.Inject

enum class SweepSeedScriptType { LEGACY, NESTED_SEGWIT, NATIVE_SEGWIT, TAPROOT }
enum class SweepWifScriptType { LEGACY, NESTED_SEGWIT, NATIVE_SEGWIT }

internal object SweepDescriptorFactory {
    fun create(
        key: DescriptorSecretKey,
        network: Network,
        type: SweepSeedScriptType,
        account: UInt
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
            return descriptor(0) to descriptor(1)
        } finally {
            try { accountKey?.destroy() } catch (_: Exception) {}
            try { accountPath.destroy() } catch (_: Exception) {}
        }
    }
}

internal object SweepWifDescriptorFactory {
    private const val NUMS_PUBLIC_KEY =
        "0250929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0"

    fun create(
        wif: String,
        network: Network,
        script: SweepWifScriptType
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
        } catch (e: Exception) {
            external.destroy()
            throw e
        }
    }
}

@HiltViewModel
class SweepViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val electrumConnectionFactory: ElectrumConnectionFactory
) : ViewModel() {

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
        _uiState.update { it.copy(isLoadingBalance = true, error = null, seedValidated = false) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var mnemonic: Mnemonic? = null
                var secretKey: DescriptorSecretKey? = null
                try {
                    val network = if (settingsManager.isTestnet()) Network.TESTNET else Network.BITCOIN
                    val mnemonicStr = String(mnemonicWords)
                    mnemonic = Mnemonic.fromString(mnemonicStr)
                    val passphraseStr = if (passphrase != null) String(passphrase) else ""
                    secretKey = DescriptorSecretKey(network, mnemonic, passphraseStr)

                    val sweepState = _uiState.value
                    val (externalDesc, internalDesc) = SweepDescriptorFactory.create(
                        secretKey,
                        network,
                        sweepState.seedScriptType,
                        sweepState.seedAccount
                    )

                    // Create temp wallet to sync and get balance — use cacheDir (C-1)
                    val tempDbPath = java.io.File.createTempFile("clench_sweep_", ".db", appContext.cacheDir).absolutePath
                    val tempPersister = Persister.newSqlite(tempDbPath)
                    val tempWallet = Wallet(externalDesc, internalDesc, network, tempPersister)

                    try {
                        // Sync the temp wallet
                        val config = settingsManager.loadElectrumConfig()
                        val activeConn = electrumConnectionFactory.createConnection(config)
                        try {
                            val fullScanResult = tempWallet.startFullScan().build()
                            val update = activeConn.client.fullScan(fullScanResult, stopGap = 20uL, batchSize = 10uL, fetchPrevTxouts = false)
                            tempWallet.applyUpdate(update)
                        } finally {
                            activeConn.close()
                        }

                        val balance = tempWallet.balance()
                        val confirmed = balance.confirmed.toSat().toLong()
                        val pending = (balance.trustedPending.toSat() + balance.untrustedPending.toSat()).toLong()

                        _uiState.update {
                            it.copy(
                                isLoadingBalance = false,
                                sourceBalanceSat = confirmed,
                                sourcePendingSat = pending,
                                seedValidated = true
                            )
                        }
                    } finally {
                        try { tempWallet.close() } catch (_: Exception) {}
                        try { tempPersister.close() } catch (_: Exception) {}
                        try { externalDesc.close() } catch (_: Exception) {}
                        try { internalDesc.close() } catch (_: Exception) {}
                        // C-1: Clean up temp DB and WAL/SHM/journal files
                        try { java.io.File(tempDbPath).delete() } catch (_: Exception) {}
                        try { java.io.File(tempDbPath + "-wal").delete() } catch (_: Exception) {}
                        try { java.io.File(tempDbPath + "-shm").delete() } catch (_: Exception) {}
                        try { java.io.File(tempDbPath + "-journal").delete() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isLoadingBalance = false,
                            error = "Invalid seed or connection error: ${e.message}"
                        )
                    }
                } finally {
                    // Security: sweep signing keys zeroed after use per audit requirement
                    mnemonicWords.fill('0')
                    passphrase?.fill('0')
                    try { mnemonic?.destroy() } catch (_: Exception) {}
                    try { secretKey?.destroy() } catch (_: Exception) {}
                }
            }
        }
    }

    fun validateWifAndFetchBalance(wifInput: CharArray) {
        _uiState.update { it.copy(isLoadingBalance = true, error = null, seedValidated = false) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val balance = withWifWallet(wifInput, _uiState.value.wifScriptType) { tempWallet, activeConn ->
                        syncWifWallet(tempWallet, activeConn)
                        tempWallet.balance()
                    }
                    val confirmed = balance.confirmed.toSat().toLong()
                    val pending = (balance.trustedPending.toSat() + balance.untrustedPending.toSat()).toLong()
                    _uiState.update {
                        it.copy(
                            isLoadingBalance = false,
                            sourceBalanceSat = confirmed,
                            sourcePendingSat = pending,
                            seedValidated = true
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isLoadingBalance = false,
                            error = "Invalid WIF or connection error: ${e.message}"
                        )
                    }
                } finally {
                    wifInput.fill('0')
                }
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
        _uiState.update { it.copy(isSweeping = true, error = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var mnemonic: Mnemonic? = null
                var secretKey: DescriptorSecretKey? = null
                try {
                    val network = if (settingsManager.isTestnet()) Network.TESTNET else Network.BITCOIN
                    val mnemonicStr = String(mnemonicWords)
                    mnemonic = Mnemonic.fromString(mnemonicStr)
                    val passphraseStr = if (passphrase != null) String(passphrase) else ""
                    secretKey = DescriptorSecretKey(network, mnemonic, passphraseStr)

                    val (externalDesc, internalDesc) = SweepDescriptorFactory.create(
                        secretKey,
                        network,
                        state.seedScriptType,
                        state.seedAccount
                    )

                    val config = settingsManager.loadElectrumConfig()
                    val activeConn = electrumConnectionFactory.createConnection(config)

                    val tempDbPath = java.io.File.createTempFile("clench_sweep2_", ".db", appContext.cacheDir).absolutePath
                    val tempPersister = Persister.newSqlite(tempDbPath)
                    val tempWallet = Wallet(externalDesc, internalDesc, network, tempPersister)

                    try {
                        // Re-sync to get latest UTXOs
                        val fullScanResult = tempWallet.startFullScan().build()
                        val update = activeConn.client.fullScan(fullScanResult, stopGap = 20uL, batchSize = 10uL, fetchPrevTxouts = false)
                        tempWallet.applyUpdate(update)

                        val destAddress = validatedDestination(state, network)
                        val feeRate = validatedFeeRate(state.feeRate)

                        // Build drain/sweep transaction (send max)
                        val address = org.bitcoindevkit.Address(destAddress, network)
                        val script = address.scriptPubkey()
                        val psbt = TxBuilder()
                            .drainWallet()
                            .drainTo(script)
                            .feeRate(feeRate)
                            .finish(tempWallet)

                        // Sign
                        val finalized = tempWallet.sign(psbt)
                        if (!finalized) {
                            throw Exception("Transaction signing incomplete")
                        }

                        // Extract and hold for immutable review. Broadcasting happens only
                        // after the user verifies the exact destination, amount, and fee.
                        val tx = psbt.extractTx()
                        prepareSweepTransaction(tempWallet, tx, state, destAddress)
                    } finally {
                        try { activeConn.close() } catch (_: Exception) {}
                        try { tempWallet.close() } catch (_: Exception) {}
                        try { tempPersister.close() } catch (_: Exception) {}
                        try { externalDesc.close() } catch (_: Exception) {}
                        try { internalDesc.close() } catch (_: Exception) {}
                        // C-1: Clean up temp DB and WAL/SHM/journal files
                        try { java.io.File(tempDbPath).delete() } catch (_: Exception) {}
                        try { java.io.File(tempDbPath + "-wal").delete() } catch (_: Exception) {}
                        try { java.io.File(tempDbPath + "-shm").delete() } catch (_: Exception) {}
                        try { java.io.File(tempDbPath + "-journal").delete() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isSweeping = false, error = e.message ?: "Sweep failed") }
                } finally {
                    // Security: sweep signing keys zeroed after broadcast per audit requirement
                    mnemonicWords.fill('0')
                    passphrase?.fill('0')
                    try { mnemonic?.destroy() } catch (_: Exception) {}
                    try { secretKey?.destroy() } catch (_: Exception) {}
                }
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
        _uiState.update { it.copy(isSweeping = true, error = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    withWifWallet(wifInput, state.wifScriptType) { tempWallet, activeConn ->
                        syncWifWallet(tempWallet, activeConn)

                        val destAddress = validatedDestination(state, tempWallet.network())
                        val feeRate = validatedFeeRate(state.feeRate)

                        val address = org.bitcoindevkit.Address(destAddress, tempWallet.network())
                        val psbt = TxBuilder()
                            .drainWallet()
                            .drainTo(address.scriptPubkey())
                            .feeRate(feeRate)
                            .finish(tempWallet)

                        val finalized = tempWallet.sign(psbt)
                        if (!finalized) {
                            throw Exception("Transaction signing incomplete")
                        }

                        prepareSweepTransaction(tempWallet, psbt.extractTx(), state, destAddress)
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isSweeping = false, error = e.message ?: "Sweep failed") }
                } finally {
                    wifInput.fill('0')
                }
            }
        }
    }

    fun sweepSatscardPrivateKey(privateKey: ByteArray, sourceIsTestnet: Boolean) {
        _uiState.update { it.copy(isSweeping = true, error = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var wifChars: CharArray? = null
                try {
                    val walletIsTestnet = settingsManager.isTestnet()
                    if (sourceIsTestnet != walletIsTestnet) {
                        val cardNetwork = if (sourceIsTestnet) "testnet" else "mainnet"
                        val walletNetwork = if (walletIsTestnet) "testnet" else "mainnet"
                        throw Exception("SATSCARD is $cardNetwork but this wallet is $walletNetwork")
                    }
                    val network = if (sourceIsTestnet) Network.TESTNET else Network.BITCOIN
                    val wif = WifPrivateKeyParser.fromRawPrivateKey(privateKey, network, compressed = true)
                    wifChars = wif.value.toCharArray()
                    withWifWallet(wifChars, SweepWifScriptType.NATIVE_SEGWIT) { tempWallet, activeConn ->
                        syncWifWallet(tempWallet, activeConn)

                        val balance = tempWallet.balance()
                        val confirmed = balance.confirmed.toSat().toLong()
                        if (confirmed <= 0L) {
                            throw Exception("No confirmed funds found on the unsealed SATSCARD slot")
                        }

                        val currentState = _uiState.value
                        val destAddress = validatedDestination(currentState, tempWallet.network())
                        val feeRate = validatedFeeRate(currentState.feeRate)

                        val address = org.bitcoindevkit.Address(destAddress, tempWallet.network())
                        val psbt = TxBuilder()
                            .drainWallet()
                            .drainTo(address.scriptPubkey())
                            .feeRate(feeRate)
                            .finish(tempWallet)

                        val finalized = tempWallet.sign(psbt)
                        if (!finalized) {
                            throw Exception("Transaction signing incomplete")
                        }

                        prepareSweepTransaction(tempWallet, psbt.extractTx(), currentState, destAddress)
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isSweeping = false, error = e.message ?: "SATSCARD sweep failed") }
                } finally {
                    privateKey.fill(0)
                    wifChars?.fill('0')
                }
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
            } catch (e: Exception) {
                _uiState.update { it.copy(isBroadcasting = false, error = e.message ?: "Sweep broadcast failed") }
            }
        }
    }

    private fun validatedDestination(state: UiState, network: Network): String {
        val raw = state.destinationAddress.trim()
        if (raw.isBlank()) throw IllegalArgumentException("Destination address is empty")
        val address = org.bitcoindevkit.Address(raw, network)
        check(address.isValidForNetwork(network)) { "Destination address is for the wrong Bitcoin network" }
        val normalized = address.toString()
        if (normalized != state.defaultDestinationAddress && !state.externalDestinationConfirmed) {
            throw SecurityException(
                "This destination is not the receive address Clench generated for the selected wallet. Confirm the external destination before preparing the sweep."
            )
        }
        return normalized
    }

    private fun validatedFeeRate(raw: String): FeeRate {
        val value = raw.toDoubleOrNull()
        require(value != null && value.isFinite() && value in 1.0..SendViewModel.MAX_FEE_RATE_SAT_VB.toDouble()) {
            "Enter a fee rate from 1 to ${SendViewModel.MAX_FEE_RATE_SAT_VB.toInt()} sat/vB"
        }
        return FeeRate.fromSatPerVb(kotlin.math.ceil(value).toLong().toULong())
    }

    private fun prepareSweepTransaction(
        sourceWallet: Wallet,
        tx: Transaction,
        draft: UiState,
        destinationAddress: String
    ) {
        try {
            val outputs = tx.output()
            check(outputs.size == 1) { "Sweep transaction contains an unexpected output" }
            val expectedScript = org.bitcoindevkit.Address(destinationAddress, sourceWallet.network()).scriptPubkey()
            check(outputs.single().scriptPubkey.toBytes().contentEquals(expectedScript.toBytes())) {
                "Sweep transaction destination differs from the reviewed address"
            }
            val feeSat = sourceWallet.calculateFee(tx).toSat().toLong()
            val vsize = tx.vsize().toLong()
            val review = BuiltTransactionReview(
                txid = tx.computeTxid().toString(),
                feeSat = feeSat,
                vsize = vsize,
                feeRateSatPerVbyte = if (vsize > 0) feeSat.toDouble() / vsize else 0.0,
                inputs = tx.input().map { "${it.previousOutput.txid}:${it.previousOutput.vout}" },
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
            tx.close()
        }
    }

    private suspend fun <T> withWifWallet(
        wifInput: CharArray,
        script: SweepWifScriptType,
        block: (Wallet, net.clench.wallet.data.network.ActiveElectrumConnection) -> T
    ): T {
        val network = if (settingsManager.isTestnet()) Network.TESTNET else Network.BITCOIN
        val wif = WifPrivateKeyParser.extract(wifInput, network)
        var externalDesc: Descriptor? = null
        var internalDesc: Descriptor? = null
        val tempDbPath = java.io.File.createTempFile("clench_wif_sweep_", ".db", appContext.cacheDir).absolutePath
        val tempPersister = Persister.newSqlite(tempDbPath)
        var activeConn: net.clench.wallet.data.network.ActiveElectrumConnection? = null
        var tempWallet: Wallet? = null
        try {
            val descriptors = SweepWifDescriptorFactory.create(wif.value, network, script)
            externalDesc = descriptors.first
            internalDesc = descriptors.second
            val wallet = Wallet(externalDesc, internalDesc, network, tempPersister)
            tempWallet = wallet
            val config = settingsManager.loadElectrumConfig()
            val connection = electrumConnectionFactory.createConnection(config)
            activeConn = connection
            return block(wallet, connection)
        } finally {
            try { activeConn?.close() } catch (_: Exception) {}
            try { tempWallet?.close() } catch (_: Exception) {}
            try { tempPersister.close() } catch (_: Exception) {}
            try { externalDesc?.destroy() } catch (_: Exception) {}
            try { internalDesc?.destroy() } catch (_: Exception) {}
            try { java.io.File(tempDbPath).delete() } catch (_: Exception) {}
            try { java.io.File(tempDbPath + "-wal").delete() } catch (_: Exception) {}
            try { java.io.File(tempDbPath + "-shm").delete() } catch (_: Exception) {}
            try { java.io.File(tempDbPath + "-journal").delete() } catch (_: Exception) {}
        }
    }

    private fun syncWifWallet(
        wallet: Wallet,
        activeConn: net.clench.wallet.data.network.ActiveElectrumConnection
    ) {
        // A WIF controls one fixed, non-wildcard script. Reveal that script and use
        // BDK's bounded sync request; gap-based full scans are intended for ranged
        // descriptors rather than this one fixed script.
        val addressInfo = wallet.revealNextAddress(org.bitcoindevkit.KeychainKind.EXTERNAL)
        try {
            wallet.startSyncWithRevealedSpks().use { builder ->
                builder.build().use { request ->
                    val update = activeConn.client.sync(
                        request,
                        batchSize = 1uL,
                        fetchPrevTxouts = false
                    )
                    wallet.applyUpdate(update)
                }
            }
        } finally {
            addressInfo.destroy()
        }
    }

}
