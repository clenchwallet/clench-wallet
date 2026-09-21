package net.clench.wallet.viewmodel

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.AddressVerificationResult
import net.clench.wallet.domain.model.BitcoinAddressVerifier
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.viewmodel.SendViewModel
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendPsbtLifecycleTest {
    private fun fixture(block: suspend TestScope.(SendViewModel, CompletableDeferred<String>, BitcoinRepository) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(BitcoinAddressVerifier)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        try {
            every { BitcoinAddressVerifier.verify(any(),any()) } returns AddressVerificationResult("public-test-address","Testnet","SegWit")
            val deferred=CompletableDeferred<String>();val repo=mockk<BitcoinRepository>(relaxed=true)
            coEvery { repo.createPsbt(any(),any(),any(),any(),any(),any(),any()) } coAnswers { deferred.await() }
            val settings=mockk<SettingsManager>(relaxed=true)
            every { settings.isOfflineMode() } returns true
            every { settings.isBtcPriceEnabled() } returns false
            coEvery { repo.listWallets() } returns emptyList()
            coEvery { repo.getBalance(any()) } throws IllegalStateException("synthetic offline fixture")
            val vm=SendViewModel(repo,settings,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
            val f=SendViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST") val state=f.get(vm) as MutableStateFlow<SendViewModel.UiState>
            state.value=SendViewModel.UiState(walletId="synthetic-wallet",toAddress="public-test-address",amountSat="10000",feeRate="2")
            block(vm,deferred,repo)
        } finally { unmockkObject(BitcoinAddressVerifier); unmockkStatic(android.util.Log::class); Dispatchers.resetMain() }
    }
    @Test fun editedDraftCannotNavigateWithLatePsbt() = fixture { vm,result,_ ->
        var navigated:String?=null;vm.createPsbt { navigated=it };runCurrent()
        vm.setAmount("20000");result.complete("public-synthetic-psbt");advanceUntilIdle()
        assertNull(navigated);assertFalse(vm.uiState.value.isLoading);assertNotNull(vm.uiState.value.error)
    }
    @Test fun editingAwayAndBackStillRequiresFreshRequest() = fixture { vm,result,_ ->
        var navigated=false;vm.createPsbt { navigated=true };runCurrent()
        vm.setAmount("20000");vm.setAmount("10000");result.complete("public-synthetic-psbt");advanceUntilIdle()
        assertFalse(navigated)
    }
    @Test fun unchangedDraftCompletesOnce() = fixture { vm,result,_ ->
        val returns=mutableListOf<String>();vm.createPsbt { returns.add(it) };runCurrent()
        result.complete("public-synthetic-psbt");advanceUntilIdle()
        assertEquals(listOf("public-synthetic-psbt"),returns);assertFalse(vm.uiState.value.isLoading)
    }
    @Test fun duplicateRequestIsReservedBeforeCoroutineStarts() = fixture { vm,result,repo ->
        var calls=0;vm.createPsbt { calls++ };vm.createPsbt { calls++ };runCurrent()
        result.complete("public-synthetic-psbt");advanceUntilIdle()
        coVerify(exactly=1) { repo.createPsbt(any(),any(),any(),any(),any(),any(),any()) };assertEquals(1,calls)
    }
    @Test fun oldWalletCompletionCannotReleaseNewRequestsLoadingState() = fixture { vm,old,repo ->
        var oldNavigations = 0
        vm.createPsbt { oldNavigations++ }
        runCurrent()
        vm.load("second-synthetic-wallet")
        runCurrent()
        val current = CompletableDeferred<String>()
        coEvery { repo.createPsbt(any(),any(),any(),any(),any(),any(),any()) } coAnswers { current.await() }
        var currentNavigations = 0
        vm.createPsbt { currentNavigations++ }
        runCurrent()
        old.complete("obsolete-public-psbt")
        runCurrent()
        assertEquals(0, oldNavigations)
        assertTrue(vm.uiState.value.isLoading)
        current.complete("current-public-psbt")
        advanceUntilIdle()
        assertEquals(1, currentNavigations)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test fun failureReleasesReservationAndExplicitRetryCanSucceed() = fixture { vm,result,repo ->
        var calls = 0
        vm.createPsbt { calls++ }
        runCurrent()
        result.completeExceptionally(IllegalStateException("synthetic failure"))
        advanceUntilIdle()
        assertEquals(0, calls)
        assertFalse(vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.error)
        coEvery { repo.createPsbt(any(),any(),any(),any(),any(),any(),any()) } returns "new-public-psbt"
        vm.createPsbt { calls++ }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertNull(vm.uiState.value.error)
    }

    @Test fun callbackStartingAnotherRequestDoesNotGetItsReservationCleared() = fixture { vm,first,repo ->
        val second = CompletableDeferred<String>()
        var calls = 0
        vm.createPsbt {
            calls++
            coEvery { repo.createPsbt(any(),any(),any(),any(),any(),any(),any()) } coAnswers { second.await() }
            vm.createPsbt { calls++ }
        }
        runCurrent()
        first.complete("first-public-psbt")
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        assertEquals(1, calls)
        second.complete("second-public-psbt")
        advanceUntilIdle()
        assertEquals(2, calls)
        assertFalse(vm.uiState.value.isLoading)
    }

}
