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
import net.clench.wallet.data.local.dao.SavedSignerDao
import net.clench.wallet.data.local.dao.WalletKeystoreMetadataDao
import net.clench.wallet.data.local.entity.SavedSignerEntity
import net.clench.wallet.data.local.entity.WalletKeystoreMetadataEntity
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.domain.model.PhoneSigner
import net.clench.wallet.domain.model.SignerAccountKeyParser
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.domain.repository.MultisigPhoneSignerSecret
import net.clench.wallet.ui.util.shouldRethrowForUiBoundary
import net.clench.wallet.ui.util.walletRuntimeMessage
import javax.inject.Inject

@HiltViewModel
class CreateMultisigViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager,
    private val savedSignerDao: SavedSignerDao,
    private val walletKeystoreMetadataDao: WalletKeystoreMetadataDao
) : ViewModel() {

    data class SignerInfo(
        val label: String = "",
        val xpub: String = "",
        val fingerprint: String = "",
        val derivationPath: String = "m/48'/0'/0'/2'",
        val deviceType: String? = null,
        val isLocalKey: Boolean = false,
        val phoneSignerSeedWords: List<String> = emptyList(),
        val phoneSignerAccountXprv: String? = null,
        val phoneSignerBackedUp: Boolean = false,
        val savedSignerId: String? = null
    )

    data class SavedSignerOption(
        val id: String,
        val label: String,
        val xpub: String,
        val fingerprint: String?,
        val derivationPath: String,
        val deviceType: String?,
        val network: String
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
        val qrScannerTargetIndex: Int = -1,
        val showPhoneSignerOptions: Boolean = false,
        val generatingPhoneSignerIndex: Int? = null,
        val savedSignerOptions: List<SavedSignerOption> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Initialize signers list based on default totalSigners
        _uiState.update { it.copy(showPhoneSignerOptions = true) }
        initializeSigners()
        loadSavedSigners()
    }

    private fun initializeSigners() {
        val total = _uiState.value.totalSigners
        val isTestnet = settingsManager.isTestnet()
        val defaultPath = SignerAccountKeyParser.expectedMultisigPath(isTestnet)
        val signers = (1..total).map { i ->
            SignerInfo(
                label = "Signer $i",
                derivationPath = defaultPath
            )
        }
        _uiState.update { it.copy(signers = signers) }
    }

    fun loadSavedSigners() {
        viewModelScope.launch {
            val network = if (settingsManager.isTestnet()) "testnet" else "mainnet"
            val options = withContext(Dispatchers.IO) {
                savedSignerDao.getForNetworkAndScript(
                    network = network,
                    scriptType = SignerAccountKeyParser.SCRIPT_MULTISIG_NATIVE_SEGWIT
                )
            }.map {
                SavedSignerOption(
                    id = it.id,
                    label = it.label,
                    xpub = it.xpub,
                    fingerprint = it.fingerprint,
                    derivationPath = it.derivationPath,
                    deviceType = it.deviceType,
                    network = it.network
                )
            }
            _uiState.update { it.copy(savedSignerOptions = options) }
        }
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
                val parsed = xpub?.let {
                    SignerAccountKeyParser.parse(
                        raw = it,
                        fallbackDerivationPath = current.derivationPath
                    )
                }
                val newXpub = parsed?.keyWithOrigin ?: xpub?.let { SignerAccountKeyParser.normalizeHardwareExportForMultisig(it) } ?: current.xpub
                val newFingerprint = if (xpub != null) parsed?.fingerprint.orEmpty() else current.fingerprint
                val newDerivationPath = if (xpub != null) parsed?.derivationPath ?: current.derivationPath else current.derivationPath
                signers[index] = current.copy(
                    label = label ?: current.label,
                    xpub = newXpub,
                    fingerprint = newFingerprint,
                    derivationPath = newDerivationPath,
                    isLocalKey = if (xpub != null) false else current.isLocalKey,
                    phoneSignerSeedWords = if (xpub != null) emptyList() else current.phoneSignerSeedWords,
                    phoneSignerAccountXprv = if (xpub != null) null else current.phoneSignerAccountXprv,
                    phoneSignerBackedUp = if (xpub != null) false else current.phoneSignerBackedUp,
                    deviceType = if (xpub != null) current.deviceType?.takeUnless { it == PhoneSigner.DEVICE_TYPE } else current.deviceType,
                    savedSignerId = null
                )
            }
            state.copy(signers = signers)
        }
    }

    fun updateSignerMetadata(index: Int, fingerprint: String? = null, derivationPath: String? = null) {
        _uiState.update { state ->
            val signers = state.signers.toMutableList()
            if (index in signers.indices) {
                val current = signers[index]
                signers[index] = current.copy(
                    fingerprint = fingerprint?.let { SignerAccountKeyParser.normalizeFingerprint(it).orEmpty() } ?: current.fingerprint,
                    derivationPath = derivationPath?.let { SignerAccountKeyParser.normalizeDerivationPath(it) ?: it } ?: current.derivationPath,
                    savedSignerId = null
                )
            }
            state.copy(signers = signers)
        }
    }

    fun applySavedSigner(index: Int, signerId: String) {
        _uiState.update { state ->
            val option = state.savedSignerOptions.find { it.id == signerId } ?: return@update state
            val signers = state.signers.toMutableList()
            if (index in signers.indices) {
                val current = signers[index]
                signers[index] = current.copy(
                    label = option.label,
                    xpub = option.xpub,
                    fingerprint = option.fingerprint.orEmpty(),
                    derivationPath = option.derivationPath,
                    deviceType = option.deviceType,
                    isLocalKey = false,
                    phoneSignerSeedWords = emptyList(),
                    phoneSignerAccountXprv = null,
                    phoneSignerBackedUp = false,
                    savedSignerId = option.id
                )
            }
            state.copy(signers = signers, warning = "Loaded saved signer ${option.label}", error = null)
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
                    deviceType = device?.name,
                    isLocalKey = false,
                    phoneSignerSeedWords = emptyList(),
                    phoneSignerAccountXprv = null,
                    phoneSignerBackedUp = false
                )
            }
            state.copy(signers = signers)
        }
    }

    fun generatePhoneSigner(index: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(generatingPhoneSignerIndex = index, error = null) }
            try {
                val generated = bitcoinRepository.generateMultisigPhoneSigner()
                _uiState.update { state ->
                    val signers = state.signers.toMutableList()
                    if (index in signers.indices) {
                        val defaultLabel = "Signer ${index + 1}"
                        val current = signers[index]
                        signers[index] = current.copy(
                            label = if (current.label.isBlank() || current.label == defaultLabel) {
                                "${PhoneSigner.DISPLAY_NAME} ${index + 1}"
                            } else current.label,
                            xpub = generated.xpubWithOrigin,
                            fingerprint = generated.fingerprint,
                            derivationPath = generated.derivationPath,
                            deviceType = PhoneSigner.DEVICE_TYPE,
                            isLocalKey = true,
                            phoneSignerSeedWords = generated.mnemonicWords,
                            phoneSignerAccountXprv = generated.accountXprvWithOrigin,
                            phoneSignerBackedUp = false,
                            savedSignerId = null
                        )
                    }
                    state.copy(signers = signers, generatingPhoneSignerIndex = null)
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update {
                    it.copy(
                        generatingPhoneSignerIndex = null,
                        error = "Could not generate phone signer: ${t.walletRuntimeMessage("creating the multisig phone signer")}"
                    )
                }
            }
        }
    }

    fun setPhoneSignerBackedUp(index: Int, backedUp: Boolean) {
        _uiState.update { state ->
            val signers = state.signers.toMutableList()
            if (index in signers.indices && signers[index].isLocalKey) {
                signers[index] = signers[index].copy(phoneSignerBackedUp = backedUp)
            }
            state.copy(signers = signers)
        }
    }

    fun clearPhoneSigner(index: Int) {
        _uiState.update { state ->
            val signers = state.signers.toMutableList()
            if (index in signers.indices) {
                val defaultPath = signers[index].derivationPath
                signers[index] = SignerInfo(
                    label = "Signer ${index + 1}",
                    derivationPath = defaultPath
                )
            }
            state.copy(signers = signers, error = null, warning = null)
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
            val xpub = effectiveSignerXpub(signer)
            if (xpub.endsWith("/0/*") || xpub.endsWith("/1/*")) {
                xpub.replace("/1/*", "/0/*")
            } else {
                "$xpub/0/*"
            }
        }
        return "wsh(sortedmulti(${state.threshold},$keys))"
    }

    private fun effectiveSignerXpub(signer: SignerInfo): String {
        val parsed = SignerAccountKeyParser.parse(
            raw = signer.xpub,
            fallbackFingerprint = signer.fingerprint,
            fallbackDerivationPath = signer.derivationPath
        )
        return parsed?.keyWithOrigin ?: signer.xpub.trim()
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
                val localSignerCount = state.signers.count { it.isLocalKey }
                if (localSignerCount >= state.threshold) {
                    _uiState.update {
                        it.copy(error = "Phone signers must be fewer than the required signature threshold. Lower the number of phone signers or increase the threshold.")
                    }
                    return false
                }
                val unbackedPhoneSigner = state.signers.indexOfFirst { it.isLocalKey && !it.phoneSignerBackedUp }
                if (unbackedPhoneSigner >= 0) {
                    _uiState.update { it.copy(error = "Signer ${unbackedPhoneSigner + 1}: back up the phone signer seed phrase before continuing") }
                    return false
                }
                state.signers.forEachIndexed { index, signer ->
                    val xpub = effectiveSignerXpub(signer)
                    if (xpub.isBlank()) {
                        _uiState.update { it.copy(error = "Signer ${index + 1}: extended public key is required") }
                        return false
                    }

                    SignerAccountKeyParser.validationError(xpub, settingsManager.isTestnet())?.let { error ->
                        _uiState.update { it.copy(error = "Signer ${index + 1}: $error") }
                        return false
                    }
                }
                val canonicalKeys = state.signers.map { canonicalSignerKey(effectiveSignerXpub(it)) }
                if (canonicalKeys.distinct().size != canonicalKeys.size) {
                    _uiState.update { it.copy(error = "Duplicate cosigner key detected. Each signer must be unique.") }
                    return false
                }
                when {
                    localSignerCount >= 2 -> _uiState.update {
                        it.copy(warning = "$localSignerCount phone signers will be encrypted on this phone. Because that is more than one signer, keep the wallet threshold higher than the phone signer count and back up each seed separately.")
                    }
                    localSignerCount == 1 -> _uiState.update {
                        it.copy(warning = "One phone signer will be encrypted on this phone. Keep at least one other signer off this device before funding.")
                    }
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
                val signerXpubs = state.signers.map { effectiveSignerXpub(it) }
                val localSignerSecrets = state.signers.mapIndexedNotNull { index, signer ->
                    val accountXprv = signer.phoneSignerAccountXprv
                    if (!signer.isLocalKey || accountXprv.isNullOrBlank()) null
                    else index to MultisigPhoneSignerSecret(
                        mnemonicWords = signer.phoneSignerSeedWords,
                        accountXprvWithOrigin = accountXprv
                    )
                }.toMap()

                val walletData = bitcoinRepository.createMultisigWallet(
                    name = state.walletName.ifBlank { "${state.threshold}-of-${state.totalSigners} Multisig" },
                    threshold = state.threshold,
                    signerXpubs = signerXpubs,
                    localSignerSecrets = localSignerSecrets
                )
                persistSignerMetadata(walletData.id, state.signers)
                saveSignersToVault(state.signers, source = "multisig_create")
                loadSavedSigners()

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
                val parsed = parseSignerKeyForMetadata(effectiveSignerXpub(signer)) ?: return@forEachIndexed
                val label = signer.label.trim().ifBlank { "Signer ${index + 1}" }
                walletKeystoreMetadataDao.upsert(
                    WalletKeystoreMetadataEntity(
                        walletId = walletId,
                        keyId = stableKeystoreId(parsed.fingerprint, parsed.derivationPath, parsed.xpub),
                        label = label.take(64),
                        preferredHardwareWallet = if (signer.isLocalKey) PhoneSigner.DEVICE_TYPE else signer.deviceType,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun saveSignerToVault(index: Int) {
        viewModelScope.launch {
            try {
                val signer = _uiState.value.signers.getOrNull(index) ?: return@launch
                val saved = saveSignerToVaultInternal(signer, source = "manual_save")
                loadSavedSigners()
                _uiState.update {
                    it.copy(
                        warning = "Saved ${saved.label} to Signer Vault",
                        error = null
                    )
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update { it.copy(error = t.walletRuntimeMessage("saving the signer")) }
            }
        }
    }

    private suspend fun saveSignersToVault(signers: List<SignerInfo>, source: String) {
        withContext(Dispatchers.IO) {
            signers.forEach { signer ->
                if (signer.isLocalKey) return@forEach
                runCatching { saveSignerToVaultInternal(signer, source) }
            }
        }
    }

    private suspend fun saveSignerToVaultInternal(
        signer: SignerInfo,
        source: String
    ): SavedSignerEntity {
        if (signer.isLocalKey) {
            error("Phone signers are wallet-specific right now and cannot be reused from Signer Vault")
        }
        val isTestnet = settingsManager.isTestnet()
        val parsed = SignerAccountKeyParser.parse(
            raw = signer.xpub,
            fallbackFingerprint = signer.fingerprint,
            fallbackDerivationPath = signer.derivationPath
        ) ?: error("Signer public key is empty")
        SignerAccountKeyParser.validationError(parsed.keyWithOrigin, isTestnet)?.let { error(it) }
        val fingerprint = parsed.fingerprint ?: error("Signer master fingerprint is missing")
        val derivationPath = parsed.derivationPath ?: error("Signer derivation path is missing")
        val id = SignerAccountKeyParser.stableId(fingerprint, derivationPath, parsed.xpub)
        val now = System.currentTimeMillis()
        val entity = SavedSignerEntity(
            id = id,
            label = signer.label.trim().ifBlank { "Signer $fingerprint" }.take(64),
            xpub = parsed.keyWithOrigin,
            fingerprint = fingerprint,
            derivationPath = derivationPath,
            network = if (isTestnet) "testnet" else "mainnet",
            scriptType = SignerAccountKeyParser.SCRIPT_MULTISIG_NATIVE_SEGWIT,
            deviceType = signer.deviceType,
            source = source,
            verified = signer.deviceType == HardwareWalletType.TAPSIGNER.name,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        val saved = entity.copy(createdAtEpochMs = existingCreatedAt(id) ?: entity.createdAtEpochMs)
        savedSignerDao.upsert(saved)
        return saved
    }

    private suspend fun existingCreatedAt(id: String): Long? {
        val network = if (settingsManager.isTestnet()) "testnet" else "mainnet"
        return savedSignerDao.getForNetworkAndScript(
            network = network,
            scriptType = SignerAccountKeyParser.SCRIPT_MULTISIG_NATIVE_SEGWIT
        ).find { it.id == id }?.createdAtEpochMs
    }

    private data class ParsedSignerKey(
        val fingerprint: String?,
        val derivationPath: String?,
        val xpub: String
    )

    private fun parseSignerKeyForMetadata(raw: String): ParsedSignerKey? {
        val parsed = SignerAccountKeyParser.parse(raw) ?: return null
        return ParsedSignerKey(parsed.fingerprint, parsed.derivationPath, parsed.xpub)
    }

    private fun stableKeystoreId(
        fingerprint: String?,
        derivationPath: String?,
        xpub: String
    ): String {
        return SignerAccountKeyParser.stableId(fingerprint, derivationPath, xpub)
    }
}
