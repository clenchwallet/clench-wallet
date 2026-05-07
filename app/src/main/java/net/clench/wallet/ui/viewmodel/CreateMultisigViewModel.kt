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
import net.clench.wallet.data.local.dao.WalletKeystoreMetadataDao
import net.clench.wallet.data.local.entity.WalletKeystoreMetadataEntity
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.util.shouldRethrowForUiBoundary
import net.clench.wallet.ui.util.walletRuntimeMessage
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CreateMultisigViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val walletKeystoreMetadataDao: WalletKeystoreMetadataDao
) : ViewModel() {

    data class SignerInfo(
        val label: String = "",
        val xpub: String = "",
        val fingerprint: String = "",
        val derivationPath: String = "m/48'/0'/0'/2'",
        val deviceType: String? = null,
        val isLocalKey: Boolean = false
    )

    data class UiState(
        val threshold: Int = 2,
        val totalSigners: Int = 3,
        val signers: List<SignerInfo> = emptyList(),
        val walletName: String = "",
        val currentStep: Int = 1,
        val isCreating: Boolean = false,
        val error: String? = null,
        val warning: String? = null,
        val createdWalletId: String? = null,
        val showQrScanner: Boolean = false,
        val qrScannerTargetIndex: Int = -1
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Initialize signers list based on default totalSigners
        initializeSigners()
    }

    private fun initializeSigners() {
        val total = _uiState.value.totalSigners
        val isTestnet = settingsManager.isTestnet()
        val defaultPath = if (isTestnet) "m/48'/1'/0'/2'" else "m/48'/0'/0'/2'"
        val signers = (1..total).map { i ->
            SignerInfo(
                label = "Signer $i",
                derivationPath = defaultPath
            )
        }
        _uiState.update { it.copy(signers = signers) }
    }

    fun setThreshold(threshold: Int) {
        _uiState.update {
            val newThreshold = threshold.coerceIn(1, it.totalSigners)
            it.copy(threshold = newThreshold)
        }
    }

    fun setTotalSigners(total: Int) {
        val newTotal = total.coerceIn(2, 7)
        _uiState.update {
            val newThreshold = it.threshold.coerceAtMost(newTotal)
            it.copy(totalSigners = newTotal, threshold = newThreshold)
        }
        initializeSigners()
    }

    fun setPreset(m: Int, n: Int) {
        _uiState.update { it.copy(threshold = m, totalSigners = n) }
        initializeSigners()
    }

    fun setWalletName(name: String) {
        _uiState.update { it.copy(walletName = name) }
    }

    fun updateSigner(index: Int, label: String? = null, xpub: String? = null) {
        _uiState.update { state ->
            val signers = state.signers.toMutableList()
            if (index in signers.indices) {
                val current = signers[index]
                val newXpub = xpub?.let { normalizeHardwareExportForMultisig(it) } ?: current.xpub
                val newFingerprint = if (xpub != null) extractFingerprint(newXpub) else current.fingerprint
                signers[index] = current.copy(
                    label = label ?: current.label,
                    xpub = newXpub,
                    fingerprint = newFingerprint
                )
            }
            state.copy(signers = signers)
        }
    }

    fun setSignerDevice(index: Int, device: HardwareWalletType?) {
        _uiState.update { state ->
            val signers = state.signers.toMutableList()
            if (index in signers.indices) {
                val current = signers[index]
                val defaultLabel = "Signer ${index + 1}"
                val nextLabel = if (device != null && (current.label.isBlank() || current.label == defaultLabel)) {
                    device.displayName
                } else {
                    current.label
                }
                signers[index] = current.copy(
                    label = nextLabel,
                    deviceType = device?.name
                )
            }
            state.copy(signers = signers)
        }
    }

    fun removeSigner(index: Int) {
        _uiState.update { state ->
            val signers = state.signers.toMutableList()
            if (index in signers.indices && signers.size > 2) {
                signers.removeAt(index)
                val newTotal = signers.size
                val newThreshold = state.threshold.coerceAtMost(newTotal)
                state.copy(
                    signers = signers,
                    totalSigners = newTotal,
                    threshold = newThreshold
                )
            } else state
        }
    }

    fun showQrScanner(signerIndex: Int) {
        _uiState.update { it.copy(showQrScanner = true, qrScannerTargetIndex = signerIndex) }
    }

    fun hideQrScanner() {
        _uiState.update { it.copy(showQrScanner = false, qrScannerTargetIndex = -1) }
    }

    fun onQrScanned(result: String) {
        try {
            val index = _uiState.value.qrScannerTargetIndex
            if (index >= 0) {
                updateSigner(index, xpub = result.trim())
            }
            hideQrScanner()
        } catch (t: Throwable) {
            if (t.shouldRethrowForUiBoundary()) throw t
            _uiState.update {
                it.copy(
                    showQrScanner = false,
                    qrScannerTargetIndex = -1,
                    error = "QR import failed: ${t.walletRuntimeMessage("reading the cosigner QR")}"
                )
            }
        }
    }

    fun nextStep() {
        _uiState.update { state ->
            val next = (state.currentStep + 1).coerceAtMost(3)
            state.copy(currentStep = next, error = null, warning = null)
        }
    }

    fun previousStep() {
        _uiState.update { state ->
            val prev = (state.currentStep - 1).coerceAtLeast(1)
            state.copy(currentStep = prev, error = null, warning = null)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun clearWarning() {
        _uiState.update { it.copy(warning = null) }
    }

    /**
     * Build the full multisig descriptor string for display in the review step.
     */
    fun buildDescriptorPreview(): String {
        val state = _uiState.value
        val keys = state.signers.joinToString(",") { signer ->
            val xpub = signer.xpub.trim()
            if (xpub.endsWith("/0/*") || xpub.endsWith("/1/*")) {
                xpub.replace("/1/*", "/0/*")
            } else {
                "$xpub/0/*"
            }
        }
        return "wsh(sortedmulti(${state.threshold},$keys))"
    }

    private fun normalizeHardwareExportForMultisig(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return trimmed

        return runCatching {
            val root = JSONObject(trimmed)
            val candidates = listOf(
                "p2wsh", "bip48", "bip48_2", "p2sh_p2wsh", "p2sh-p2wsh",
                "p2wpkh", "bip84", "native_segwit"
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
            .ifBlank { obj.optString("Zpub") }
            .ifBlank { obj.optString("Ypub") }
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

    private fun canonicalSignerKey(raw: String): String {
        val trimmed = raw.trim()
        val key = if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket >= 0) trimmed.substring(closeBracket + 1) else trimmed
        } else trimmed
        return key
            .removeSuffix("/0/*")
            .removeSuffix("/1/*")
            .lowercase()
    }

    companion object {
        // Valid public extended key prefixes for Bitcoin multisig. Private extended keys are
        // intentionally rejected so this watch-only flow never persists signer secrets.
        private val VALID_KEY_PREFIXES = listOf(
            "xpub", "ypub", "zpub", "tpub",
            "Zpub", "Ypub", "Vpub", "Upub"
        )
    }

    /**
     * Validate current step before allowing navigation to next.
     */
    fun validateCurrentStep(): Boolean {
        val state = _uiState.value
        _uiState.update { it.copy(warning = null) }

        return when (state.currentStep) {
            1 -> {
                // Config step is always valid (constrained by UI)
                true
            }
            2 -> {
                // Fix 7: Validate each signer's key format
                var hasWarning = false
                state.signers.forEachIndexed { index, signer ->
                    val xpub = signer.xpub.trim()
                    if (xpub.isBlank()) {
                        _uiState.update { it.copy(error = "Signer ${index + 1}: extended public key is required") }
                        return false
                    }

                    // Check if key has origin info [fingerprint/path]
                    val hasOrigin = xpub.startsWith("[")
                    val originText: String?
                    val keyPart = if (hasOrigin) {
                        val closeBracket = xpub.indexOf(']')
                        if (closeBracket < 0) {
                            _uiState.update { it.copy(error = "Signer ${index + 1}: malformed key origin — missing closing ']'") }
                            return false
                        }
                        originText = xpub.substring(1, closeBracket)
                        val originError = validateOriginPath(originText, settingsManager.isTestnet())
                        if (originError != null) {
                            _uiState.update { it.copy(error = "Signer ${index + 1}: $originError") }
                            return false
                        }
                        xpub.substring(closeBracket + 1)
                    } else {
                        originText = null
                        xpub
                    }

                    // Require public extended keys, not full descriptors or private keys.
                    val isDescriptor = xpub.startsWith("wsh(") || xpub.startsWith("wpkh(") || xpub.startsWith("sh(")
                    if (isDescriptor) {
                        _uiState.update { it.copy(error = "Signer ${index + 1}: paste the signer public key, not a full descriptor") }
                        return false
                    }
                    if (keyPart.startsWith("xprv") || keyPart.startsWith("yprv") ||
                        keyPart.startsWith("zprv") || keyPart.startsWith("tprv")) {
                        _uiState.update { it.copy(error = "Signer ${index + 1}: private extended keys are not allowed") }
                        return false
                    }
                    val hasValidPrefix = VALID_KEY_PREFIXES.any { keyPart.startsWith(it) }
                    if (!hasValidPrefix) {
                        _uiState.update {
                            it.copy(error = "Signer ${index + 1}: unrecognized key format. " +
                                "Expected xpub, Zpub, tpub, or similar public extended key.")
                        }
                        return false
                    }
                    val networkError = validateSignerNetwork(keyPart, settingsManager.isTestnet())
                    if (networkError != null) {
                        _uiState.update { it.copy(error = "Signer ${index + 1}: $networkError") }
                        return false
                    }

                    // Warn (not block) if key origin is missing — HW wallets need it to verify derivation
                    if (!hasOrigin) {
                        hasWarning = true
                        _uiState.update {
                            it.copy(warning = "Signer ${index + 1}: no key origin [fingerprint/path]. " +
                                "Hardware wallets may not be able to verify this signer's derivation path.")
                        }
                    }
                }
                val canonicalKeys = state.signers.map { canonicalSignerKey(it.xpub) }
                if (canonicalKeys.distinct().size != canonicalKeys.size) {
                    _uiState.update { it.copy(error = "Duplicate cosigner key detected. Each signer must be unique.") }
                    return false
                }
                true
            }
            3 -> {
                // Wallet name required
                if (state.walletName.isBlank()) {
                    _uiState.update { it.copy(error = "Wallet name is required") }
                    return false
                }
                true
            }
            else -> true
        }
    }

    fun createMultisigWallet(onCreated: (String) -> Unit) {
        if (!validateCurrentStep()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                val state = _uiState.value

                // Build xpub list with origin info
                val signerXpubs = state.signers.map { it.xpub.trim() }

                val walletData = bitcoinRepository.createMultisigWallet(
                    name = state.walletName.ifBlank { "${state.threshold}-of-${state.totalSigners} Multisig" },
                    threshold = state.threshold,
                    signerXpubs = signerXpubs
                )
                persistSignerMetadata(walletData.id, state.signers)

                _uiState.update {
                    it.copy(isCreating = false, createdWalletId = walletData.id)
                }
                onCreated(walletData.id)
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("CreateMultisig", "createMultisigWallet failed: ${t.message}", t)
                _uiState.update {
                    it.copy(isCreating = false, error = t.walletRuntimeMessage("creating the multisig wallet"))
                }
            }
        }
    }

    private suspend fun persistSignerMetadata(walletId: String, signers: List<SignerInfo>) {
        withContext(Dispatchers.IO) {
            signers.forEachIndexed { index, signer ->
                val parsed = parseSignerKeyForMetadata(signer.xpub) ?: return@forEachIndexed
                val label = signer.label.trim().ifBlank { "Signer ${index + 1}" }
                walletKeystoreMetadataDao.upsert(
                    WalletKeystoreMetadataEntity(
                        walletId = walletId,
                        keyId = stableKeystoreId(parsed.fingerprint, parsed.derivationPath, parsed.xpub),
                        label = label.take(64),
                        preferredHardwareWallet = signer.deviceType,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private data class ParsedSignerKey(
        val fingerprint: String?,
        val derivationPath: String?,
        val xpub: String
    )

    private fun parseSignerKeyForMetadata(raw: String): ParsedSignerKey? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val originMatch = Regex("""^\[([0-9a-fA-F]{8})(?:/([^\]]+))?\](.+)$""").find(trimmed)
        val fingerprint = originMatch?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
        val originPath = originMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        val keyWithPath = originMatch?.groupValues?.getOrNull(3) ?: trimmed
        val xpub = keyWithPath
            .removeSuffix("/0/*")
            .removeSuffix("/1/*")
            .removeSuffix("/**")
            .trim()
            .ifBlank { return null }
        return ParsedSignerKey(fingerprint, originPath, xpub)
    }

    private fun stableKeystoreId(
        fingerprint: String?,
        derivationPath: String?,
        xpub: String
    ): String {
        val input = listOf(
            fingerprint.orEmpty().uppercase(Locale.US),
            derivationPath.orEmpty().lowercase(Locale.US),
            xpub.trim()
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private fun validateSignerNetwork(keyPart: String, isTestnet: Boolean): String? {
        val key = keyPart.removeSuffix("/0/*").removeSuffix("/1/*")
        val isMainnetKey = listOf("xpub", "ypub", "zpub", "Ypub", "Zpub").any { key.startsWith(it) }
        val isTestnetKey = listOf("tpub", "upub", "vpub", "Upub", "Vpub").any { key.startsWith(it) }
        return when {
            isTestnet && isMainnetKey -> "mainnet public key used while Clench is set to testnet"
            !isTestnet && isTestnetKey -> "testnet public key used while Clench is set to mainnet"
            else -> null
        }
    }

    private fun validateOriginPath(origin: String, isTestnet: Boolean): String? {
        val parts = origin.split('/')
        if (parts.isEmpty() || !Regex("^[0-9a-fA-F]{8}$").matches(parts[0])) {
            return "key origin must start with an 8-character master fingerprint"
        }
        val pathParts = parts.drop(1).let { if (it.firstOrNull() == "m") it.drop(1) else it }
        if (pathParts.size >= 2) {
            val coinType = pathParts[1].removeHardenedSuffix()
            val expected = if (isTestnet) "1" else "0"
            if (coinType != expected) {
                return "origin path coin type $coinType does not match ${if (isTestnet) "testnet" else "mainnet"}"
            }
        }
        return null
    }

    private fun String.removeHardenedSuffix(): String {
        return removeSuffix("'").removeSuffix("h").removeSuffix("H")
    }

    /**
     * Extract master fingerprint from xpub origin info.
     * Handles formats like: [73c5da0a/48'/0'/0'/2']xpub...
     */
    private fun extractFingerprint(xpub: String): String {
        val match = Regex("\\[([0-9a-fA-F]{8})/").find(xpub)
        return match?.groupValues?.get(1) ?: ""
    }
}
