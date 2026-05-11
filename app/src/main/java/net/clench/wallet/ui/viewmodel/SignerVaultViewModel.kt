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
import net.clench.wallet.domain.model.SignerAccountKeyParser
import net.clench.wallet.ui.util.shouldRethrowForUiBoundary
import net.clench.wallet.ui.util.walletRuntimeMessage
import javax.inject.Inject

@HiltViewModel
class SignerVaultViewModel @Inject constructor(
    private val savedSignerDao: SavedSignerDao,
    private val settingsManager: SettingsManager
) : ViewModel() {

    data class UiState(
        val savedSigners: List<SavedSignerEntity> = emptyList(),
        val isLoading: Boolean = false,
        val label: String = "",
        val publicKey: String = "",
        val fingerprint: String = "",
        val derivationPath: String = SignerAccountKeyParser.expectedMultisigPath(false),
        val deviceType: String = "",
        val error: String? = null,
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(
        UiState(
            derivationPath = SignerAccountKeyParser.expectedMultisigPath(settingsManager.isTestnet())
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

    fun saveManualSigner() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val isTestnet = settingsManager.isTestnet()
                val parsed = SignerAccountKeyParser.parse(
                    raw = state.publicKey,
                    fallbackFingerprint = state.fingerprint,
                    fallbackDerivationPath = state.derivationPath
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
                SignerAccountKeyParser.validationError(parsed.keyWithOrigin, isTestnet)?.let { error ->
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
                    scriptType = SignerAccountKeyParser.SCRIPT_MULTISIG_NATIVE_SEGWIT,
                    deviceType = state.deviceType.trim().ifBlank { null },
                    source = "manual",
                    verified = false,
                    createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                    updatedAtEpochMs = now
                )
                withContext(Dispatchers.IO) { savedSignerDao.upsert(entity) }
                _uiState.update {
                    it.copy(
                        label = "",
                        publicKey = "",
                        fingerprint = "",
                        derivationPath = SignerAccountKeyParser.expectedMultisigPath(isTestnet),
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
