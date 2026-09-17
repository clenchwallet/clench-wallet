package net.clench.wallet.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.clench.wallet.domain.model.toNetworkKind
import net.clench.wallet.ui.viewmodel.CreateMultisigViewModel
import net.clench.wallet.ui.viewmodel.WalletInfoViewModel
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.Network
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultisigCreationBoundaryTest {
    @Test fun exactlyOneKeyPerFieldIsRequiredBeforeAnyWalletPersistence() = runBlocking {
        WalletRepositoryFixture().use { f ->
            val a = f.repository.generateMultisigPhoneSigner().xpubWithOrigin
            val b = f.repository.generateMultisigPhoneSigner().xpubWithOrigin
            val c = f.repository.generateMultisigPhoneSigner().xpubWithOrigin
            try {
                f.repository.createMultisigWallet("Rejected", 2, listOf("$a/0/*,$b/0/*", c))
                fail("A two-field input must not persist a three-key spending policy")
            } catch (_: IllegalArgumentException) { }
            assertTrue(f.database.walletDao().getAll().isEmpty())
            assertTrue(f.context.getDatabasePath("unused").parentFile!!.listFiles().orEmpty().none { it.name.startsWith("wallet_") })
            try {
                f.repository.createMultisigWallet("Duplicate", 2, listOf(a, "[deadbeef/48'/1'/0'/2']" + a.substringAfter(']')))
                fail("Aliased material must not count as an independent signer")
            } catch (_: IllegalArgumentException) { }
            assertTrue(f.database.walletDao().getAll().isEmpty())
        }
    }

    @Test fun viewModelToRepositoryNativeAndRoomRetainsTheReviewedReceiveAndChangePolicy() = runBlocking {
        WalletRepositoryFixture().use { f ->
            val signers = listOf(f.repository.generateMultisigPhoneSigner().xpubWithOrigin,
                f.repository.generateMultisigPhoneSigner().xpubWithOrigin)
            val completion = CompletableDeferred<String>()
            val store = ViewModelStore()
            lateinit var vm: CreateMultisigViewModel
            try {
                withContext(Dispatchers.Main) {
                    vm = CreateMultisigViewModel(f.repository, f.settings, f.database.savedSignerDao(),
                        f.database.walletKeystoreMetadataDao(), SavedStateHandle())
                    store.put("fixture", vm)
                    vm.setPreset(2, 2)
                    vm.updateSigner(0, xpub = signers[0])
                    vm.updateSigner(1, xpub = signers[1])
                    vm.setWalletName("Disposable native policy")
                    vm.nextStep()
                    assertTrue(vm.validateCurrentStep())
                    vm.nextStep()
                    vm.createMultisigWallet { completion.complete(it) }
                }
                val id = withTimeout(30_000) {
                    while (!completion.isCompleted) {
                        vm.uiState.value.error?.let { throw AssertionError(it) }
                        delay(25)
                    }
                    completion.await()
                }
                val row = requireNotNull(f.database.walletDao().getById(id))
                Descriptor(vm.buildDescriptorPreview(), Network.TESTNET.toNetworkKind()).use { preview ->
                    assertEquals(preview.toString(), row.descriptor)
                }
                assertEquals(2, f.database.walletKeystoreMetadataDao().getForWallet(id).size)
                MultisigDescriptorSafety.requireExpectedPolicy(row.descriptor, 2, signers, 0)
                MultisigDescriptorSafety.requireExpectedPolicy(row.changeDescriptor, 2, signers, 1)
                val display = requireNotNull(WalletInfoViewModel.parseMultisigPolicyForDisplay(row.descriptor, row.changeDescriptor))
                assertEquals(2, display.threshold)
                assertEquals(2, display.totalSigners)
                assertTrue(display.warnings.isEmpty())
                Descriptor(row.descriptor, Network.TESTNET.toNetworkKind()).use { receive ->
                    Descriptor(row.changeDescriptor, Network.TESTNET.toNetworkKind()).use { change ->
                        Persister.newInMemory().use { persister ->
                            Wallet(receive, change, Network.TESTNET, persister).use { native ->
                                assertEquals(Network.TESTNET, native.network())
                            }
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
            }
        }
    }
}
