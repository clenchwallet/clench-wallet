package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.util.DescriptorDisplayPolicy
import net.clench.wallet.ui.util.shouldRethrowForUiBoundary
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val keystoreManager: KeystoreManager,
    private val operationBarrier: SensitiveWalletOperationBarrier
) : ViewModel() {

    data class UiState(
        val walletId: String = "",
        val isWatchOnly: Boolean = false,
        val isMultisig: Boolean = false,
        val hasPassphrase: Boolean = false,
        val accountXpub: String = "",
        val xpubLabel: String = "zpub",
        val descriptor: String = "",
        val changeDescriptor: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val seedRevealed: Boolean = false,
        val mnemonic: List<String> = emptyList(),
        val fingerprintBytes: ByteArray? = null,
        val masterFingerprintBytes: ByteArray? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    override fun onCleared() {
        super.onCleared()
        _uiState.update { it.copy(mnemonic = emptyList(), seedRevealed = false) }
    }

    fun load(walletId: String) {
        _uiState.update { it.copy(walletId = walletId, isLoading = true) }
        viewModelScope.launch {
            try {
                val wallet = bitcoinRepository.getWalletEntity(walletId)
                if (wallet == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Wallet not found") }
                    return@launch
                }

                val xpub = try { bitcoinRepository.getAccountXpub(walletId) } catch (_: Exception) { "" }

                val xpubLabel = when {
                    xpub.startsWith("zpub") -> "zpub"
                    xpub.startsWith("vpub") -> "vpub"
                    xpub.startsWith("ypub") -> "ypub"
                    xpub.startsWith("tpub") -> "tpub"
                    xpub.startsWith("xpub") -> "xpub"
                    else -> "xpub"
                }

                val effectiveIsMultisig = wallet.isMultisig ||
                    DescriptorDisplayPolicy.isMultisigDescriptor(wallet.descriptor) ||
                    DescriptorDisplayPolicy.isMultisigDescriptor(wallet.changeDescriptor)
                val masterFp = if (effectiveIsMultisig) null else CreateWalletViewModel.extractMasterFingerprint(wallet.descriptor)
                val fpBytes = if (effectiveIsMultisig) null else wallet.identiconBytes ?: if (masterFp != null) {
                    CreateWalletViewModel.computeFingerprint(masterFp, "").sliceArray(0 until 8)
                } else null

                _uiState.update { it.copy(
                    isWatchOnly = wallet.isWatchOnly,
                    isMultisig = effectiveIsMultisig,
                    hasPassphrase = wallet.hasPassphrase,
                    accountXpub = xpub,
                    xpubLabel = xpubLabel,
                    descriptor = wallet.descriptor,
                    changeDescriptor = wallet.changeDescriptor,
                    fingerprintBytes = fpBytes,
                    masterFingerprintBytes = masterFp,
                    isLoading = false
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun revealSeed() {
        val walletId = _uiState.value.walletId
        viewModelScope.launch {
            try {
                operationBarrier.withLease {
                    val mnemonic = keystoreManager.getMnemonic(walletId)
                    currentCoroutineContext().ensureActive()
                    if (_uiState.value.walletId != walletId) return@withLease
                    if (mnemonic != null) {
                        val words = mnemonic.split(" ")
                        currentCoroutineContext().ensureActive()
                        if (_uiState.value.walletId != walletId) return@withLease
                        _uiState.update { it.copy(seedRevealed = true, mnemonic = words) }
                    } else {
                        _uiState.update { it.copy(error = "Seed phrase not available for this wallet") }
                    }
                }
            } catch (t: Throwable) {
                if (t.shouldRethrowForUiBoundary()) throw t
                _uiState.update { it.copy(error = "Failed to retrieve seed phrase: ${t.message}") }
            }
        }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }
}
