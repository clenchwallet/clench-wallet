package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.clench.wallet.data.local.KeystoreManager
import javax.inject.Inject

@HiltViewModel
class ViewSeedPhraseViewModel @Inject constructor(
    private val keystoreManager: KeystoreManager
) : ViewModel() {

    data class UiState(
        val showWarning: Boolean = true,
        val mnemonic: List<String> = emptyList(),
        val passphrase: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var walletId: String = ""

    fun load(walletId: String) {
        this.walletId = walletId
    }

    fun confirmWarning() {
        _uiState.update { it.copy(showWarning = false, isLoading = true) }
        try {
            val mnemonic = keystoreManager.getMnemonic(walletId)
            val passphrase = keystoreManager.getPassphrase(walletId)
            if (mnemonic == null) {
                _uiState.update { it.copy(isLoading = false, error = "No seed phrase found. This may be a watch-only wallet.") }
                return
            }
            _uiState.update {
                it.copy(
                    mnemonic = mnemonic.split(" "),
                    passphrase = passphrase?.ifBlank { null },
                    isLoading = false
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }
}
