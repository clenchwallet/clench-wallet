package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class StartupViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    sealed class StartupDestination {
        object Loading : StartupDestination()
        object NetworkChoice : StartupDestination()
        object ServerSetup : StartupDestination()
        object Welcome : StartupDestination()
        data class ExistingWallet(val walletId: String, val needsPassphrase: Boolean = false) : StartupDestination()
    }

    private val _destination = MutableStateFlow<StartupDestination>(StartupDestination.Loading)
    val destination = _destination.asStateFlow()

    private val _wallets = MutableStateFlow<List<net.clench.wallet.domain.model.WalletData>>(emptyList())
    val wallets = _wallets.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-evaluate the startup destination.
     * Called on init and again after network switches or any event that
     * navigates back to the "loading" screen.
     */
    fun refresh() {
        _destination.value = StartupDestination.Loading
        viewModelScope.launch {
            val wallets = try {
                bitcoinRepository.listWallets()
            } catch (e: Exception) {
                emptyList()
            }

            _wallets.value = wallets

            _destination.value = when {
                wallets.isNotEmpty() -> {
                    // Prefer last-viewed wallet, fall back to first wallet
                    val lastViewedId = settingsManager.getLastViewedWalletId()
                    val targetWallet = wallets.find { it.id == lastViewedId } ?: wallets.first()
                    StartupDestination.ExistingWallet(
                        walletId = targetWallet.id,
                        needsPassphrase = targetWallet.hasPassphrase && !bitcoinRepository.isPassphraseWalletUnlocked(targetWallet.id)
                    )
                }
                !settingsManager.isOnboarded() -> StartupDestination.NetworkChoice
                else -> StartupDestination.Welcome
            }
        }
    }
}
