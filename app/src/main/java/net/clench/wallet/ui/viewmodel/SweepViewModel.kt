package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.FeeEstimates
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.Amount
import org.bitcoindevkit.Connection
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.ElectrumClient
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Wallet
import javax.inject.Inject

@HiltViewModel
class SweepViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    data class UiState(
        val walletName: String = "",
        val destinationAddress: String = "",
        val sourceBalanceSat: Long = 0L,
        val sourcePendingSat: Long = 0L,
        val feeEstimates: FeeEstimates? = null,
        val selectedFeeTier: FeeTier = FeeTier.STANDARD,
        val feeRate: String = "2",
        val isLoadingBalance: Boolean = false,
        val isSweeping: Boolean = false,
        val error: String? = null,
        val broadcastTxid: String? = null,
        val seedValidated: Boolean = false
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
                        destinationAddress = address.address
                    )
                }
                fetchFeeEstimates()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun setDestinationAddress(addr: String) {
        _uiState.update { it.copy(destinationAddress = addr) }
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
            state.copy(selectedFeeTier = tier, feeRate = feeRate)
        }
    }

    fun setFeeRate(rate: String) {
        _uiState.update { it.copy(feeRate = rate, selectedFeeTier = FeeTier.CUSTOM) }
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

                    val externalDesc = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
                    val internalDesc = Descriptor.newBip84(secretKey, KeychainKind.INTERNAL, network)

                    // Create temp wallet to sync and get balance
                    val tempDbPath = java.io.File.createTempFile("clench_sweep_", ".db").absolutePath
                    val tempConnection = Connection(tempDbPath)
                    val tempWallet = Wallet(externalDesc, internalDesc, network, tempConnection)

                    try {
                        // Sync the temp wallet
                        val config = settingsManager.loadElectrumConfig()
                        val protocol = if (config.useSsl) "ssl" else "tcp"
                        val url = "$protocol://${config.serverUrl}:${config.port}"
                        val client = ElectrumClient(url)
                        val fullScanResult = tempWallet.startFullScan().build()
                        val update = client.fullScan(fullScanResult, stopGap = 20u, batchSize = 10u, fetchPrevTxouts = false)
                        tempWallet.applyUpdate(update)
                        client.close()

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
                        try { java.io.File(tempDbPath).delete() } catch (_: Exception) {}
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

                    val externalDesc = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
                    val internalDesc = Descriptor.newBip84(secretKey, KeychainKind.INTERNAL, network)

                    val config = settingsManager.loadElectrumConfig()
                    val protocol = if (config.useSsl) "ssl" else "tcp"
                    val url = "$protocol://${config.serverUrl}:${config.port}"
                    val client = ElectrumClient(url)

                    val tempDbPath = java.io.File.createTempFile("clench_sweep2_", ".db").absolutePath
                    val tempConnection = Connection(tempDbPath)
                    val tempWallet = Wallet(externalDesc, internalDesc, network, tempConnection)

                    try {
                        // Re-sync to get latest UTXOs
                        val fullScanResult = tempWallet.startFullScan().build()
                        val update = client.fullScan(fullScanResult, stopGap = 20u, batchSize = 10u, fetchPrevTxouts = false)
                        tempWallet.applyUpdate(update)

                        val destAddress = state.destinationAddress
                        if (destAddress.isBlank()) {
                            throw Exception("Destination address is empty")
                        }

                        val feeRateVal = state.feeRate.toFloatOrNull()?.toLong()?.coerceAtLeast(1L)?.toULong()
                            ?: throw Exception("Invalid fee rate")
                        val feeRate = FeeRate.fromSatPerVb(feeRateVal)

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

                        // Extract and broadcast
                        val tx = psbt.extractTx()
                        val txid = client.transactionBroadcast(tx)
                        client.close()

                        _uiState.update {
                            it.copy(
                                isSweeping = false,
                                broadcastTxid = txid
                            )
                        }
                    } finally {
                        try { tempWallet.close() } catch (_: Exception) {}
                        try { java.io.File(tempDbPath).delete() } catch (_: Exception) {}
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
}
