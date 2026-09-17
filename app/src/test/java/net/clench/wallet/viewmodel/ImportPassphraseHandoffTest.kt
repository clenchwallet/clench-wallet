package net.clench.wallet.viewmodel

import androidx.lifecycle.ViewModelStore
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.viewmodel.ImportWalletViewModel
import org.bitcoindevkit.Mnemonic
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests the real import ViewModel handoff; native derivation is covered by instrumentation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportPassphraseHandoffTest {
    @Test fun `import never replaces nonempty whitespace with the empty wallet identity`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(Mnemonic.Companion)
        val parsed = mockk<Mnemonic>(relaxed = true)
        every { Mnemonic.fromString(any()) } returns parsed
        try {
            listOf("", " ", "   ", "\t", "ordinary", " surrounded ", "é", "e\u0301").forEach { passphrase ->
                val repository = mockk<BitcoinRepository>()
                val settings = mockk<SettingsManager>(relaxed = true)
                every { settings.isOfflineMode() } returns true
                coEvery { repository.importWallet(any(), any(), any(), any()) } returns
                    WalletData("fixture", "fixture", "receive", "change")
                val vm = ImportWalletViewModel(repository, settings, SensitiveWalletOperationBarrier())
                val store = ViewModelStore().apply { put("fixture", vm) }
                try {
                    // Avoid preview derivation in this JVM-only handoff test; native preview has
                    // its own full-path Android test against the pinned BDK artifact.
                    val state = vm.javaClass.getDeclaredField("_uiState").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val flow = state.get(vm) as MutableStateFlow<ImportWalletViewModel.UiState>
                    flow.value = ImportWalletViewModel.UiState(
                        input = (List(11) { "abandon" } + "about").joinToString(" "),
                        passphrase = passphrase, detectedType = ImportWalletViewModel.DetectedType.SEED_12
                    )
                    var imported: String? = null
                    vm.importWallet { imported = it }
                    advanceUntilIdle()
                    assertEquals("fixture", imported)
                    coVerify(exactly = 1) {
                        repository.importWallet(any(), any(), if (passphrase.isEmpty()) null else passphrase, any())
                    }
                    assertEquals("", vm.uiState.value.passphrase)
                } finally { store.clear() }
            }
        } finally {
            unmockkAll()
            Dispatchers.resetMain()
        }
    }
}
