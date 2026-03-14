package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class ImportWalletViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    enum class ImportMode { SEED, DESCRIPTOR }

    data class UiState(
        val importMode: ImportMode = ImportMode.SEED,
        val walletName: String = "",
        val seedInput: String = "",
        val passphrase: String = "",
        val descriptorInput: String = "",
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun setImportMode(mode: ImportMode) = _uiState.update { it.copy(importMode = mode, error = null) }
    fun setWalletName(name: String) = _uiState.update { it.copy(walletName = name) }
    fun setSeedInput(seed: String) = _uiState.update { it.copy(seedInput = seed) }
    fun setPassphrase(pass: String) = _uiState.update { it.copy(passphrase = pass) }
    fun setDescriptorInput(desc: String) = _uiState.update { it.copy(descriptorInput = desc) }

    fun importWallet(onImported: (String) -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val walletData = when (state.importMode) {
                    ImportMode.SEED -> {
                        val words = state.seedInput.trim().split("\\s+".toRegex())
                        if (words.size != 12 && words.size != 24) {
                            _uiState.update { it.copy(isLoading = false, error = "Please enter 12 or 24 words") }
                            return@launch
                        }
                        bitcoinRepository.importWallet(
                            name = state.walletName.ifBlank { "Imported Wallet" },
                            mnemonic = words,
                            passphrase = state.passphrase.ifBlank { null }
                        )
                    }
                    ImportMode.DESCRIPTOR -> {
                        if (state.descriptorInput.isBlank()) {
                            _uiState.update { it.copy(isLoading = false, error = "Please enter a descriptor or xpub") }
                            return@launch
                        }
                        bitcoinRepository.importWatchOnly(
                            name = state.walletName.ifBlank { "Watch-only Wallet" },
                            descriptor = state.descriptorInput.trim()
                        )
                    }
                }
                onImported(walletData.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
