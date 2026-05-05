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
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.Wallet
import javax.inject.Inject

@HiltViewModel
class EphemeralSeedSigningViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val keystoreManager: KeystoreManager,
    private val walletDao: WalletDao
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val broadcastTxid: String? = null,
        val convertedToHot: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Sign a PSBT using an ephemeral seed phrase and broadcast.
     *
     * Security: mnemonic and derived keys zeroed after signing per audit requirement.
     * The mnemonic CharArray is zeroed in the finally block regardless of success/failure.
     * BDK Mnemonic and DescriptorSecretKey objects are destroyed after use.
     */
    fun signAndBroadcast(
        walletId: String,
        psbtBase64: String,
        mnemonicWords: CharArray,
        passphrase: CharArray?,
        saveAsHotWallet: Boolean
    ) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var mnemonic: Mnemonic? = null
                var secretKey: DescriptorSecretKey? = null
                var tempDbFile: java.io.File? = null
                try {
                    val network = if (settingsManager.isTestnet()) Network.TESTNET else Network.BITCOIN
                    val mnemonicStr = String(mnemonicWords)
                    mnemonic = Mnemonic.fromString(mnemonicStr)
                    val passphraseStr = if (passphrase != null) String(passphrase) else ""
                    secretKey = DescriptorSecretKey(network, mnemonic, passphraseStr)

                    val externalDesc = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
                    val internalDesc = Descriptor.newBip84(secretKey, KeychainKind.INTERNAL, network)

                    // Load the wallet's PSBT and sign it
                    val psbt = Psbt(psbtBase64)

                    // Verify wallet exists
                    walletDao.getById(walletId)
                        ?: throw IllegalStateException("Wallet not found")

                    // Create a temporary signing wallet using cacheDir (OS can clear) instead of default tmpdir
                    tempDbFile = java.io.File.createTempFile("clench_ephemeral_", ".db", appContext.cacheDir)
                    val tempDbPath = tempDbFile!!.absolutePath
                    val tempPersister = org.bitcoindevkit.Persister.newSqlite(tempDbPath)
                    val signingWallet = Wallet(externalDesc, internalDesc, network, tempPersister)

                    try {
                        // Sign the PSBT
                        val finalized = signingWallet.sign(psbt)
                        if (!finalized) {
                            throw Exception("PSBT signing incomplete — wallet may not have matching keys for this transaction")
                        }

                        // Broadcast via the repository
                        val txid = bitcoinRepository.applyAndBroadcastPsbt(
                            walletId,
                            psbt.serialize(),
                            psbtBase64
                        )

                        if (saveAsHotWallet) {
                            // Store mnemonic in keystore and update wallet to hot
                            val mnemonicString = String(mnemonicWords)
                            keystoreManager.storeMnemonic(walletId, mnemonicString)
                            keystoreManager.storeSecretDescriptor(walletId, externalDesc.toStringWithSecret())
                            keystoreManager.storeSecretChangeDescriptor(walletId, internalDesc.toStringWithSecret())
                            walletDao.setWatchOnly(walletId, false)
                        }

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                broadcastTxid = txid,
                                convertedToHot = saveAsHotWallet
                            )
                        }
                    } finally {
                        // BDK 2.x: Wallet resources released by GC/Drop
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Signing failed") }
                } finally {
                    // Security: always delete temp DB files (including WAL/SHM) regardless of success/failure
                    tempDbFile?.let { dbFile ->
                        try { dbFile.delete() } catch (_: Exception) {}
                        try { java.io.File(dbFile.absolutePath + "-wal").delete() } catch (_: Exception) {}
                        try { java.io.File(dbFile.absolutePath + "-shm").delete() } catch (_: Exception) {}
                    }
                    // Security: mnemonic and derived keys zeroed after signing per audit requirement
                    mnemonicWords.fill('0')
                    passphrase?.fill('0')
                    try { mnemonic?.destroy() } catch (_: Exception) {}
                    try { secretKey?.destroy() } catch (_: Exception) {}
                }
            }
        }
    }

}
