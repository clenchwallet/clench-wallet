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

    /**
     * Auto-detected input type for unified import field.
     */
    enum class DetectedType {
        NONE,
        SEED_12,
        SEED_24,
        XPUB_WATCH_ONLY,
        DESCRIPTOR,
        PRIVATE_DESCRIPTOR
    }

    data class UiState(
        val walletName: String = "",
        val input: String = "",
        val passphrase: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val fingerprintBytes: ByteArray? = null,
        val masterFingerprintBytes: ByteArray? = null,
        val detectedType: DetectedType = DetectedType.NONE,
        val detectedLabel: String = ""
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UiState) return false
            return walletName == other.walletName && input == other.input &&
                passphrase == other.passphrase && isLoading == other.isLoading &&
                error == other.error && fingerprintBytes.contentEquals(other.fingerprintBytes) &&
                masterFingerprintBytes.contentEquals(other.masterFingerprintBytes) &&
                detectedType == other.detectedType && detectedLabel == other.detectedLabel
        }
        override fun hashCode(): Int {
            var result = walletName.hashCode()
            result = 31 * result + input.hashCode()
            result = 31 * result + passphrase.hashCode()
            result = 31 * result + isLoading.hashCode()
            result = 31 * result + (error?.hashCode() ?: 0)
            result = 31 * result + (fingerprintBytes?.contentHashCode() ?: 0)
            result = 31 * result + (masterFingerprintBytes?.contentHashCode() ?: 0)
            result = 31 * result + detectedType.hashCode()
            result = 31 * result + detectedLabel.hashCode()
            return result
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    override fun onCleared() {
        super.onCleared()
        _uiState.update {
            it.copy(input = "", passphrase = "", fingerprintBytes = null, masterFingerprintBytes = null)
        }
    }

    fun setWalletName(name: String) = _uiState.update { it.copy(walletName = name) }

    fun setInput(text: String) {
        _uiState.update { it.copy(input = text, error = null) }
        detectInputType()
        updateFingerprint()
    }

    fun setPassphrase(pass: String) {
        _uiState.update { it.copy(passphrase = pass) }
        updateFingerprint()
    }

    /**
     * Auto-detect input type from the text content.
     */
    private fun detectInputType() {
        val text = _uiState.value.input.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(detectedType = DetectedType.NONE, detectedLabel = "") }
            return
        }

        val lower = text.lowercase()

        // Check for descriptor patterns
        if (lower.contains("wpkh(") || lower.contains("tr(") || lower.contains("pkh(") || lower.contains("wsh(")) {
            // Check if it contains a private key
            if (lower.contains("xprv") || lower.contains("zprv") || lower.contains("tprv")) {
                _uiState.update { it.copy(
                    detectedType = DetectedType.PRIVATE_DESCRIPTOR,
                    detectedLabel = "Detected: private descriptor"
                ) }
            } else {
                _uiState.update { it.copy(
                    detectedType = DetectedType.DESCRIPTOR,
                    detectedLabel = "Detected: descriptor (watch-only)"
                ) }
            }
            return
        }

        // Check for key-origin-prefixed xpub: [fingerprint/path]xpub...
        val originPattern = Regex("^\\[([0-9a-fA-F]{8})/[^]]+\\](.+)")
        val originMatch = originPattern.find(text)
        if (originMatch != null) {
            val keyPart = originMatch.groupValues[2].lowercase()
            val fingerprint = originMatch.groupValues[1]
            if (keyPart.startsWith("xpub") || keyPart.startsWith("zpub") || keyPart.startsWith("ypub") ||
                keyPart.startsWith("vpub") || keyPart.startsWith("tpub")) {
                _uiState.update { it.copy(
                    detectedType = DetectedType.XPUB_WATCH_ONLY,
                    detectedLabel = "Detected: ${keyPart.take(4)} with origin [$fingerprint] (watch-only)"
                ) }
                return
            }
            if (keyPart.startsWith("xprv") || keyPart.startsWith("zprv") || keyPart.startsWith("tprv")) {
                _uiState.update { it.copy(
                    detectedType = DetectedType.PRIVATE_DESCRIPTOR,
                    detectedLabel = "Detected: private key with origin [$fingerprint]"
                ) }
                return
            }
        }

        // Check for xpub/zpub/ypub/vpub/tpub
        if (lower.startsWith("xpub") || lower.startsWith("zpub") || lower.startsWith("ypub") ||
            lower.startsWith("vpub") || lower.startsWith("tpub")) {
            _uiState.update { it.copy(
                detectedType = DetectedType.XPUB_WATCH_ONLY,
                detectedLabel = "Detected: ${text.take(4)} (watch-only)"
            ) }
            return
        }

        // Check for xprv/zprv
        if (lower.startsWith("xprv") || lower.startsWith("zprv")) {
            _uiState.update { it.copy(
                detectedType = DetectedType.PRIVATE_DESCRIPTOR,
                detectedLabel = "Detected: private key"
            ) }
            return
        }

        // Check for seed phrase (12 or 24 words)
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.size == 12) {
            _uiState.update { it.copy(
                detectedType = DetectedType.SEED_12,
                detectedLabel = "Detected: 12-word seed phrase"
            ) }
            return
        }
        if (words.size == 24) {
            _uiState.update { it.copy(
                detectedType = DetectedType.SEED_24,
                detectedLabel = "Detected: 24-word seed phrase"
            ) }
            return
        }

        // Partial seed phrase
        if (words.size in 2..23 && words.all { it.matches(Regex("[a-z]+")) }) {
            _uiState.update { it.copy(
                detectedType = DetectedType.NONE,
                detectedLabel = "${words.size} words entered (need 12 or 24)"
            ) }
            return
        }

        _uiState.update { it.copy(detectedType = DetectedType.NONE, detectedLabel = "") }
    }

    private val isSeedPhrase: Boolean
        get() = _uiState.value.detectedType == DetectedType.SEED_12 || _uiState.value.detectedType == DetectedType.SEED_24

    /**
     * Recompute the fingerprint when seed phrase or passphrase changes.
     */
    private fun updateFingerprint() {
        if (!isSeedPhrase) {
            _uiState.update { it.copy(fingerprintBytes = null, masterFingerprintBytes = null) }
            return
        }

        val state = _uiState.value
        val words = state.input.trim().split("\\s+".toRegex())

        try {
            val mnemonicObj = Mnemonic.fromString(words.joinToString(" "))
            val network = Network.BITCOIN
            val secretKey = DescriptorSecretKey(network, mnemonicObj, state.passphrase)
            val descriptor = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
            val descriptorStr = descriptor.toString()

            val masterFp = CreateWalletViewModel.extractMasterFingerprint(descriptorStr)
            if (masterFp != null) {
                val fpBytes = CreateWalletViewModel.computeFingerprint(masterFp, state.passphrase)
                _uiState.update { it.copy(
                    fingerprintBytes = fpBytes.sliceArray(0 until 8),
                    masterFingerprintBytes = masterFp
                ) }
            } else {
                _uiState.update { it.copy(fingerprintBytes = null, masterFingerprintBytes = null) }
            }
        } catch (_: Exception) {
            _uiState.update { it.copy(fingerprintBytes = null, masterFingerprintBytes = null) }
        }
    }

    fun importWallet(onImported: (String) -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val walletData = when (state.detectedType) {
                    DetectedType.SEED_12, DetectedType.SEED_24 -> {
                        val words = state.input.trim().split("\\s+".toRegex())
                        // Validate BIP39
                        try {
                            Mnemonic.fromString(words.joinToString(" "))
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
                    DetectedType.XPUB_WATCH_ONLY, DetectedType.DESCRIPTOR -> {
                        bitcoinRepository.importWatchOnly(
                            name = state.walletName.ifBlank { "Watch-only Wallet" },
                            descriptor = state.input.trim()
                        )
                    }
                    DetectedType.PRIVATE_DESCRIPTOR -> {
                        // Private descriptor — import as full wallet via descriptor
                        bitcoinRepository.importWatchOnly(
                            name = state.walletName.ifBlank { "Imported Wallet" },
                            descriptor = state.input.trim()
                        )
                    }
                    DetectedType.NONE -> {
                        _uiState.update { it.copy(isLoading = false, error = "Please enter a valid seed phrase, xpub, zpub, or descriptor") }
                        return@launch
                    }
                }
                onImported(walletData.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
