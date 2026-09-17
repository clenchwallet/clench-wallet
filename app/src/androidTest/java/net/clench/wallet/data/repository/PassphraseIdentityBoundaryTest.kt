package net.clench.wallet.data.repository

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.clench.wallet.domain.model.ScriptType
import net.clench.wallet.domain.model.toNetworkKind
import net.clench.wallet.ui.viewmodel.ImportWalletViewModel
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PassphraseIdentityBoundaryTest {
    private val words = List(11) { "abandon" } + "about"
    private val cases = listOf("", " ", "   ", "\t", "ordinary", " surrounded ", "é", "e\u0301", "\u3000")

    @Test fun importPreviewStorageUnlockAndReopenedRepositoryHaveTheSameIdentity() = runBlocking {
        cases.forEach { passphrase ->
            WalletRepositoryFixture().use { f ->
                val expected = derive(passphrase)
                val completion = CompletableDeferred<String>()
                val store = ViewModelStore()
                lateinit var vm: ImportWalletViewModel
                try {
                    withContext(Dispatchers.Main) {
                        vm = ImportWalletViewModel(f.repository, f.settings, f.barrier)
                        store.put("import", vm)
                        vm.setWalletName("Disposable identity fixture")
                        vm.setInput(words.joinToString(" "))
                        vm.setPassphrase(passphrase)
                    }
                    withTimeout(20_000) { while (vm.uiState.value.masterFingerprintBytes == null) delay(25) }
                    assertEquals(expected.fingerprint, vm.uiState.value.masterFingerprintBytes!!.hex())
                    withContext(Dispatchers.Main) { vm.importWallet { completion.complete(it) } }
                    val id = withTimeout(30_000) {
                        while (!completion.isCompleted) {
                            vm.uiState.value.error?.let { throw AssertionError(it) }
                            delay(25)
                        }
                        completion.await()
                    }
                    assertEquals(expected.receive, f.database.walletDao().getById(id)!!.descriptor)
                    assertEquals(expected.change, f.database.walletDao().getById(id)!!.changeDescriptor)
                    assertEquals(passphrase.isNotEmpty(), f.database.walletDao().getById(id)!!.hasPassphrase)
                    assertEquals(passphrase.isEmpty(), f.keystore.getSecretDescriptor(id) != null)
                    if (passphrase.isNotEmpty()) {
                        assertFalse(f.context.getDatabasePath("wallet_$id.db").exists())
                        assertFalse(f.repository.isPassphraseWalletUnlocked(id))
                        f.repository.unlockPassphraseWallet(id, passphrase)
                        assertEquals(expected.address, f.repository.getLastAddress(id).address)
                        assertEquals(expected.fingerprint, f.repository.getPassphraseFingerprint(id, passphrase)!!.second.hex())
                        assertFalse(f.context.getDatabasePath("wallet_$id.db").exists())
                    }
                    f.restartRepositoryAndRoom()
                    assertEquals(expected.receive, f.database.walletDao().getById(id)!!.descriptor)
                    assertEquals(passphrase.isNotEmpty(), f.database.walletDao().getById(id)!!.hasPassphrase)
                    if (passphrase.isNotEmpty()) {
                        assertFalse(f.repository.isPassphraseWalletUnlocked(id))
                        assertNull(f.keystore.getSecretDescriptor(id))
                        f.repository.unlockPassphraseWallet(id, passphrase)
                    }
                    assertEquals(expected.address, f.repository.getLastAddress(id).address)
                } finally {
                    withContext(Dispatchers.Main) { store.clear() }
                }
            }
        }
    }

    @Test fun creationAndWatchOnlyConversionPreserveWhitespaceWithoutReinterpretingExistingWallets() = runBlocking {
        WalletRepositoryFixture().use { f ->
            val blank = derive(" ")
            val empty = f.repository.createWallet("Existing empty", 12, null, words, ScriptType.NATIVE_SEGWIT).second
            val emptyDescriptor = empty.descriptor
            val created = f.repository.createWallet("Space", 12, " ", words, ScriptType.NATIVE_SEGWIT).second
            assertEquals(blank.receive, created.descriptor)
            assertTrue(created.hasPassphrase)
            assertNull(f.keystore.getSecretDescriptor(created.id))
            assertFalse(f.context.getDatabasePath("wallet_${created.id}.db").exists())
            f.repository.deleteWallet(created.id)
            val watch = f.repository.importWatchOnly("Watch whitespace", blank.receive)
            f.repository.convertWatchOnlyToHot(watch.id, words, " ")
            assertTrue(f.database.walletDao().getById(watch.id)!!.hasPassphrase)
            assertNull(f.keystore.getSecretDescriptor(watch.id))
            assertFalse(f.context.getDatabasePath("wallet_${watch.id}.db").exists())
            assertEquals(blank.address, f.repository.getLastAddress(watch.id).address)
            assertEquals(emptyDescriptor, f.database.walletDao().getById(empty.id)!!.descriptor)
            assertFalse(f.database.walletDao().getById(empty.id)!!.hasPassphrase)
        }
    }

    @Test fun nativeBip39NormalizationIsTheSameForComposedAndDecomposedInput() {
        assertEquals(derive("é"), derive("e\u0301"))
        assertEquals(derive(" "), derive("\u3000"))
        assertNotEquals(derive(""), derive(" "))
    }

    private data class Identity(val receive: String, val change: String, val fingerprint: String, val address: String)
    private fun derive(passphrase: String): Identity = Mnemonic.fromString(words.joinToString(" ")).use { mnemonic ->
        DescriptorSecretKey(Network.TESTNET.toNetworkKind(), mnemonic, passphrase).use { key ->
            Descriptor.newBip84(key, KeychainKind.EXTERNAL, Network.TESTNET.toNetworkKind()).use { receive ->
                Descriptor.newBip84(key, KeychainKind.INTERNAL, Network.TESTNET.toNetworkKind()).use { change ->
                    Persister.newInMemory().use { persister ->
                        Wallet(receive, change, Network.TESTNET, persister).use { wallet ->
                            val address = wallet.peekAddress(KeychainKind.EXTERNAL, 0u)
                            try {
                                Identity(receive.toString(), change.toString(),
                                    Regex("\\[([a-fA-F0-9]{8})/").find(receive.toString())!!.groupValues[1].lowercase(),
                                    address.address.toString())
                            } finally { address.destroy() }
                        }
                    }
                }
            }
        }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
