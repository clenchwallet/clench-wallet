package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.SavedSignerDao
import net.clench.wallet.domain.model.HardwareWalletType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class MultisigNfcDestinationTest {
    private val store = ViewModelStore()
    private lateinit var vm: CreateMultisigViewModel

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        val settings = mockk<SettingsManager> { every { isTestnet() } returns false }
        val dao = mockk<SavedSignerDao> { coEvery { getForNetworkAndScript(any(), any()) } returns emptyList() }
        vm = CreateMultisigViewModel(mockk(relaxed = true), settings, dao, mockk(relaxed = true), SavedStateHandle())
        store.put("fixture", vm)
        vm.nextStep()
    }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    private fun rejectsAfter(change: () -> Unit) {
        val attempt = vm.nfcImportSession.begin(null)
        var closed = false
        attempt.attach { closed = true }
        change()
        val replacement = vm.uiState.value.signers
        assertTrue("Mutation must synchronously close the old connection", closed)
        assertFalse(vm.completeNfcSignerImport(attempt, 1, "stale-key"))
        assertFalse("Late error/status callbacks must also be rejected", vm.nfcImportSession.isCurrent(attempt))
        assertEquals(replacement, vm.uiState.value.signers)
    }

    @Test fun removalBeforeTargetCannotOverwriteShiftedRow() = rejectsAfter { vm.removeSigner(0) }
    @Test fun removalAtTargetCannotOverwriteReplacementRow() = rejectsAfter { vm.removeSigner(1) }
    @Test fun pastedReplacementSurvivesLateNfcCompletion() = rejectsAfter { vm.updateSigner(1, xpub = "replacement-key") }
    @Test fun deviceReplacementRevokesImport() = rejectsAfter { vm.setSignerDevice(1, HardwareWalletType.TAPSIGNER) }
    @Test fun backAndPresetResetCannotResurrectAttempt() = rejectsAfter {
        vm.previousStep(); vm.setPreset(2, 3); vm.nextStep()
    }
    @Test fun editAwayAndBackRejectsAbaCompletion() = rejectsAfter {
        val original = vm.uiState.value.signers[1].label
        vm.updateSigner(1, label = "temporary"); vm.updateSigner(1, label = original)
    }
    @Test fun leavingImportStepRevokesWorker() = rejectsAfter { vm.nextStep() }
    @Test fun qrReplacementRouteRevokesWorkerBeforeCameraOpens() = rejectsAfter { vm.showQrScanner(1) }
    @Test fun staleSuccessAndErrorLeaveFreshReaderUntouched() {
        val old = vm.nfcImportSession.begin(null)
        vm.updateSigner(1, label = "new destination")
        val fresh = vm.nfcImportSession.begin(null)
        var freshClosed = false
        fresh.attach { freshClosed = true }
        assertFalse(vm.completeNfcSignerImport(old, 1, "stale-key"))
        assertFalse(vm.nfcImportSession.isCurrent(old))
        assertTrue(vm.nfcImportSession.isCurrent(fresh))
        assertFalse(freshClosed)
        assertTrue(vm.completeNfcSignerImport(fresh, 1, "fresh-key"))
        assertEquals("fresh-key", vm.uiState.value.signers[1].xpub)
        assertTrue(freshClosed)
        assertFalse(vm.completeNfcSignerImport(fresh, 1, "duplicate"))
    }
}
