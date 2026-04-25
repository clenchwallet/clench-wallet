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
import net.clench.wallet.domain.model.HardwareWalletType
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.util.copyToClipboardWithAutoClear
import javax.inject.Inject

@HiltViewModel
class WalletInfoViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val walletName: String = "",
        val isWatchOnly: Boolean = false,
        val hasPassphrase: Boolean = false,
        val network: String = "mainnet",
        val transactionCount: Int = 0,
        val derivationPath: String = "",
        val accountXpub: String = "",
        val xpubLabel: String = "zpub",
        val preferredHardwareWallet: String? = null,
        val masterFingerprint: String? = null,        // stored HW fingerprint, e.g. "D3E95C19"
        val storedDerivationPath: String? = null,     // stored HW derivation path
        val importedViaDevice: String? = null,        // e.g. "COLDCARD_Q"
        val fingerprint: String = "",
        val fingerprintColors: List<Int> = emptyList(),
        val fingerprintBytes: ByteArray? = null,
        val masterFingerprintBytes: ByteArray? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val isEditing: Boolean = false,
        val editName: String = "",
        val descriptor: String = "",
        val copied: Boolean = false,
        val isConvertingToHot: Boolean = false,
        val convertedToHot: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) {
        _uiState.update { it.copy(walletId = walletId, isLoading = true) }
        viewModelScope.launch {
            try {
                val wallet = bitcoinRepository.getWalletEntity(walletId)
                if (wallet == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Wallet not found") }
                    return@launch
                }

                val txs = bitcoinRepository.getTransactions(walletId)
                val xpub = try { bitcoinRepository.getAccountXpub(walletId) } catch (_: Exception) { "" }
                val derivPath = try { bitcoinRepository.getDerivationPath(walletId) } catch (_: Exception) { "Unknown" }

                // Determine xpub label based on network and prefix
                val xpubLabel = when {
                    xpub.startsWith("zpub") -> "zpub"
                    xpub.startsWith("vpub") -> "vpub"
                    xpub.startsWith("ypub") -> "ypub"
                    xpub.startsWith("upub") -> "upub"
                    xpub.startsWith("tpub") -> "tpub"
                    xpub.startsWith("xpub") -> "xpub"
                    else -> "xpub"
                }

                // Visual fingerprint: use stored identicon bytes if available (preserves
                // passphrase-derived visual from wallet creation). Fall back to recomputing
                // without passphrase for older wallets that don't have stored bytes.
                val fingerprint = generateFingerprint(wallet.descriptor)
                val fingerprintColors = generateFingerprintColors(fingerprint)
                val masterFp = CreateWalletViewModel.extractMasterFingerprint(wallet.descriptor)
                val fpBytes = wallet.identiconBytes ?: if (masterFp != null) {
                    CreateWalletViewModel.computeFingerprint(masterFp, "").sliceArray(0 until 8)
                } else null

                _uiState.update { it.copy(
                    walletName = wallet.name,
                    isWatchOnly = wallet.isWatchOnly,
                    hasPassphrase = wallet.hasPassphrase,
                    network = wallet.network,
                    transactionCount = txs.size,
                    derivationPath = derivPath,
                    accountXpub = xpub,
                    xpubLabel = xpubLabel,
                    preferredHardwareWallet = wallet.preferredHardwareWallet,
                    masterFingerprint = wallet.masterFingerprint,
                    storedDerivationPath = wallet.derivationPath,
                    importedViaDevice = wallet.importedViaDevice,
                    fingerprint = fingerprint,
                    fingerprintColors = fingerprintColors,
                    fingerprintBytes = fpBytes,
                    masterFingerprintBytes = masterFp,
                    descriptor = wallet.descriptor,
                    isLoading = false
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun startEditing() {
        _uiState.update { it.copy(isEditing = true, editName = it.walletName) }
    }

    fun setEditName(name: String) {
        _uiState.update { it.copy(editName = name) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(isEditing = false) }
    }

    fun saveName() {
        val walletId = _uiState.value.walletId
        val newName = _uiState.value.editName.trim()
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                bitcoinRepository.renameWallet(walletId, newName)
                _uiState.update { it.copy(walletName = newName, isEditing = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun setPreferredHardwareWallet(device: String?) {
        val walletId = _uiState.value.walletId
        viewModelScope.launch {
            try {
                bitcoinRepository.setPreferredHardwareWallet(walletId, device)
                _uiState.update { it.copy(preferredHardwareWallet = device) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun convertWatchOnlyToHot(mnemonicWords: CharArray, passphrase: CharArray?) {
        val walletId = _uiState.value.walletId
        val words = String(mnemonicWords).trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        val passphraseString = passphrase?.let { String(it) }?.ifBlank { null }
        if (words.size != 12 && words.size != 24) {
            mnemonicWords.fill('0')
            passphrase?.fill('0')
            _uiState.update { it.copy(error = "Enter a 12 or 24 word seed phrase") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isConvertingToHot = true, error = null, convertedToHot = false) }
            try {
                bitcoinRepository.convertWatchOnlyToHot(walletId, words, passphraseString)
                _uiState.update {
                    it.copy(
                        isConvertingToHot = false,
                        convertedToHot = true,
                        isWatchOnly = false,
                        hasPassphrase = passphraseString != null,
                        error = null
                    )
                }
                load(walletId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConvertingToHot = false,
                        error = e.message ?: "Could not add seed phrase"
                    )
                }
            } finally {
                mnemonicWords.fill('0')
                passphrase?.fill('0')
            }
        }
    }

    fun clearConversionSuccess() {
        _uiState.update { it.copy(convertedToHot = false) }
    }

    fun copyToClipboard(text: String, label: String = "Copied") {
        copyToClipboardWithAutoClear(context, label, text)
        _uiState.update { it.copy(copied = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000L)
            _uiState.update { it.copy(copied = false) }
        }
    }

    /**
     * Generate fingerprint from master fingerprint bytes extracted from descriptor.
     * Uses SHA-256 of the 4-byte master fingerprint (no passphrase mixing — passphrase
     * is not stored, so we can only use the descriptor's embedded master fingerprint).
     */
    private fun generateFingerprint(descriptorString: String): String {
        return try {
            val masterFp = CreateWalletViewModel.extractMasterFingerprint(descriptorString)
            if (masterFp != null) {
                val hash = CreateWalletViewModel.computeFingerprint(masterFp, "")
                hash.take(8).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            } else {
                // Fallback for descriptors without origin (e.g. bare xpub watch-only)
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(descriptorString.toByteArray())
                hash.take(8).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            }
        } catch (_: Exception) { "" }
    }

    private fun generateFingerprintColors(fingerprint: String): List<Int> {
        if (fingerprint.isBlank()) return emptyList()
        val bytes = fingerprint.split(":").mapNotNull {
            try { it.toInt(16) } catch (_: Exception) { null }
        }
        // Generate 8 hue-based colors from the bytes (matching CreateWalletScreen style)
        return bytes.map { b ->
            val hue = b * 360f / 256f
            android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.7f))
        }
    }
}
