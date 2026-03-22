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
class HardwareWalletSettingsViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository
) : ViewModel() {

    data class UiState(
        val selectedDevice: String? = null,
        val selectedLabel: String = "None",
        val savedSuccess: Boolean = false,
        val walletIds: List<String> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val deviceLabels = mapOf(
        "SEEDSIGNER" to "SeedSigner",
        "KEYSTONE" to "Keystone",
        "PASSPORT" to "Foundation Passport",
        "COLDCARD_Q" to "Coldcard Q",
        "COLDCARD_MK4" to "Coldcard Mk4",
        "JADE" to "Blockstream Jade"
    )

    init {
        viewModelScope.launch {
            val wallets = bitcoinRepository.listWallets()
            val walletIds = wallets.map { it.id }
            // Use first wallet's preference as the current setting
            val currentDevice = wallets.firstOrNull()?.preferredHardwareWallet
            _uiState.update {
                it.copy(
                    selectedDevice = currentDevice,
                    selectedLabel = deviceLabels[currentDevice] ?: "None",
                    walletIds = walletIds
                )
            }
        }
    }

    fun setPreferredDevice(device: String?) {
        val label = deviceLabels[device] ?: "None"
        _uiState.update { it.copy(selectedDevice = device, selectedLabel = label) }

        // Apply to all wallets on current network
        viewModelScope.launch {
            val walletIds = _uiState.value.walletIds
            for (id in walletIds) {
                bitcoinRepository.setPreferredHardwareWallet(id, device)
            }
            _uiState.update { it.copy(savedSuccess = true) }
            kotlinx.coroutines.delay(2000)
            _uiState.update { it.copy(savedSuccess = false) }
        }
    }
}
