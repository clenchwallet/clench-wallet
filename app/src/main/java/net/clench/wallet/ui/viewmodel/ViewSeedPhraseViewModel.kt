package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.ui.util.shouldRethrowForUiBoundary
import javax.inject.Inject

@HiltViewModel
class ViewSeedPhraseViewModel @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val settingsManager: SettingsManager,
    private val operationBarrier: SensitiveWalletOperationBarrier
) : ViewModel() {

    data class UiState(
        val showWarning: Boolean = true,
        val mnemonic: List<String> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val biometricForSeedEnabled: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Best-effort mnemonic cleanup when ViewModel is destroyed.
     * Note: JVM Strings can't be zeroed in memory, but nullifying references
     * allows GC to reclaim them and reduces the window of exposure.
     */
    override fun onCleared() {
        super.onCleared()
        _uiState.update { it.copy(mnemonic = emptyList(), error = null) }
    }

    private var walletId: String = ""

    fun load(walletId: String) {
        this.walletId = walletId
        _uiState.update { it.copy(biometricForSeedEnabled = settingsManager.isBiometricForSeedEnabled(), error = null) }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun confirmWarning() {
        _uiState.update { it.copy(showWarning = false, isLoading = true, error = null) }
        val requestedWalletId = walletId
        viewModelScope.launch {
            try {
                operationBarrier.withLease {
                    val mnemonic = keystoreManager.getMnemonic(requestedWalletId)
                    currentCoroutineContext().ensureActive()
                    // Passphrase is intentionally NOT stored or displayed for security [C-2]
                    if (mnemonic == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "No seed phrase found. This may be a watch-only wallet."
                            )
                        }
                        return@withLease
                    }
                    val words = mnemonic.split(" ")
                    currentCoroutineContext().ensureActive()
                    if (walletId != requestedWalletId) return@withLease
                    _uiState.update { it.copy(mnemonic = words, isLoading = false) }
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update { it.copy(isLoading = false, error = t.message) }
            }
        }
    }
}
