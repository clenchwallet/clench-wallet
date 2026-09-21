package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.SavedSignerDao
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.domain.repository.GeneratedMultisigPhoneSigner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSignerCompletionTest {
    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()
    private val requests = mutableListOf<CompletableDeferred<GeneratedMultisigPhoneSigner>>()
    private lateinit var vm: CreateMultisigViewModel
    private var testnet = false
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val repository = mockk<BitcoinRepository>()
        coEvery { repository.generateMultisigPhoneSigner() } coAnswers {
            CompletableDeferred<GeneratedMultisigPhoneSigner>().also { requests += it }.await()
        }
        val settings = mockk<SettingsManager> { every { isTestnet() } answers { testnet } }
        val dao = mockk<SavedSignerDao> { coEvery { getForNetworkAndScript(any(), any()) } returns emptyList() }
        vm = CreateMultisigViewModel(repository, settings, dao, mockk(relaxed = true), SavedStateHandle())
        store.put("fixture", vm)
        vm.nextStep()
        scheduler.runCurrent()
    }
    @After fun teardown() {
        val job = vm.viewModelScope.coroutineContext[Job]!!
        store.clear()
        // loadSavedSigners crosses real Dispatchers.IO; drain its cancellation before resetting Main.
        runBlocking {
            withTimeout(5_000) {
                while (!job.isCompleted) { scheduler.runCurrent(); delay(1) }
            }
        }
        Dispatchers.resetMain()
    }
    private fun generated(id: String) = GeneratedMultisigPhoneSigner(
        listOf("synthetic", "test", id), "public-fixture-$id", "synthetic-secret-$id", "12345678", "m/48'/0'/0'/2'"
    )
    private fun start(index: Int = 1) { vm.generatePhoneSigner(index); scheduler.runCurrent() }
    private fun succeed(request: Int = 0) { requests[request].complete(generated("$request")); scheduler.runCurrent() }

    @Test fun shiftedRowSurvivesDelayedPhoneResult() {
        start(); vm.removeSigner(0)
        val rows = vm.uiState.value.signers
        succeed()
        assertEquals(rows, vm.uiState.value.signers)
        assertNull(vm.uiState.value.generatingPhoneSignerIndex)
    }
    @Test fun editedDraftAndNewNfcReaderSurviveOldPhoneSuccess() {
        start(); vm.updateSigner(1, label = "replacement")
        val reader = vm.nfcImportSession.begin(null)
        var closed = false; reader.attach { closed = true }
        val rows = vm.uiState.value.signers
        succeed()
        assertEquals(rows, vm.uiState.value.signers)
        assertTrue(vm.nfcImportSession.isCurrent(reader)); assertFalse(closed)
    }
    @Test fun oldFailureCannotClearNewPhoneOperationOrOverwriteItsError() {
        start(); vm.updateSigner(1, label = "new draft"); start(0)
        vm.setError("newer warning")
        requests[0].completeExceptionally(IllegalStateException("old failure")); scheduler.runCurrent()
        assertEquals(0, vm.uiState.value.generatingPhoneSignerIndex)
        assertEquals("newer warning", vm.uiState.value.error)
        succeed(1)
        assertEquals("public-fixture-1", vm.uiState.value.signers[0].xpub)
    }
    @Test fun oldSuccessCannotClearNewPhoneOperation() {
        start(); start(0); succeed(0)
        assertEquals(0, vm.uiState.value.generatingPhoneSignerIndex)
        assertFalse(vm.uiState.value.signers[1].isLocalKey)
        succeed(1)
        assertEquals("public-fixture-1", vm.uiState.value.signers[0].xpub)
    }
    @Test fun draftEditAwayAndBackCannotResurrectPhoneResult() {
        start(); val label = vm.uiState.value.signers[1].label
        vm.updateSigner(1, label = "temporary"); vm.updateSigner(1, label = label)
        val rows = vm.uiState.value.signers; succeed(); assertEquals(rows, vm.uiState.value.signers)
    }
    @Test fun leavingStepRejectsPhoneResult() {
        start(); vm.previousStep(); val rows = vm.uiState.value.signers
        succeed(); assertEquals(rows, vm.uiState.value.signers)
    }
    @Test fun queuedObsoleteStartCannotRunOrClearNewerLoading() {
        vm.generatePhoneSigner(1)
        vm.generatePhoneSigner(0)
        scheduler.runCurrent()
        assertEquals(1, requests.size)
        assertEquals(0, vm.uiState.value.generatingPhoneSignerIndex)
        succeed()
        assertEquals("public-fixture-0", vm.uiState.value.signers[0].xpub)
        assertFalse(vm.uiState.value.signers[1].isLocalKey)
    }
    @Test fun networkChangeRejectsGeneratedKeyAndClearsOwnLoading() {
        start(); testnet = true
        val rows = vm.uiState.value.signers; succeed()
        assertEquals(rows, vm.uiState.value.signers)
        assertNull(vm.uiState.value.generatingPhoneSignerIndex)
    }
    @Test fun currentFailureClearsLoadingAndReportsError() {
        start(); requests[0].completeExceptionally(IllegalStateException("fixture failure")); scheduler.runCurrent()
        assertNull(vm.uiState.value.generatingPhoneSignerIndex)
        assertTrue(vm.uiState.value.error!!.startsWith("Could not generate phone signer:"))
        assertFalse(vm.uiState.value.signers[1].isLocalKey)
    }
    @Test fun currentSuccessStillPopulatesSignerAndClearsLoading() {
        start(); succeed()
        assertTrue(vm.uiState.value.signers[1].isLocalKey)
        assertEquals("public-fixture-0", vm.uiState.value.signers[1].xpub)
        assertFalse(vm.uiState.value.signers[1].phoneSignerBackedUp)
        assertNull(vm.uiState.value.generatingPhoneSignerIndex)
    }
}
