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
        val amountSat: String = "",  // Requested amount for BIP21 URI
        val isLoading: Boolean = false,
        val error: String? = null,
        val hasLoadedOnce: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) {
        _uiState.update { it.copy(walletId = walletId) }

        // R7-8: Don't burn addresses on every screen open.
        // On first load, use getLastAddress (peekAddress) to show current address.
        // Only advance with getReceiveAddress (revealNextAddress) when user explicitly taps "next".
        if (_uiState.value.hasLoadedOnce) return  // Already loaded — don't re-derive

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val addr = bitcoinRepository.getLastAddress(walletId)
                _uiState.update { it.copy(
                    address = addr.address,
                    addressIndex = addr.index,
                    isLoading = false,
                    hasLoadedOnce = true
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun nextAddress() {
        // R7-8: User explicitly requested a new address — advance the index
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

    fun setAmount(amount: String) {
        // Only allow valid numeric input
        val filtered = amount.filter { it.isDigit() }
        _uiState.update { it.copy(amountSat = filtered) }
    }

    /**
     * Generate BIP21 URI for the current address.
     * Format: bitcoin:address or bitcoin:address?amount=sats
     */
    fun getBip21Uri(): String {
        val state = _uiState.value
        val uri = StringBuilder("bitcoin:${state.address}")
        if (state.amountSat.isNotBlank()) {
            val amountBtc = state.amountSat.toLongOrNull()?.let { it / 100_000_000.0 } ?: 0.0
            if (amountBtc > 0) {
                uri.append("?amount=")
                // Format BTC amount: remove trailing zeros
                val formatted = String.format("%.8f", amountBtc).trimEnd('0').trimEnd('.')
                uri.append(formatted)
            }
        }
        return uri.toString()
    }

    fun copyBip21Uri() {
        val uri = getBip21Uri()
        if (uri.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin BIP21 URI", uri))

        // Auto-clear clipboard after 60 seconds
        viewModelScope.launch {
            kotlinx.coroutines.delay(60_000L)
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (current == uri) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
}
