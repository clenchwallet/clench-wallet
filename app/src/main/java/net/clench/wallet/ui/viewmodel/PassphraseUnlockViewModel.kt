package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.repository.BdkBitcoinRepository
import javax.inject.Inject

@HiltViewModel
class PassphraseUnlockViewModel @Inject constructor(
    private val bitcoinRepository: BdkBitcoinRepository
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val walletName: String = "",
        val passphrase: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val isUnlocked: Boolean = false,
        val fingerprintBytes: ByteArray? = null,
        val masterFingerprintBytes: ByteArray? = null,
        val storedIdenticonBytes: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UiState) return false
            return walletId == other.walletId && passphrase == other.passphrase &&
                isLoading == other.isLoading && error == other.error &&
                isUnlocked == other.isUnlocked &&
                fingerprintBytes?.contentEquals(other.fingerprintBytes) == true &&
                masterFingerprintBytes?.contentEquals(other.masterFingerprintBytes) == true &&
                storedIdenticonBytes?.contentEquals(other.storedIdenticonBytes) == true
        }

        override fun hashCode(): Int {
            var result = walletId.hashCode()
            result = 31 * result + passphrase.hashCode()
            result = 31 * result + isLoading.hashCode()
            result = 31 * result + (error?.hashCode() ?: 0)
            result = 31 * result + isUnlocked.hashCode()
            result = 31 * result + (fingerprintBytes?.contentHashCode() ?: 0)
            result = 31 * result + (masterFingerprintBytes?.contentHashCode() ?: 0)
            result = 31 * result + (storedIdenticonBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private var fingerprintJob: Job? = null

    fun load(walletId: String, storedIdenticonBytes: ByteArray?) {
        fingerprintJob?.cancel()
        viewModelScope.launch {
            try {
                val walletEntity = bitcoinRepository.getWalletEntity(walletId)
                _uiState.update {
                    it.copy(
                        walletId = walletId,
                        walletName = walletEntity?.name ?: "Wallet",
                        passphrase = "",
                        fingerprintBytes = null,
                        masterFingerprintBytes = null,
                        storedIdenticonBytes = storedIdenticonBytes,
                        error = null,
                        isUnlocked = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearPassphrase() {
        fingerprintJob?.cancel()
        _uiState.update { it.copy(passphrase = "", fingerprintBytes = null, masterFingerprintBytes = null, error = null) }
    }

    fun setPassphrase(passphrase: String) {
        _uiState.update { it.copy(passphrase = passphrase, error = null) }
        fingerprintJob?.cancel()
        
        // Update fingerprint in real-time as user types
        val walletId = _uiState.value.walletId
        if (passphrase.isNotEmpty() && walletId.isNotEmpty()) {
            fingerprintJob = viewModelScope.launch {
                try {
                    val fingerprint = bitcoinRepository.getPassphraseFingerprint(walletId, passphrase)
                    val stillCurrent = _uiState.value.walletId == walletId && _uiState.value.passphrase == passphrase
                    if (fingerprint != null && stillCurrent) {
                        _uiState.update {
                            it.copy(
                                fingerprintBytes = fingerprint.first,
                                masterFingerprintBytes = fingerprint.second
                            )
                        }
                    } else if (stillCurrent) {
                        _uiState.update { it.copy(fingerprintBytes = null, masterFingerprintBytes = null) }
                    }
                } catch (e: Exception) {
                    val stillCurrent = _uiState.value.walletId == walletId && _uiState.value.passphrase == passphrase
                    if (stillCurrent) {
                        _uiState.update { it.copy(fingerprintBytes = null, masterFingerprintBytes = null) }
                    }
                }
            }
        } else {
            _uiState.update { it.copy(fingerprintBytes = null, masterFingerprintBytes = null) }
        }
    }

    fun unlock() {
        val state = _uiState.value
        if (state.passphrase.isEmpty()) {
            _uiState.update { it.copy(error = "Please enter your passphrase") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                bitcoinRepository.unlockPassphraseWallet(state.walletId, state.passphrase)
                
                _uiState.update { it.copy(isLoading = false, isUnlocked = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to unlock wallet"
                    )
                }
            }
        }
    }
}
