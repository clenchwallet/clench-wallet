package net.clench.wallet.ui.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.model.Address
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class AddressesViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class UiState(
        val addresses: List<Address> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val copiedIndex: Int? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val addresses = bitcoinRepository.getAddresses(walletId, 20)
                _uiState.update { it.copy(addresses = addresses, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun copyAddress(index: Int, address: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin Address", address))
        _uiState.update { it.copy(copiedIndex = index) }

        // Clear copied indicator after 2 seconds
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000L)
            _uiState.update { it.copy(copiedIndex = null) }
        }

        // Auto-clear clipboard after 60 seconds
        viewModelScope.launch {
            kotlinx.coroutines.delay(60_000L)
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (current == address) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}
