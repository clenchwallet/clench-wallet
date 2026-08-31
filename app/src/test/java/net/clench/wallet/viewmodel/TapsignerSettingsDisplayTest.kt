package net.clench.wallet.viewmodel

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.clench.wallet.data.backup.ClenchStateBackupManager
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.PinManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.ElectrumConnectionFactory
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.viewmodel.HardwareWalletSettingsViewModel
import net.clench.wallet.ui.viewmodel.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TapsignerSettingsDisplayTest {

    @Test
    fun `hardware wallet settings displays persisted TAPSIGNER preference`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = repositoryWithPersistedTapsigner()

            val viewModel = HardwareWalletSettingsViewModel(repository)
            advanceUntilIdle()

            assertEquals("TAPSIGNER", viewModel.uiState.value.selectedDevice)
            assertEquals("TAPSIGNER", viewModel.uiState.value.selectedLabel)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `general settings displays persisted TAPSIGNER preference`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = repositoryWithPersistedTapsigner()
            val settingsManager = mockk<SettingsManager>(relaxed = true) {
                io.mockk.every { loadElectrumConfig() } returns ElectrumConfig()
            }

            val viewModel = SettingsViewModel(
                bitcoinRepository = repository,
                settingsManager = settingsManager,
                pinManager = mockk<PinManager>(relaxed = true),
                keystoreManager = mockk<KeystoreManager>(relaxed = true),
                electrumConnectionFactory = mockk<ElectrumConnectionFactory>(relaxed = true),
                operationBarrier = SensitiveWalletOperationBarrier(),
                backupManager = mockk<ClenchStateBackupManager>(relaxed = true),
                context = mockk<Context>(relaxed = true)
            )
            advanceUntilIdle()

            assertEquals("TAPSIGNER", viewModel.uiState.value.preferredHardwareWalletLabel)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun repositoryWithPersistedTapsigner(): BitcoinRepository =
        mockk<BitcoinRepository> {
            coEvery { listWallets() } returns listOf(
                WalletData(
                    id = "wallet-1",
                    name = "TAPSIGNER wallet",
                    descriptor = "wpkh(test)",
                    changeDescriptor = "wpkh(test-change)",
                    preferredHardwareWallet = "TAPSIGNER"
                )
            )
        }
}
