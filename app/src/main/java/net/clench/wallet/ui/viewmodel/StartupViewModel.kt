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
        data class ExistingWallet(val walletId: String) : StartupDestination()
    }

    private val _destination = MutableStateFlow<StartupDestination>(StartupDestination.Loading)
    val destination = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            val wallets = try {
                bitcoinRepository.listWallets()
            } catch (e: Exception) {
                emptyList()
            }

            _destination.value = when {
                wallets.isNotEmpty() -> StartupDestination.ExistingWallet(wallets.first().id)
                !settingsManager.isOnboarded() -> StartupDestination.NetworkChoice
                else -> StartupDestination.Welcome
            }
        }
    }
}
