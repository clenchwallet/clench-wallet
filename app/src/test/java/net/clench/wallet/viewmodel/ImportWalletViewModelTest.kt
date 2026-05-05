package net.clench.wallet.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.viewmodel.ImportWalletViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportWalletViewModelTest {

    @Test
    fun `SeedHammer uppercase descriptor UR input normalizes before detection`() {
        val repository = mockk<BitcoinRepository>()
        val viewModel = ImportWalletViewModel(repository)
        val seedHammerUr = (
            "ur:crypto-output/taadmetaadmtoeadadaolftaaddloxaxhdclaxsbsgptsolkltkndsmskiaelfhhmdimcnmnlgutzotecpsfveylgrbdhptbpsveosaahdcxhnganelacwldjnlschnyfxjyplrllfdrplpswdnbuyctlpwyfmmhgsgtwsrymtldamtaaddyoeadlaaxaeattaaddyoyadlnadwkaewklawktaaddloxaxhdclaoztnnhtwtpslgndfnwpzedrlomnclchrdfsayntlplplojznslfjejecpptlgbgwdaahdcxwtmhnyzmpkkbvdpyvwutglbeahmktyuogusnjonththhdwpsfzvdfpdlcndlkensamtaaddyoeadlfaewkaocyrycmrnvwattaaddyoyadlnaewkaewklawktdbsfttn"
            ).uppercase()

        viewModel.setInput(seedHammerUr)
        val state = viewModel.uiState.value

        assertEquals(ImportWalletViewModel.DetectedType.XPUB_WATCH_ONLY, state.detectedType)
        assertFalse(state.input.lowercase().startsWith("ur:"))
        assertTrue(state.input.contains("xpub"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `private descriptor input imports signing wallet not watch-only wallet`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<BitcoinRepository>()
            coEvery {
                repository.importPrivateDescriptor(
                    name = "Imported Wallet",
                    descriptor = any()
                )
            } returns WalletData(
                id = "wallet-private",
                name = "Imported Wallet",
                descriptor = "wpkh([abcd1234/84'/0'/0']xpub/0/*)",
                changeDescriptor = "wpkh([abcd1234/84'/0'/0']xpub/1/*)",
                isWatchOnly = false
            )

            val viewModel = ImportWalletViewModel(repository)
            val privateDescriptor = "wpkh([abcd1234/84'/0'/0']xprv9s21ZrQH143K3/0/*)"
            viewModel.setInput(privateDescriptor)
            viewModel.importWallet { importedId -> assertEquals("wallet-private", importedId) }
            advanceUntilIdle()

            coVerify(exactly = 1) {
                repository.importPrivateDescriptor(
                    name = "Imported Wallet",
                    descriptor = privateDescriptor
                )
            }
            coVerify(exactly = 0) {
                repository.importWatchOnly(any(), any(), any())
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
