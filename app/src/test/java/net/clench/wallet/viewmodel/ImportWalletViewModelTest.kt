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

        assertEquals(ImportWalletViewModel.DetectedType.DESCRIPTOR, state.detectedType)
        assertFalse(state.input.lowercase().startsWith("ur:"))
        assertTrue(state.input.startsWith("wsh(multi("))
        assertTrue(state.input.contains("xpub"))
    }

    @Test
    fun `Coldcard multisig config normalizes before detection`() {
        val repository = mockk<BitcoinRepository>()
        val viewModel = ImportWalletViewModel(repository)
        val coldcard = """
            # Coldcard Multisig setup file
            Name: Vault
            Policy: 2 of 3
            Format: P2WSH
            Derivation: m/48h/0h/0h/2h
            AABBCCDD: xpub6Alpha
            11223344: xpub6Bravo
            55667788: xpub6Charlie
        """.trimIndent()

        viewModel.setInput(coldcard)
        val state = viewModel.uiState.value

        assertEquals(ImportWalletViewModel.DetectedType.DESCRIPTOR, state.detectedType)
        assertEquals(
            "wsh(sortedmulti(2,[AABBCCDD/48'/0'/0'/2']xpub6Alpha/0/*,[11223344/48'/0'/0'/2']xpub6Bravo/0/*,[55667788/48'/0'/0'/2']xpub6Charlie/0/*))",
            state.input
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `multisig config import calls watch-only descriptor import`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val expectedDescriptor =
                "wsh(sortedmulti(2,[AABBCCDD/48'/0'/0'/2']xpub6Alpha/0/*,[11223344/48'/0'/0'/2']xpub6Bravo/0/*,[55667788/48'/0'/0'/2']xpub6Charlie/0/*))"
            val repository = mockk<BitcoinRepository>()
            coEvery {
                repository.importWatchOnly(
                    name = "Vault",
                    descriptor = expectedDescriptor,
                    deviceType = null
                )
            } returns WalletData(
                id = "wallet-multisig",
                name = "Vault",
                descriptor = expectedDescriptor,
                changeDescriptor = expectedDescriptor.replace("/0/*", "/1/*"),
                isWatchOnly = true
            )

            val viewModel = ImportWalletViewModel(repository)
            viewModel.setWalletName("Vault")
            viewModel.setInput(
                """
                BSMS 1.0
                wsh(sortedmulti(2,[AABBCCDD/48'/0'/0'/2']xpub6Alpha/**,[11223344/48'/0'/0'/2']xpub6Bravo/**,[55667788/48'/0'/0'/2']xpub6Charlie/**))#abcd1234
                /0/*,/1/*
                bc1qexampleaddress
                """.trimIndent()
            )
            viewModel.importWallet { importedId -> assertEquals("wallet-multisig", importedId) }
            advanceUntilIdle()

            coVerify(exactly = 1) {
                repository.importWatchOnly(
                    name = "Vault",
                    descriptor = expectedDescriptor,
                    deviceType = null
                )
            }
            coVerify(exactly = 0) {
                repository.importPrivateDescriptor(any(), any())
            }
        } finally {
            Dispatchers.resetMain()
        }
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
