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
        val passphraseConfirm: String = "",
        val passphraseError: String? = null,
        val mnemonic: List<String> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val fingerprintBytes: ByteArray? = null,
        val descriptorString: String? = null  // cached for fingerprint computation
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UiState) return false
            return walletName == other.walletName && wordCount == other.wordCount &&
                passphrase == other.passphrase && passphraseConfirm == other.passphraseConfirm &&
                passphraseError == other.passphraseError &&
                mnemonic == other.mnemonic &&
                isLoading == other.isLoading && error == other.error &&
                fingerprintBytes.contentEquals(other.fingerprintBytes) &&
                descriptorString == other.descriptorString
        }
        override fun hashCode(): Int {
            var result = walletName.hashCode()
            result = 31 * result + wordCount
            result = 31 * result + passphrase.hashCode()
            result = 31 * result + passphraseConfirm.hashCode()
            result = 31 * result + (passphraseError?.hashCode() ?: 0)
            result = 31 * result + mnemonic.hashCode()
            result = 31 * result + isLoading.hashCode()
            result = 31 * result + (error?.hashCode() ?: 0)
            result = 31 * result + (fingerprintBytes?.contentHashCode() ?: 0)
            result = 31 * result + (descriptorString?.hashCode() ?: 0)
            return result
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    // Hold generated mnemonic in memory only (not persisted until confirmAndSave)
    private var pendingMnemonic: List<String>? = null

    fun setWalletName(name: String) = _uiState.update { it.copy(walletName = name) }
    fun setWordCount(count: Int) = _uiState.update { it.copy(wordCount = count, mnemonic = emptyList(), descriptorString = null, fingerprintBytes = null) }

    fun setPassphrase(pass: String) {
        _uiState.update {
            val error = if (it.passphraseConfirm.isNotEmpty() && it.passphraseConfirm != pass)
                "Passphrases do not match" else null
            it.copy(passphrase = pass, passphraseError = error)
        }
        regenerateDescriptorAndFingerprint()
    }

    fun setPassphraseConfirm(value: String) {
        _uiState.update {
            val error = if (value.isNotEmpty() && value != it.passphrase)
                "Passphrases do not match" else null
            it.copy(passphraseConfirm = value, passphraseError = error)
        }
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
            // If BDK fails for some reason, clear fingerprint
            _uiState.update { it.copy(fingerprintBytes = null) }
        }
    }

    private fun updateFingerprint() {
        val state = _uiState.value
        val descriptorStr = state.descriptorString

        if (descriptorStr == null) {
            _uiState.update { it.copy(fingerprintBytes = null) }
            return
        }

        val masterFp = extractMasterFingerprint(descriptorStr)
        if (masterFp == null) {
            _uiState.update { it.copy(fingerprintBytes = null) }
            return
        }

        try {
            val fpBytes = computeFingerprint(masterFp, state.passphrase)
            _uiState.update { it.copy(fingerprintBytes = fpBytes.sliceArray(0 until 8)) }
        } catch (_: Exception) {
            _uiState.update { it.copy(fingerprintBytes = null) }
        }
    }

    fun generateWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // ONLY generate mnemonic in memory — zero DB/keystore writes
                val wordCountEnum = if (_uiState.value.wordCount == 12) WordCount.WORDS12 else WordCount.WORDS24
                val mnemonic = Mnemonic(wordCountEnum)
                val mnemonicWords = mnemonic.toString().split(" ")

                // Store in memory
                pendingMnemonic = mnemonicWords

                // Generate descriptor for fingerprint computation
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
                // Use the same mnemonic words from generateWallet()
                val mnemonicWords = pendingMnemonic ?: throw IllegalStateException("No mnemonic generated")

                val walletData = bitcoinRepository.importWallet(
                    name = _uiState.value.walletName,
                    mnemonic = mnemonicWords,
                    passphrase = _uiState.value.passphrase.ifBlank { null }
                )

                // Clear mnemonic from memory as best effort — JVM String is immutable
                // so the backing char[] can't be zeroed, but at least remove references
                // so it becomes eligible for GC and won't be shown if user navigates back.
                pendingMnemonic = null
                _uiState.update { it.copy(mnemonic = emptyList(), passphrase = "", passphraseConfirm = "", descriptorString = null, fingerprintBytes = null) }

                onCreated(walletData.id)
            } catch (e: Exception) {
                android.util.Log.e("CreateWallet", "confirmAndSave failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false, error = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    companion object {
        /**
         * Extract the 4-byte master fingerprint from a descriptor origin string.
         * E.g. from "wpkh([AABBCCDD/84h/0h/0h]xpub...)" extracts bytes [AA, BB, CC, DD].
         */
        fun extractMasterFingerprint(descriptorString: String): ByteArray? {
            val match = Regex("\\[([0-9a-fA-F]{8})/").find(descriptorString)
            val hex = match?.groupValues?.get(1) ?: return null
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        /**
         * Compute a visual fingerprint from master fingerprint bytes + passphrase.
         * SHA-256(masterFingerprint + passphrase.toByteArray())
         */
        fun computeFingerprint(masterFingerprintBytes: ByteArray, passphrase: String): ByteArray {
            val input = masterFingerprintBytes + passphrase.toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(input)
        }
    }
}
