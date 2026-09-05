package net.clench.wallet.viewmodel

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.domain.repository.BuiltTransactionReview
import net.clench.wallet.ui.viewmodel.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSignerPsbtViewModelTest {
    private fun review(id: String) = BuiltTransactionReview(id, 100, 100, 1.0, listOf("input"), emptyList())
    private fun handoff(id: String) = PsbtHandoff(id, "unsigned-$id", "unsigned-$id", "PHONE_SIGNER", 0L)
    private class Fixture {
        val repository = mockk<BitcoinRepository>()
        val store = mockk<PsbtStore>(relaxed = true)
        val settings = mockk<SettingsManager>(relaxed = true)
        val vm = PhoneSignerPsbtViewModel(repository, store, settings)
    }
    private fun scenario(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block(Fixture()) } finally { Dispatchers.resetMain() }
    }

    @Test fun `late review cannot overwrite replacement wallet`() = scenario { f ->
        val old = CompletableDeferred<BuiltTransactionReview>()
        every { f.store.consume(any(), any(), any(), any()) } returnsMany listOf(handoff("a"), handoff("b"))
        coEvery { f.repository.inspectPsbt("a", any()) } coAnswers { old.await() }
        coEvery { f.repository.inspectPsbt("b", any()) } returns review("b")
        f.vm.initFromStore("a"); runCurrent()
        f.vm.initFromStore("b"); runCurrent()
        old.complete(review("a")); advanceUntilIdle()
        assertEquals("b", f.vm.uiState.value.transactionReview?.txid)
        assertEquals("b", f.vm.uiState.value.walletId)
    }

    @Test fun `wrong wallet cancelled and replayed tokens never authorize another signature`() = scenario { f ->
        every { f.store.consume(any(), any(), any(), any()) } returns handoff("a")
        coEvery { f.repository.inspectPsbt(any(), any()) } returns review("a")
        coEvery { f.repository.signMultisigPsbtWithPhoneKeys("a", "unsigned-a") } returns "signed-a"
        f.vm.initFromStore("a"); advanceUntilIdle()
        assertNull(f.vm.beginPhoneSigning("b"))
        val cancelled = f.vm.beginPhoneSigning("a")!!
        f.vm.cancelPhoneSigning(cancelled)
        f.vm.signWithPhoneKeys(cancelled); advanceUntilIdle()
        coVerify(exactly = 0) { f.repository.signMultisigPsbtWithPhoneKeys(any(), any()) }
        val valid = f.vm.beginPhoneSigning("a")!!
        assertNull(f.vm.beginPhoneSigning("a"))
        assertNull(f.vm.initFromStore("b"))
        f.vm.signWithPhoneKeys(valid); f.vm.signWithPhoneKeys(valid)
        advanceUntilIdle()
        coVerify(exactly = 1) { f.repository.signMultisigPsbtWithPhoneKeys("a", "unsigned-a") }
        assertEquals("signed-a", f.vm.uiState.value.signedPsbtBase64)
        assertFalse(f.vm.uiState.value.isSigning)
    }

    @Test fun `invalidated signing completion cannot publish into a new session`() = scenario { f ->
        val signed = CompletableDeferred<String>()
        every { f.store.consume(any(), any(), any(), any()) } returnsMany listOf(handoff("a"), handoff("b"))
        coEvery { f.repository.inspectPsbt(any(), any()) } returns review("review")
        coEvery { f.repository.signMultisigPsbtWithPhoneKeys("a", "unsigned-a") } coAnswers { signed.await() }
        f.vm.initFromStore("a"); advanceUntilIdle()
        f.vm.signWithPhoneKeys(f.vm.beginPhoneSigning("a")!!); runCurrent()
        f.vm.cancelDocumentPickerRoundTrip("test-token")
        f.vm.initFromStore("b"); runCurrent()
        signed.complete("old-signed"); advanceUntilIdle()
        assertEquals("b", f.vm.uiState.value.walletId)
        assertNull(f.vm.uiState.value.signedPsbtBase64)
        assertFalse(f.vm.uiState.value.isSigning)
    }

    @Test fun `pending authentication is revoked when route is disposed`() = scenario { f ->
        every { f.store.consume(any(), any(), any(), any()) } returns handoff("a")
        coEvery { f.repository.inspectPsbt(any(), any()) } returns review("a")
        f.vm.initFromStore("a"); advanceUntilIdle()
        val token = f.vm.beginPhoneSigning("a")!!
        f.vm.cancelPendingAuthentication()
        f.vm.signWithPhoneKeys(token); advanceUntilIdle()
        coVerify(exactly = 0) { f.repository.signMultisigPsbtWithPhoneKeys(any(), any()) }
    }
}
