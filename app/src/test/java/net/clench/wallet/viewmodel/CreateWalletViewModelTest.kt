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
import net.clench.wallet.domain.model.ScriptType
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.viewmodel.CreateWalletViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for CreateWalletViewModel companion object functions — pure functions.
 */
class CreateWalletViewModelTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `confirmAndSave creates wallet from displayed mnemonic and selected script type`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<BitcoinRepository>()
            var savedMnemonic: List<String>? = null

            coEvery {
                repository.createWallet(
                    name = any(),
                    wordCount = any(),
                    passphrase = null,
                    mnemonicWords = any(),
                    scriptType = ScriptType.TAPROOT
                )
            } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val mnemonicArg = invocation.args[3] as List<String>
                savedMnemonic = mnemonicArg
                mnemonicArg to WalletData(
                    id = "wallet-1",
                    name = "My Wallet",
                    descriptor = "tr(test)",
                    changeDescriptor = "tr(test-change)"
                )
            }

            val viewModel = CreateWalletViewModel(repository)
            val preparedMnemonic = listOf(
                "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
                "abandon", "abandon", "abandon", "abandon", "abandon", "about"
            )
            CreateWalletViewModel::class.java
                .getDeclaredField("pendingMnemonic")
                .apply { isAccessible = true }
                .set(viewModel, preparedMnemonic)

            viewModel.setScriptType(ScriptType.TAPROOT)

            var createdWalletId: String? = null
            viewModel.confirmAndSave { createdWalletId = it }
            advanceUntilIdle()

            assertEquals(preparedMnemonic, savedMnemonic)
            assertEquals("wallet-1", createdWalletId)
            coVerify(exactly = 1) {
                repository.createWallet(
                    name = "My Wallet",
                    wordCount = 24,
                    passphrase = null,
                    mnemonicWords = preparedMnemonic,
                    scriptType = ScriptType.TAPROOT
                )
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `extractMasterFingerprint from valid descriptor`() {
        val descriptor = "wpkh([73c5da0a/84h/0h/0h]xpub6BqYRk.../0/*)#checksum"
        val fp = CreateWalletViewModel.extractMasterFingerprint(descriptor)
        assertNotNull(fp)
        assertEquals(4, fp!!.size)
        // 73c5da0a in bytes
        assertEquals(0x73.toByte(), fp[0])
        assertEquals(0xc5.toByte(), fp[1])
        assertEquals(0xda.toByte(), fp[2])
        assertEquals(0x0a.toByte(), fp[3])
    }

    @Test
    fun `extractMasterFingerprint returns null for invalid descriptor`() {
        val result = CreateWalletViewModel.extractMasterFingerprint("wpkh(xpub6BqYRk.../0/*)")
        assertNull(result)
    }

    @Test
    fun `extractMasterFingerprint returns null for empty string`() {
        assertNull(CreateWalletViewModel.extractMasterFingerprint(""))
    }

    @Test
    fun `computeFingerprint is deterministic`() {
        val fp = byteArrayOf(0x73.toByte(), 0xc5.toByte(), 0xda.toByte(), 0x0a.toByte())
        val result1 = CreateWalletViewModel.computeFingerprint(fp, "test")
        val result2 = CreateWalletViewModel.computeFingerprint(fp, "test")
        assertEquals(result1.toList(), result2.toList())
    }

    @Test
    fun `computeFingerprint different passphrases produce different results`() {
        val fp = byteArrayOf(0x73.toByte(), 0xc5.toByte(), 0xda.toByte(), 0x0a.toByte())
        val result1 = CreateWalletViewModel.computeFingerprint(fp, "pass1")
        val result2 = CreateWalletViewModel.computeFingerprint(fp, "pass2")
        // SHA-256 outputs should differ
        assert(result1.toList() != result2.toList())
    }

    @Test
    fun `computeFingerprint empty passphrase`() {
        val fp = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val result = CreateWalletViewModel.computeFingerprint(fp, "")
        assertNotNull(result)
        assertEquals(32, result.size) // SHA-256 is always 32 bytes
    }

    @Test
    fun `computeFingerprint returns 32 bytes SHA-256`() {
        val fp = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val result = CreateWalletViewModel.computeFingerprint(fp, "my passphrase")
        assertEquals(32, result.size)
    }
}
