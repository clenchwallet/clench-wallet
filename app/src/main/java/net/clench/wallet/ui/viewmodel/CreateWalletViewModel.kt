package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.model.ScriptType
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.WordCount
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class CreateWalletViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val walletName: String = "My Wallet",
        val wordCount: Int = 24,
        val mnemonic: List<String> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val scriptType: ScriptType = ScriptType.NATIVE_SEGWIT
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UiState) return false
            return walletName == other.walletName && wordCount == other.wordCount &&
                mnemonic == other.mnemonic &&
                isLoading == other.isLoading && error == other.error &&
                scriptType == other.scriptType
        }
        override fun hashCode(): Int {
            var result = walletName.hashCode()
            result = 31 * result + wordCount
            result = 31 * result + mnemonic.hashCode()
            result = 31 * result + isLoading.hashCode()
            result = 31 * result + (error?.hashCode() ?: 0)
            result = 31 * result + scriptType.hashCode()
            return result
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    // Hold generated mnemonic in memory only (not persisted until confirmAndSave)
    private var pendingMnemonic: List<String>? = null

    /**
     * Best-effort mnemonic cleanup when ViewModel is destroyed.
     * Note: JVM Strings can't be zeroed in memory, but nullifying references
     * allows GC to reclaim them and reduces the window of exposure.
     */
    override fun onCleared() {
        super.onCleared()
        pendingMnemonic = null
        _uiState.update {
            it.copy(
                mnemonic = emptyList()
            )
        }
    }

    fun setWalletName(name: String) = _uiState.update { it.copy(walletName = name) }
    fun setWordCount(count: Int) = _uiState.update { it.copy(wordCount = count, mnemonic = emptyList()) }

    fun setScriptType(type: ScriptType) {
        _uiState.update { it.copy(scriptType = type) }
    }

    companion object {
        fun extractMasterFingerprint(descriptorString: String): ByteArray? {
            val match = Regex("\\[([0-9a-fA-F]{8})/").find(descriptorString)
            val hex = match?.groupValues?.get(1) ?: return null
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        fun computeFingerprint(masterFingerprintBytes: ByteArray, passphrase: String): ByteArray {
            val input = masterFingerprintBytes + passphrase.toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(input)
        }
    }

    fun generateWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val wordCountEnum = if (_uiState.value.wordCount == 12) WordCount.WORDS12 else WordCount.WORDS24
                val mnemonic = Mnemonic(wordCountEnum)
                val mnemonicWords = mnemonic.toString().split(" ")

                pendingMnemonic = mnemonicWords

                _uiState.update { it.copy(mnemonic = mnemonicWords, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun confirmAndSave(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val mnemonicWords = pendingMnemonic ?: throw IllegalStateException("No mnemonic generated")

                // Create wallet without passphrase (passphrase wallets can only be created via Import)
                val walletData = bitcoinRepository.createWallet(
                    name = _uiState.value.walletName,
                    wordCount = _uiState.value.wordCount,
                    passphrase = null,
                    mnemonicWords = mnemonicWords,
                    scriptType = _uiState.value.scriptType
                )

                pendingMnemonic = null
                _uiState.update { it.copy(mnemonic = emptyList()) }

                onCreated(walletData.second.id)
            } catch (e: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("CreateWallet", "confirmAndSave failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false, error = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

}
