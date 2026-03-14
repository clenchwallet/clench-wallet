package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.repository.BitcoinRepository
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CreateWalletViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val wordCount: Int = 24,
        val passphrase: String = "",
        val mnemonic: List<String> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun setWordCount(count: Int) = _uiState.update { it.copy(wordCount = count, mnemonic = emptyList()) }
    fun setPassphrase(pass: String) = _uiState.update { it.copy(passphrase = pass) }

    fun generateWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val (mnemonic, _) = bitcoinRepository.createWallet(
                    name = "temp",
                    wordCount = _uiState.value.wordCount,
                    passphrase = _uiState.value.passphrase.ifBlank { null }
                )
                _uiState.update { it.copy(mnemonic = mnemonic, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun confirmAndSave(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val walletName = "Wallet ${UUID.randomUUID().toString().take(6)}"
                val (_, walletData) = bitcoinRepository.createWallet(
                    name = walletName,
                    wordCount = _uiState.value.wordCount,
                    passphrase = _uiState.value.passphrase.ifBlank { null }
                )
                onCreated(walletData.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
