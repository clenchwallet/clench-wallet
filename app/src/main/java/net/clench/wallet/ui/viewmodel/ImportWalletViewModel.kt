package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.components.HardwareWalletQrPayloadDecoder
import net.clench.wallet.ui.components.MultisigWalletConfigParser
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.json.JSONObject
import com.sparrowwallet.hummingbird.URDecoder
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ImportWalletViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
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
        val hardwareDeviceType: String? = null,  // HardwareWalletType.name when in HW wallet mode
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
                passphrase == other.passphrase && hardwareDeviceType == other.hardwareDeviceType &&
                isLoading == other.isLoading &&
                error == other.error && fingerprintBytes.contentEquals(other.fingerprintBytes) &&
                masterFingerprintBytes.contentEquals(other.masterFingerprintBytes) &&
                detectedType == other.detectedType && detectedLabel == other.detectedLabel
        }
        override fun hashCode(): Int {
            var result = walletName.hashCode()
            // Do not derive persistent hash values from seed/private-descriptor/passphrase text.
            // Unequal states may intentionally collide; StateFlow equality still detects edits.
            result = 31 * result + (hardwareDeviceType?.hashCode() ?: 0)
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

    fun setHardwareDeviceType(deviceType: String?) = _uiState.update { it.copy(hardwareDeviceType = deviceType) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun setInput(text: String) {
        val normalized = normalizeHardwareExport(text)
        val inferredName = inferWalletNameFromImport(text)
        _uiState.update {
            it.copy(
                input = normalized,
                error = null,
                walletName = if (it.walletName.isBlank() && !inferredName.isNullOrBlank()) inferredName else it.walletName
            )
        }
        detectInputType()
        updateFingerprint()
    }

    fun setPassphrase(pass: String) {
        _uiState.update { it.copy(passphrase = pass) }
        updateFingerprint()
    }

    private fun normalizeHardwareExport(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("ur:")) {
            val decoded = runCatching {
                HardwareWalletQrPayloadDecoder.decodeUrPayload(URDecoder.decode(lower))
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }

        MultisigWalletConfigParser.parse(trimmed)?.let { return it }

        if (!trimmed.startsWith("{")) return trimmed

        return runCatching {
            val root = JSONObject(trimmed)
            val descriptor = root.optString("descriptor")
                .ifBlank { root.optString("recv_descriptor") }
                .ifBlank { root.optString("output_descriptor") }
                .ifBlank { root.optString("receive_descriptor") }
                .ifBlank { root.optString("external_descriptor") }
                .takeIf { it.isNotBlank() }
            if (descriptor != null) {
                MultisigWalletConfigParser.parse(descriptor)?.let { return@runCatching it }
            }
            val candidates = listOf(
                "p2wpkh", "bip84", "bip84_p2wpkh", "native_segwit",
                "p2sh_p2wpkh", "p2sh-p2wpkh", "bip49",
                "p2wsh", "bip48", "bip48_2", "p2sh_p2wsh", "p2sh-p2wsh"
            )
            for (key in candidates) {
                val obj = root.optJSONObject(key) ?: continue
                val normalized = xpubWithOriginFromJsonObject(obj, root)
                if (normalized != null) return@runCatching normalized
            }
            xpubWithOriginFromJsonObject(root, root) ?: trimmed
        }.getOrDefault(trimmed)
    }

    private fun xpubWithOriginFromJsonObject(obj: JSONObject, root: JSONObject): String? {
        val xpub = obj.optString("xpub")
            .ifBlank { obj.optString("zpub") }
            .ifBlank { obj.optString("ypub") }
            .ifBlank { obj.optString("pub") }
            .ifBlank { obj.optString("key") }
            .takeIf { it.isNotBlank() }
            ?: return null
        val xfp = obj.optString("xfp")
            .ifBlank { obj.optString("fingerprint") }
            .ifBlank { root.optString("xfp") }
            .ifBlank { root.optString("fingerprint") }
        val deriv = obj.optString("deriv")
            .ifBlank { obj.optString("derivation") }
            .ifBlank { obj.optString("path") }
        return if (xfp.isNotBlank() && deriv.isNotBlank()) {
            "[${xfp.removePrefix("0x").uppercase()}/${deriv.removePrefix("m/")}]$xpub"
        } else xpub
    }

    private fun inferWalletNameFromImport(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val lineName = trimmed
            .lineSequence()
            .map { it.trim() }
            .firstNotNullOfOrNull { line ->
                Regex("""(?i)^(?:name|wallet name|label)\s*:\s*(.+)$""")
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.trim('"', '\'')
                    ?.takeIf { it.isNotBlank() }
            }
        if (!lineName.isNullOrBlank()) return lineName.take(64)

        if (trimmed.startsWith("{")) {
            runCatching {
                val root = JSONObject(trimmed)
                root.optString("name")
                    .ifBlank { root.optString("walletName") }
                    .ifBlank { root.optString("label") }
                    .trim()
                    .trim('"', '\'')
                    .takeIf { it.isNotBlank() }
                    ?.take(64)
            }.getOrNull()?.let { return it }

            Regex("""(?is)["'](?:name|walletName|label)["']\s*:\s*["']([^"']+)["']""")
                .find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.take(64)
                ?.let { return it }
        }

        return null
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
        if (lower.startsWith("xprv") || lower.startsWith("zprv") || lower.startsWith("tprv")) {
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

        var mnemonicObj: Mnemonic? = null
        var secretKey: DescriptorSecretKey? = null
        var descriptor: Descriptor? = null
        try {
            val parsedMnemonic = Mnemonic.fromString(words.joinToString(" "))
            mnemonicObj = parsedMnemonic
            val network = Network.BITCOIN
            val derivedSecretKey = DescriptorSecretKey(network, parsedMnemonic, state.passphrase)
            secretKey = derivedSecretKey
            val derivedDescriptor = Descriptor.newBip84(derivedSecretKey, KeychainKind.EXTERNAL, network)
            descriptor = derivedDescriptor
            val descriptorStr = derivedDescriptor.toString()

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
        } finally {
            try { descriptor?.close() } catch (_: Exception) {}
            try { secretKey?.destroy() } catch (_: Exception) {}
            try { mnemonicObj?.destroy() } catch (_: Exception) {}
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
                            Mnemonic.fromString(words.joinToString(" ")).destroy()
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
                            descriptor = state.input.trim(),
                            deviceType = state.hardwareDeviceType
                        )
                    }
                    DetectedType.PRIVATE_DESCRIPTOR -> {
                        bitcoinRepository.importPrivateDescriptor(
                            name = state.walletName.ifBlank { "Imported Wallet" },
                            descriptor = state.input.trim()
                        )
                    }
                    DetectedType.NONE -> {
                        _uiState.update { it.copy(isLoading = false, error = "Please enter a valid seed phrase, xpub, descriptor, or multisig config") }
                        return@launch
                    }
                }
                if (!settingsManager.isOfflineMode()) {
                    try {
                        bitcoinRepository.syncWallet(walletData.id, settingsManager.loadElectrumConfig())
                    } catch (_: Exception) {
                        // Import succeeded. Home will surface any sync problem and allow retry.
                    }
                }
                _uiState.update {
                    it.copy(
                        input = "",
                        passphrase = "",
                        fingerprintBytes = null,
                        masterFingerprintBytes = null,
                        isLoading = false
                    )
                }
                onImported(walletData.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
