package net.clench.wallet.ui.viewmodel

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
import net.clench.wallet.ui.util.copyToClipboardWithAutoClear
import java.math.BigDecimal
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
        val verificationPath: String = "",
        val masterFingerprint: String? = null,
        val importedViaDevice: String? = null,
        val preferredHardwareWallet: String? = null,
        val receiveWithAmount: Boolean = false,
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
                val wallet = bitcoinRepository.getWalletEntity(walletId)
                val accountPath = try { bitcoinRepository.getDerivationPath(walletId) } catch (_: Exception) { "" }
                _uiState.update { it.copy(
                    address = addr.address,
                    addressIndex = addr.index,
                    verificationPath = verificationPath(accountPath, addr.index),
                    masterFingerprint = wallet?.masterFingerprint,
                    importedViaDevice = wallet?.importedViaDevice,
                    preferredHardwareWallet = wallet?.preferredHardwareWallet,
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
                val accountPath = try { bitcoinRepository.getDerivationPath(walletId) } catch (_: Exception) { "" }
                _uiState.update { it.copy(
                    address = addr.address,
                    addressIndex = addr.index,
                    verificationPath = verificationPath(accountPath, addr.index),
                    isLoading = false
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun copyAddress() {
        val address = _uiState.value.address
        if (address.isBlank()) return
        copyToClipboardWithAutoClear(context, "Bitcoin Address", address)
    }

    fun setAmount(amount: String) {
        // Only allow valid numeric input
        val filtered = amount.filter { it.isDigit() }.take(16)
        _uiState.update { it.copy(amountSat = filtered) }
    }

    fun receiveWithAmount() {
        _uiState.update { it.copy(receiveWithAmount = true) }
    }

    fun receiveAddressOnly() {
        _uiState.update { it.copy(receiveWithAmount = false, amountSat = "") }
    }

    /**
     * Generate BIP21 URI for the current address.
     * Format: bitcoin:address or bitcoin:address?amount=btc
     */
    fun getBip21Uri(): String {
        val state = _uiState.value
        val amountSat = if (state.receiveWithAmount) state.amountSat else ""
        return buildBip21Uri(state.address, amountSat)
    }

    fun copyBip21Uri() {
        val uri = getBip21Uri()
        if (uri.isBlank()) return
        copyToClipboardWithAutoClear(context, "Bitcoin BIP21 URI", uri)
    }

    private fun verificationPath(accountPath: String, index: Int): String {
        val clean = accountPath.takeIf { it.isNotBlank() && it != "Unknown" } ?: return "External /0/$index"
        return "${clean.trimEnd('/')}/0/$index"
    }

    companion object {
        fun buildBip21Uri(address: String, amountSat: String): String {
            val cleanAddress = address.trim()
            val baseUri = "bitcoin:$cleanAddress"
            val sats = amountSat.toLongOrNull() ?: return baseUri
            if (sats <= 0L) return baseUri

            val btc = BigDecimal.valueOf(sats)
                .movePointLeft(8)
                .stripTrailingZeros()
                .toPlainString()
            return "$baseUri?amount=$btc"
        }
    }
}
