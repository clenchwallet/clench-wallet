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
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val address: String = "",
        val addressIndex: Int = 0,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) {
        _uiState.update { it.copy(walletId = walletId) }
        // Use getReceiveAddress to show the next unused address
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val addr = bitcoinRepository.getReceiveAddress(walletId)
                _uiState.update { it.copy(address = addr.address, addressIndex = addr.index, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun nextAddress() {
        // Use getReceiveAddress to advance to next address
        val walletId = _uiState.value.walletId
        if (walletId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val addr = bitcoinRepository.getReceiveAddress(walletId)
                _uiState.update { it.copy(address = addr.address, addressIndex = addr.index, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun copyAddress() {
        val address = _uiState.value.address
        if (address.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin Address", address))

        // Auto-clear clipboard after 60 seconds
        viewModelScope.launch {
            kotlinx.coroutines.delay(60_000L)
            // Only clear if it still contains our address
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (current == address) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}
