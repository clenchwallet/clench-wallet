package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.WordCount
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CreateWalletViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val walletName: String = "My Wallet",
        val wordCount: Int = 24,
        val passphrase: String = "",
        val mnemonic: List<String> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val fingerprintBytes: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UiState) return false
            return walletName == other.walletName && wordCount == other.wordCount &&
                passphrase == other.passphrase && mnemonic == other.mnemonic &&
                isLoading == other.isLoading && error == other.error &&
                fingerprintBytes.contentEquals(other.fingerprintBytes)
        }
        override fun hashCode(): Int {
            var result = walletName.hashCode()
            result = 31 * result + wordCount
            result = 31 * result + passphrase.hashCode()
            result = 31 * result + mnemonic.hashCode()
            result = 31 * result + isLoading.hashCode()
            result = 31 * result + (error?.hashCode() ?: 0)
            result = 31 * result + (fingerprintBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    // Hold generated mnemonic in memory only (not persisted until confirmAndSave)
    private var pendingMnemonic: List<String>? = null

    fun setWalletName(name: String) = _uiState.update { it.copy(walletName = name) }
    fun setWordCount(count: Int) = _uiState.update { it.copy(wordCount = count, mnemonic = emptyList()) }
    fun setPassphrase(pass: String) {
        _uiState.update { it.copy(passphrase = pass) }
        updateFingerprint()
    }

    private fun updateFingerprint() {
        val state = _uiState.value
        val mnemonic = state.mnemonic
        val passphrase = state.passphrase

        // Show fingerprint if we have mnemonic or passphrase
        if (mnemonic.isEmpty() && passphrase.isEmpty()) {
            _uiState.update { it.copy(fingerprintBytes = null) }
            return
        }

        val input = if (mnemonic.isNotEmpty()) {
            mnemonic.joinToString(" ") + passphrase
        } else {
            passphrase
        }

        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            _uiState.update { it.copy(fingerprintBytes = digest.sliceArray(0 until 8)) }
        } catch (e: Exception) {
            _uiState.update { it.copy(fingerprintBytes = null) }
        }
    }

    fun generateWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // ONLY generate mnemonic in memory — zero DB/keystore writes
                val wordCountEnum = if (_uiState.value.wordCount == 12) WordCount.WORDS12 else WordCount.WORDS24
                val mnemonic = Mnemonic(wordCountEnum)
                val mnemonicWords = mnemonic.toString().split(" ")

                // Store in memory
                pendingMnemonic = mnemonicWords

                _uiState.update { it.copy(mnemonic = mnemonicWords, isLoading = false) }
                updateFingerprint()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun confirmAndSave(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Use the same mnemonic words from generateWallet()
                val mnemonicWords = pendingMnemonic ?: throw IllegalStateException("No mnemonic generated")

                val walletData = bitcoinRepository.importWallet(
                    name = _uiState.value.walletName,
                    mnemonic = mnemonicWords,
                    passphrase = _uiState.value.passphrase.ifBlank { null }
                )

                // Clear mnemonic from memory as best effort — JVM String is immutable
                // so the backing char[] can't be zeroed, but at least remove references
                // so it becomes eligible for GC and won't be shown if user navigates back.
                pendingMnemonic = null
                _uiState.update { it.copy(mnemonic = emptyList(), passphrase = "") }

                onCreated(walletData.id)
            } catch (e: Exception) {
                android.util.Log.e("CreateWallet", "confirmAndSave failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _uiState.update { it.copy(isLoading = false, error = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }
}
