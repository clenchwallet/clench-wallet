package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class ImportWalletViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    enum class ImportMode { SEED, DESCRIPTOR }

    data class UiState(
        val importMode: ImportMode = ImportMode.SEED,
        val walletName: String = "",
        val seedInput: String = "",
        val passphrase: String = "",
        val descriptorInput: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val fingerprintBytes: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UiState) return false
            return importMode == other.importMode && walletName == other.walletName &&
                seedInput == other.seedInput && passphrase == other.passphrase &&
                descriptorInput == other.descriptorInput && isLoading == other.isLoading &&
                error == other.error && fingerprintBytes.contentEquals(other.fingerprintBytes)
        }
        override fun hashCode(): Int {
            var result = importMode.hashCode()
            result = 31 * result + walletName.hashCode()
            result = 31 * result + seedInput.hashCode()
            result = 31 * result + passphrase.hashCode()
            result = 31 * result + descriptorInput.hashCode()
            result = 31 * result + isLoading.hashCode()
            result = 31 * result + (error?.hashCode() ?: 0)
            result = 31 * result + (fingerprintBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun setImportMode(mode: ImportMode) = _uiState.update { it.copy(importMode = mode, error = null) }
    fun setWalletName(name: String) = _uiState.update { it.copy(walletName = name) }
    fun setSeedInput(seed: String) {
        _uiState.update { it.copy(seedInput = seed) }
        updateFingerprint()
    }
    fun setPassphrase(pass: String) {
        _uiState.update { it.copy(passphrase = pass) }
        updateFingerprint()
    }
    fun setDescriptorInput(desc: String) = _uiState.update { it.copy(descriptorInput = desc) }

    /**
     * Recompute the fingerprint whenever seed or passphrase changes.
     * This helps the user verify they're entering the right passphrase.
     */
    private fun updateFingerprint() {
        val state = _uiState.value
        val words = state.seedInput.trim().split("\\s+".toRegex())
        if (words.size != 12 && words.size != 24) {
            _uiState.update { it.copy(fingerprintBytes = null) }
            return
        }

        try {
            val mnemonicObj = Mnemonic.fromString(words.joinToString(" "))
            val network = Network.BITCOIN
            val secretKey = DescriptorSecretKey(network, mnemonicObj, state.passphrase)
            val descriptor = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
            val descriptorStr = descriptor.toString()

            val masterFp = CreateWalletViewModel.extractMasterFingerprint(descriptorStr)
            if (masterFp != null) {
                val fpBytes = CreateWalletViewModel.computeFingerprint(masterFp, state.passphrase)
                _uiState.update { it.copy(fingerprintBytes = fpBytes.sliceArray(0 until 8)) }
            } else {
                _uiState.update { it.copy(fingerprintBytes = null) }
            }
        } catch (_: Exception) {
            _uiState.update { it.copy(fingerprintBytes = null) }
        }
    }

    fun importWallet(onImported: (String) -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val walletData = when (state.importMode) {
                    ImportMode.SEED -> {
                        val words = state.seedInput.trim().split("\\s+".toRegex())
                        if (words.size != 12 && words.size != 24) {
                            _uiState.update { it.copy(isLoading = false, error = "Please enter 12 or 24 words") }
                            return@launch
                        }
                        // Validate BIP39 word list before hitting BDK
                        try {
                            org.bitcoindevkit.Mnemonic.fromString(words.joinToString(" "))
                        } catch (e: Exception) {
                            _uiState.update { it.copy(isLoading = false, error = "Invalid seed phrase — check that all words are valid BIP39 words and in the correct order") }
                            return@launch
                        }
                        bitcoinRepository.importWallet(
                            name = state.walletName.ifBlank { "Imported Wallet" },
                            mnemonic = words,
                            passphrase = state.passphrase.ifBlank { null }
                        )
                    }
                    ImportMode.DESCRIPTOR -> {
                        if (state.descriptorInput.isBlank()) {
                            _uiState.update { it.copy(isLoading = false, error = "Please enter a descriptor or xpub") }
                            return@launch
                        }
                        bitcoinRepository.importWatchOnly(
                            name = state.walletName.ifBlank { "Watch-only Wallet" },
                            descriptor = state.descriptorInput.trim()
                        )
                    }
                }
                onImported(walletData.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
