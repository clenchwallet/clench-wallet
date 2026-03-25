package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class CreateMultisigViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    data class SignerInfo(
        val label: String = "",
        val xpub: String = "",
        val fingerprint: String = "",
        val derivationPath: String = "m/48'/0'/0'/2'",
        val isLocalKey: Boolean = false
    )

    data class UiState(
        val threshold: Int = 2,
        val totalSigners: Int = 3,
        val signers: List<SignerInfo> = emptyList(),
        val walletName: String = "",
        val currentStep: Int = 1,
        val isCreating: Boolean = false,
        val error: String? = null,
        val createdWalletId: String? = null,
        val showQrScanner: Boolean = false,
        val qrScannerTargetIndex: Int = -1
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Initialize signers list based on default totalSigners
        initializeSigners()
    }

    private fun initializeSigners() {
        val total = _uiState.value.totalSigners
        val isTestnet = settingsManager.isTestnet()
        val defaultPath = if (isTestnet) "m/48'/1'/0'/2'" else "m/48'/0'/0'/2'"
        val signers = (1..total).map { i ->
            SignerInfo(
                label = "Signer $i",
                derivationPath = defaultPath
            )
        }
        _uiState.update { it.copy(signers = signers) }
    }

    fun setThreshold(threshold: Int) {
        _uiState.update {
            val newThreshold = threshold.coerceIn(1, it.totalSigners)
            it.copy(threshold = newThreshold)
        }
    }

    fun setTotalSigners(total: Int) {
        val newTotal = total.coerceIn(2, 7)
        _uiState.update {
            val newThreshold = it.threshold.coerceAtMost(newTotal)
            it.copy(totalSigners = newTotal, threshold = newThreshold)
        }
        initializeSigners()
    }

    fun setPreset(m: Int, n: Int) {
        _uiState.update { it.copy(threshold = m, totalSigners = n) }
        initializeSigners()
    }

    fun setWalletName(name: String) {
        _uiState.update { it.copy(walletName = name) }
    }

    fun updateSigner(index: Int, label: String? = null, xpub: String? = null) {
        _uiState.update { state ->
            val signers = state.signers.toMutableList()
            if (index in signers.indices) {
                val current = signers[index]
                val newXpub = xpub ?: current.xpub
                val newFingerprint = if (xpub != null) extractFingerprint(newXpub) else current.fingerprint
                signers[index] = current.copy(
                    label = label ?: current.label,
                    xpub = newXpub,
                    fingerprint = newFingerprint
                )
            }
            state.copy(signers = signers)
        }
    }

    fun removeSigner(index: Int) {
        _uiState.update { state ->
            val signers = state.signers.toMutableList()
            if (index in signers.indices && signers.size > 2) {
                signers.removeAt(index)
                val newTotal = signers.size
                val newThreshold = state.threshold.coerceAtMost(newTotal)
                state.copy(
                    signers = signers,
                    totalSigners = newTotal,
                    threshold = newThreshold
                )
            } else state
        }
    }

    fun showQrScanner(signerIndex: Int) {
        _uiState.update { it.copy(showQrScanner = true, qrScannerTargetIndex = signerIndex) }
    }

    fun hideQrScanner() {
        _uiState.update { it.copy(showQrScanner = false, qrScannerTargetIndex = -1) }
    }

    fun onQrScanned(result: String) {
        val index = _uiState.value.qrScannerTargetIndex
        if (index >= 0) {
            updateSigner(index, xpub = result.trim())
        }
        hideQrScanner()
    }

    fun nextStep() {
        _uiState.update { state ->
            val next = (state.currentStep + 1).coerceAtMost(3)
            state.copy(currentStep = next, error = null)
        }
    }

    fun previousStep() {
        _uiState.update { state ->
            val prev = (state.currentStep - 1).coerceAtLeast(1)
            state.copy(currentStep = prev, error = null)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Build the full multisig descriptor string for display in the review step.
     */
    fun buildDescriptorPreview(): String {
        val state = _uiState.value
        val keys = state.signers.joinToString(",") { signer ->
            val xpub = signer.xpub.trim()
            if (xpub.endsWith("/0/*") || xpub.endsWith("/1/*")) {
                xpub.replace("/1/*", "/0/*")
            } else {
                "$xpub/0/*"
            }
        }
        return "wsh(sortedmulti(${state.threshold},$keys))"
    }

    /**
     * Validate current step before allowing navigation to next.
     */
    fun validateCurrentStep(): Boolean {
        val state = _uiState.value
        return when (state.currentStep) {
            1 -> {
                // Config step is always valid (constrained by UI)
                true
            }
            2 -> {
                // All signers must have xpubs
                val allFilled = state.signers.all { it.xpub.isNotBlank() }
                if (!allFilled) {
                    _uiState.update { it.copy(error = "All signers must have an extended public key") }
                    return false
                }
                true
            }
            3 -> {
                // Wallet name required
                if (state.walletName.isBlank()) {
                    _uiState.update { it.copy(error = "Wallet name is required") }
                    return false
                }
                true
            }
            else -> true
        }
    }

    fun createMultisigWallet(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                val state = _uiState.value

                // Build xpub list with origin info
                val signerXpubs = state.signers.map { it.xpub.trim() }

                val walletData = bitcoinRepository.createMultisigWallet(
                    name = state.walletName.ifBlank { "${state.threshold}-of-${state.totalSigners} Multisig" },
                    threshold = state.threshold,
                    signerXpubs = signerXpubs
                )

                _uiState.update {
                    it.copy(isCreating = false, createdWalletId = walletData.id)
                }
                onCreated(walletData.id)
            } catch (e: Exception) {
                android.util.Log.e("CreateMultisig", "createMultisigWallet failed: ${e.message}", e)
                _uiState.update {
                    it.copy(isCreating = false, error = e.message ?: "Failed to create multisig wallet")
                }
            }
        }
    }

    /**
     * Extract master fingerprint from xpub origin info.
     * Handles formats like: [73c5da0a/48'/0'/0'/2']xpub...
     */
    private fun extractFingerprint(xpub: String): String {
        val match = Regex("\\[([0-9a-fA-F]{8})/").find(xpub)
        return match?.groupValues?.get(1) ?: ""
    }
}
