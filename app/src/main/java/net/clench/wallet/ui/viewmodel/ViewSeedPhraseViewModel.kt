package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import javax.inject.Inject

@HiltViewModel
class ViewSeedPhraseViewModel @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val settingsManager: SettingsManager
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
        try {
            val mnemonic = keystoreManager.getMnemonic(walletId)
            // Passphrase is intentionally NOT stored or displayed for security [C-2]
            if (mnemonic == null) {
                _uiState.update { it.copy(isLoading = false, error = "No seed phrase found. This may be a watch-only wallet.") }
                return
            }
            _uiState.update {
                it.copy(
                    mnemonic = mnemonic.split(" "),
                    isLoading = false
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }
}
