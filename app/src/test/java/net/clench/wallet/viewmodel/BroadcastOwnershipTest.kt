package net.clench.wallet.viewmodel

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.viewmodel.SendViewModel
import net.clench.wallet.ui.viewmodel.SweepViewModel
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastOwnershipTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(vm: Any): MutableStateFlow<T> = vm.javaClass.getDeclaredField("_uiState")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun scenario(block: suspend TestScope.(BitcoinRepository, SettingsManager, CompletableDeferred<String>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        try {
            val repo = mockk<BitcoinRepository>(relaxed = true)
            val settings = mockk<SettingsManager>(relaxed = true)
            val result = CompletableDeferred<String>()
            coEvery { repo.broadcastTransaction(any(), any(), any()) } coAnswers { result.await() }
            block(repo, settings, result)
        } finally { unmockkStatic(android.util.Log::class); Dispatchers.resetMain() }
    }
    private fun send(repo: BitcoinRepository, settings: SettingsManager): SendViewModel {
        val vm = SendViewModel(repo, settings, mockk(relaxed=true), mockk(relaxed=true), mockk(relaxed=true))
        val draft = SendViewModel.UiState(walletId="public-fixture",toAddress="public-destination",amountSat="1000",feeRate="2",txHex="synthetic-signed-transaction")
        val fingerprint = SendViewModel::class.java.getDeclaredMethod("proposalFingerprint",SendViewModel.UiState::class.java)
            .apply { isAccessible=true }.invoke(vm,draft) as String
        state<SendViewModel.UiState>(vm).value=draft.copy(proposalFingerprint=fingerprint)
        return vm
    }
    @Test fun sendDuplicateAdmissionIsRejected() = scenario { repo, settings, result ->
        val vm=send(repo,settings);vm.broadcast {};vm.broadcast {};runCurrent()
        result.complete("accepted");advanceUntilIdle()
        coVerify(exactly=1) { repo.broadcastTransaction(any(),any(),any()) }
    }
    @Test fun sendCompletionCannotDescribeEditedDraft() = scenario { repo, settings, result ->
        val vm=send(repo,settings);vm.broadcast {};runCurrent();vm.setAmount("2000")
        result.complete("old-accepted");advanceUntilIdle()
        assertFalse(vm.uiState.value.broadcastSuccess);assertNull(vm.uiState.value.broadcastTxid)
    }
    @Test fun sweepDuplicateAdmissionIsRejected() = scenario { repo, settings, result ->
        val vm=SweepViewModel(repo,settings,mockk(relaxed=true),mockk(relaxed=true))
        state<SweepViewModel.UiState>(vm).value=SweepViewModel.UiState(preparedTxHex="synthetic-signed-transaction")
        vm.broadcastPreparedSweep();vm.broadcastPreparedSweep();runCurrent()
        result.complete("accepted");advanceUntilIdle()
        coVerify(exactly=1) { repo.broadcastTransaction(any(),any(),any()) }
    }
    @Test fun sweepCompletionCannotDescribeEditedDestination() = scenario { repo, settings, result ->
        val vm=SweepViewModel(repo,settings,mockk(relaxed=true),mockk(relaxed=true))
        state<SweepViewModel.UiState>(vm).value=SweepViewModel.UiState(preparedTxHex="synthetic-signed-transaction")
        vm.broadcastPreparedSweep();runCurrent();vm.setDestinationAddress("replacement")
        result.complete("old-accepted");advanceUntilIdle()
        assertNull(vm.uiState.value.broadcastTxid)
    }
    @Test fun sweepRevokedDestinationNeedsFreshPreparation() = scenario { repo, settings, result ->
        val vm=SweepViewModel(repo,settings,mockk(relaxed=true),mockk(relaxed=true))
        state<SweepViewModel.UiState>(vm).value=SweepViewModel.UiState(
            destinationAddress="external", defaultDestinationAddress="wallet", externalDestinationConfirmed=true,
            preparedTxHex="synthetic-signed-transaction")
        vm.confirmExternalDestination(false)
        vm.broadcastPreparedSweep();runCurrent()
        result.complete("accepted");advanceUntilIdle()
        coVerify(exactly=0) { repo.broadcastTransaction(any(),any(),any()) }
        assertNull(vm.uiState.value.preparedTxHex)
    }
    @Test fun sendEditBeforeDispatchRevokesRequest() = scenario { repo, settings, result ->
        val vm=send(repo,settings);vm.broadcast {};vm.setAmount("2000");runCurrent()
        result.complete("accepted");advanceUntilIdle()
        coVerify(exactly=0) { repo.broadcastTransaction(any(),any(),any()) }
        assertFalse(vm.uiState.value.isLoading)
    }
    @Test fun sweepDiscardBeforeDispatchRevokesRequest() = scenario { repo, settings, result ->
        val vm=SweepViewModel(repo,settings,mockk(relaxed=true),mockk(relaxed=true))
        state<SweepViewModel.UiState>(vm).value=SweepViewModel.UiState(preparedTxHex="synthetic-signed-transaction")
        vm.broadcastPreparedSweep();vm.discardPreparedSweep();runCurrent()
        result.complete("accepted");advanceUntilIdle()
        coVerify(exactly=0) { repo.broadcastTransaction(any(),any(),any()) }
        assertFalse(vm.uiState.value.isBroadcasting)
    }

    @Test fun sendLateFailurePreservesReplacementStateAndAllowsExplicitRetry() = scenario { repo, settings, result ->
        val vm=send(repo,settings);vm.broadcast {};runCurrent();vm.setAmount("2000");vm.setError("current draft error")
        result.completeExceptionally(IllegalStateException("obsolete failure"));advanceUntilIdle()
        assertEquals("current draft error",vm.uiState.value.error);assertFalse(vm.uiState.value.isLoading)
        val next=send(repo,settings).uiState.value
        state<SendViewModel.UiState>(vm).value=next
        coEvery { repo.broadcastTransaction(any(),any(),any()) } returns "new-accepted"
        vm.broadcast {};advanceUntilIdle()
        assertEquals("new-accepted",vm.uiState.value.broadcastTxid)
    }
    @Test fun sweepLateFailurePreservesReplacementStateAndAllowsExplicitRetry() = scenario { repo, settings, result ->
        val vm=SweepViewModel(repo,settings,mockk(relaxed=true),mockk(relaxed=true))
        state<SweepViewModel.UiState>(vm).value=SweepViewModel.UiState(preparedTxHex="synthetic-signed-transaction")
        vm.broadcastPreparedSweep();runCurrent();vm.discardPreparedSweep();vm.setError("current draft error")
        result.completeExceptionally(IllegalStateException("obsolete failure"));advanceUntilIdle()
        assertEquals("current draft error",vm.uiState.value.error);assertFalse(vm.uiState.value.isBroadcasting)
        state<SweepViewModel.UiState>(vm).value=SweepViewModel.UiState(preparedTxHex="new-synthetic-transaction")
        coEvery { repo.broadcastTransaction(any(),any(),any()) } returns "new-accepted"
        vm.broadcastPreparedSweep();advanceUntilIdle()
        assertEquals("new-accepted",vm.uiState.value.broadcastTxid)
    }

}
