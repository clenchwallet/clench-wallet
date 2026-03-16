package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.WordCount
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class CreateWalletViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val walletName: String = "My Wallet",
        val wordCount: Int = 24,
        val passphrase: String = "",
        val pendingPassphrase: String = "",
        val mnemonic: List<String> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val fingerprintBytes: ByteArray? = null,
        val masterFingerprintBytes: ByteArray? = null,
        val descriptorString: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UiState) return false
            return walletName == other.walletName && wordCount == other.wordCount &&
                passphrase == other.passphrase && pendingPassphrase == other.pendingPassphrase &&
                mnemonic == other.mnemonic &&
                isLoading == other.isLoading && error == other.error &&
                fingerprintBytes.contentEquals(other.fingerprintBytes) &&
                masterFingerprintBytes.contentEquals(other.masterFingerprintBytes) &&
                descriptorString == other.descriptorString
        }
        override fun hashCode(): Int {
            var result = walletName.hashCode()
            result = 31 * result + wordCount
            result = 31 * result + passphrase.hashCode()
            result = 31 * result + pendingPassphrase.hashCode()
            result = 31 * result + mnemonic.hashCode()
            result = 31 * result + isLoading.hashCode()
            result = 31 * result + (error?.hashCode() ?: 0)
            result = 31 * result + (fingerprintBytes?.contentHashCode() ?: 0)
            result = 31 * result + (masterFingerprintBytes?.contentHashCode() ?: 0)
            result = 31 * result + (descriptorString?.hashCode() ?: 0)
            return result
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    // Hold generated mnemonic in memory only (not persisted until confirmAndSave)
    private var pendingMnemonic: List<String>? = null

    /**
     * Best-effort mnemonic cleanup when ViewModel is destroyed.
     * Note: JVM Strings can't be zeroed in memory, but nullifying references
     * allows GC to reclaim them and reduces the window of exposure.
     */
    override fun onCleared() {
        super.onCleared()
        pendingMnemonic = null
        _uiState.update {
            it.copy(
                mnemonic = emptyList(),
                passphrase = "",
                pendingPassphrase = "",
                descriptorString = null,
                fingerprintBytes = null,
                masterFingerprintBytes = null
            )
        }
    }

    fun setWalletName(name: String) = _uiState.update { it.copy(walletName = name) }
    fun setWordCount(count: Int) = _uiState.update { it.copy(wordCount = count, mnemonic = emptyList(), descriptorString = null, fingerprintBytes = null) }

    fun setPassphrase(pass: String) {
        _uiState.update { it.copy(passphrase = pass) }
        regenerateDescriptorAndFingerprint()
    }

    /** Store passphrase as pending for confirmation on the next screen */
    fun setPendingPassphrase() {
        _uiState.update { it.copy(pendingPassphrase = it.passphrase) }
    }

    /**
     * After passphrase changes, regenerate the descriptor from the pending mnemonic
     * so that the master fingerprint (and thus the visual fingerprint) updates dynamically.
     */
    private fun regenerateDescriptorAndFingerprint() {
        val mnemonic = pendingMnemonic ?: return
        val state = _uiState.value
        try {
            val mnemonicObj = Mnemonic.fromString(mnemonic.joinToString(" "))
            val network = Network.BITCOIN // fingerprint doesn't depend on network
            val secretKey = DescriptorSecretKey(network, mnemonicObj, state.passphrase)
            val descriptor = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
            val descriptorStr = descriptor.toString()
            _uiState.update { it.copy(descriptorString = descriptorStr) }
            updateFingerprint()
        } catch (_: Exception) {
            _uiState.update { it.copy(fingerprintBytes = null) }
        }
    }

    private fun updateFingerprint() {
        val state = _uiState.value
        val descriptorStr = state.descriptorString

        if (descriptorStr == null) {
            _uiState.update { it.copy(fingerprintBytes = null, masterFingerprintBytes = null) }
            return
        }

        val masterFp = extractMasterFingerprint(descriptorStr)
        if (masterFp == null) {
            _uiState.update { it.copy(fingerprintBytes = null, masterFingerprintBytes = null) }
            return
        }

        try {
            val fpBytes = computeFingerprint(masterFp, state.passphrase)
            _uiState.update { it.copy(
                fingerprintBytes = fpBytes.sliceArray(0 until 8),
                masterFingerprintBytes = masterFp
            ) }
        } catch (_: Exception) {
            _uiState.update { it.copy(fingerprintBytes = null, masterFingerprintBytes = null) }
        }
    }

    /**
     * Compute fingerprint + master key fingerprint for an arbitrary passphrase (used by confirm screen).
     * Returns Pair(identicon bytes [8], master fingerprint [4]) or null.
     */
    fun computeFingerprintForPassphrase(passphrase: String): Pair<ByteArray, ByteArray>? {
        val mnemonic = pendingMnemonic ?: return null
        return try {
            val mnemonicObj = Mnemonic.fromString(mnemonic.joinToString(" "))
            val network = Network.BITCOIN
            val secretKey = DescriptorSecretKey(network, mnemonicObj, passphrase)
            val descriptor = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
            val newDescriptorStr = descriptor.toString()
            val newMasterFp = extractMasterFingerprint(newDescriptorStr) ?: return null
            val identiconBytes = computeFingerprint(newMasterFp, passphrase).sliceArray(0 until 8)
            Pair(identiconBytes, newMasterFp)
        } catch (_: Exception) {
            null
        }
    }

    fun generateWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val wordCountEnum = if (_uiState.value.wordCount == 12) WordCount.WORDS12 else WordCount.WORDS24
                val mnemonic = Mnemonic(wordCountEnum)
                val mnemonicWords = mnemonic.toString().split(" ")

                pendingMnemonic = mnemonicWords

                val passphrase = _uiState.value.passphrase
                val network = Network.BITCOIN
                val secretKey = DescriptorSecretKey(network, mnemonic, passphrase)
                val descriptor = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
                val descriptorStr = descriptor.toString()

                _uiState.update { it.copy(mnemonic = mnemonicWords, isLoading = false, descriptorString = descriptorStr) }
                updateFingerprint()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun confirmAndSave(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val mnemonicWords = pendingMnemonic ?: throw IllegalStateException("No mnemonic generated")
                val passphraseToUse = _uiState.value.pendingPassphrase.ifBlank {
                    _uiState.value.passphrase.ifBlank { null }
                }

                val walletData = bitcoinRepository.importWallet(
                    name = _uiState.value.walletName,
                    mnemonic = mnemonicWords,
                    passphrase = passphraseToUse
                )

                pendingMnemonic = null
                _uiState.update { it.copy(mnemonic = emptyList(), passphrase = "", pendingPassphrase = "", descriptorString = null, fingerprintBytes = null, masterFingerprintBytes = null) }

                onCreated(walletData.id)
            } catch (e: Exception) {
                android.util.Log.e("CreateWallet", "confirmAndSave failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false, error = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    companion object {
        fun extractMasterFingerprint(descriptorString: String): ByteArray? {
            val match = Regex("\\[([0-9a-fA-F]{8})/").find(descriptorString)
            val hex = match?.groupValues?.get(1) ?: return null
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        fun computeFingerprint(masterFingerprintBytes: ByteArray, passphrase: String): ByteArray {
            val input = masterFingerprintBytes + passphrase.toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(input)
        }
    }
}
