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
import net.clench.wallet.data.local.dao.SavedSignerDao
import net.clench.wallet.data.local.entity.SavedSignerEntity
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.domain.model.SignerAccountKeyParser
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.util.shouldRethrowForUiBoundary
import net.clench.wallet.ui.util.walletRuntimeMessage
import javax.inject.Inject

@HiltViewModel
class SignerVaultViewModel @Inject constructor(
    private val savedSignerDao: SavedSignerDao,
    private val settingsManager: SettingsManager,
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val savedSigners: List<SavedSignerEntity> = emptyList(),
        val isLoading: Boolean = false,
        val isCreatingWallet: Boolean = false,
        val isTestnet: Boolean = false,
        val label: String = "",
        val publicKey: String = "",
        val fingerprint: String = "",
        val derivationPath: String = SignerAccountKeyParser.expectedSingleSigPath(false),
        val scriptType: String = SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT,
        val deviceType: String = "",
        val error: String? = null,
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(
        UiState(
            isTestnet = settingsManager.isTestnet(),
            derivationPath = SignerAccountKeyParser.expectedSingleSigPath(settingsManager.isTestnet())
        )
    )
    val uiState = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val signers = withContext(Dispatchers.IO) { savedSignerDao.getAll() }
                _uiState.update { it.copy(savedSigners = signers, isLoading = false) }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update {
                    it.copy(isLoading = false, error = t.walletRuntimeMessage("loading saved signers"))
                }
            }
        }
    }

    fun setLabel(value: String) = _uiState.update { it.copy(label = value, message = null) }
    fun setPublicKey(value: String) = _uiState.update { it.copy(publicKey = value, message = null) }
    fun setFingerprint(value: String) = _uiState.update { it.copy(fingerprint = value.take(10), message = null) }
    fun setDerivationPath(value: String) = _uiState.update { it.copy(derivationPath = value, message = null) }
    fun setDeviceType(value: String) = _uiState.update { it.copy(deviceType = value.take(40), message = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }
    fun setError(message: String) = _uiState.update { it.copy(error = message, message = null) }

    fun setScriptType(value: String) {
        _uiState.update { state ->
            val oldDefault = SignerAccountKeyParser.expectedPath(state.scriptType, state.isTestnet)
            val newDefault = SignerAccountKeyParser.expectedPath(value, state.isTestnet)
            state.copy(
                scriptType = value,
                derivationPath = if (state.derivationPath.isBlank() || state.derivationPath == oldDefault) {
                    newDefault
                } else {
                    state.derivationPath
                },
                message = null
            )
        }
    }

    fun importSignerText(value: String, label: String? = null, deviceType: String? = null) {
        _uiState.update { state ->
            val parsed = SignerAccountKeyParser.parse(
                raw = value,
                fallbackFingerprint = state.fingerprint,
                fallbackDerivationPath = state.derivationPath,
                scriptType = state.scriptType
            )
            state.copy(
                label = label ?: state.label,
                publicKey = parsed?.keyWithOrigin ?: SignerAccountKeyParser.normalizeHardwareExport(value, state.scriptType),
                fingerprint = parsed?.fingerprint ?: state.fingerprint,
                derivationPath = parsed?.derivationPath ?: state.derivationPath,
                deviceType = deviceType ?: state.deviceType,
                message = "Loaded signer data",
                error = null
            )
        }
    }

    fun saveManualSigner() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val isTestnet = settingsManager.isTestnet()
                val parsed = SignerAccountKeyParser.parse(
                    raw = state.publicKey,
                    fallbackFingerprint = state.fingerprint,
                    fallbackDerivationPath = state.derivationPath,
                    scriptType = state.scriptType
                ) ?: run {
                    _uiState.update { it.copy(error = "Enter a signer public account key") }
                    return@launch
                }
                if (parsed.fingerprint.isNullOrBlank()) {
                    _uiState.update { it.copy(error = "Enter the signer master fingerprint") }
                    return@launch
                }
                if (parsed.derivationPath.isNullOrBlank()) {
                    _uiState.update { it.copy(error = "Enter the signer derivation path") }
                    return@launch
                }
                SignerAccountKeyParser.validationError(
                    key = parsed.keyWithOrigin,
                    isTestnet = isTestnet,
                    scriptType = state.scriptType
                )?.let { error ->
                    _uiState.update { it.copy(error = error) }
                    return@launch
                }
                val now = System.currentTimeMillis()
                val existing = _uiState.value.savedSigners.find {
                    it.id == SignerAccountKeyParser.stableId(parsed.fingerprint, parsed.derivationPath, parsed.xpub)
                }
                val entity = SavedSignerEntity(
                    id = existing?.id ?: SignerAccountKeyParser.stableId(parsed.fingerprint, parsed.derivationPath, parsed.xpub),
                    label = state.label.trim().ifBlank { "Signer ${parsed.fingerprint}" }.take(64),
                    xpub = parsed.keyWithOrigin,
                    fingerprint = parsed.fingerprint,
                    derivationPath = parsed.derivationPath,
                    network = if (isTestnet) "testnet" else "mainnet",
                    scriptType = state.scriptType,
                    deviceType = state.deviceType.trim().ifBlank { null },
                    source = "manual",
                    verified = state.deviceType == HardwareWalletType.TAPSIGNER.name,
                    createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                    updatedAtEpochMs = now
                )
                withContext(Dispatchers.IO) { savedSignerDao.upsert(entity) }
                _uiState.update {
                    it.copy(
                        label = "",
                        publicKey = "",
                        fingerprint = "",
                        derivationPath = SignerAccountKeyParser.expectedPath(state.scriptType, isTestnet),
                        deviceType = "",
                        message = "Saved ${entity.label}",
                        error = null
                    )
                }
                load()
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update { it.copy(error = t.walletRuntimeMessage("saving the signer")) }
            }
        }
    }

    fun createSingleSigWatchOnlyWallet(signerId: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingWallet = true, error = null) }
            try {
                val signer = _uiState.value.savedSigners.find { it.id == signerId }
                    ?: error("Saved signer was not found")
                if (signer.scriptType != SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT) {
                    error("Only single-sig signers can directly create a watch-only wallet")
                }
                val wallet = bitcoinRepository.importWatchOnly(
                    name = signer.label.ifBlank { "Watch-only Wallet" },
                    descriptor = signer.xpub,
                    deviceType = signer.deviceType
                )
                if (!settingsManager.isOfflineMode()) {
                    runCatching { bitcoinRepository.syncWallet(wallet.id, settingsManager.loadElectrumConfig()) }
                }
                _uiState.update { it.copy(isCreatingWallet = false, message = "Created wallet ${wallet.name}") }
                onCreated(wallet.id)
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update {
                    it.copy(
                        isCreatingWallet = false,
                        error = t.walletRuntimeMessage("creating the watch-only wallet")
                    )
                }
            }
        }
    }

    fun deleteSigner(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { savedSignerDao.delete(id) }
                load()
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update { it.copy(error = t.walletRuntimeMessage("deleting the signer")) }
            }
        }
    }
}
