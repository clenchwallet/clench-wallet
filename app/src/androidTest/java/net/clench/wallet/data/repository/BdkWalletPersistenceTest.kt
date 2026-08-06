package net.clench.wallet.data.repository

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.clench.wallet.domain.model.toNetworkKind
import org.bitcoindevkit.AddressInfo
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BdkWalletPersistenceTest {
    private lateinit var context: Context
    private val operationBarrier = SensitiveWalletOperationBarrier()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteTestDatabase()
    }

    @After
    fun tearDown() {
        deleteTestDatabase()
    }

    @Test
    fun sqliteWalletReloadsExactRevealedTestnetAddresses() {
        operationBarrier.withSynchronousLease { lease ->
            operationBarrier.assertActive(lease)
            val persisted = createAndPersistWallet()
            operationBarrier.assertActive(lease)
            assertReloadedAddresses(persisted)
        }
        assertTrue(operationBarrier.isOpen())
        assertTrue(!operationBarrier.hasQuarantinedNativeResources())
    }

    private fun createAndPersistWallet(): PersistedAddresses {
        var mnemonic: Mnemonic? = null
        var secretKey: DescriptorSecretKey? = null
        var external: Descriptor? = null
        var change: Descriptor? = null
        var persister: Persister? = null
        var wallet: Wallet? = null
        val revealedAddresses = mutableListOf<AddressInfo>()

        try {
            mnemonic = Mnemonic.fromString(TEST_MNEMONIC)
            secretKey = DescriptorSecretKey(Network.TESTNET.toNetworkKind(), mnemonic, "")
            external = Descriptor.newBip84(
                secretKey,
                KeychainKind.EXTERNAL,
                Network.TESTNET.toNetworkKind()
            )
            change = Descriptor.newBip84(
                secretKey,
                KeychainKind.INTERNAL,
                Network.TESTNET.toNetworkKind()
            )

            val externalPublic = external.toString()
            val changePublic = change.toString()
            assertTrue(!externalPublic.contains("tprv"))
            assertTrue(!changePublic.contains("tprv"))

            persister = Persister.newSqlite(testDatabase.absolutePath)
            wallet = Wallet(external, change, Network.TESTNET, persister)

            val descriptorCleanup = listOfNotNull(
                nativeCloseAction(change) { it.close() },
                nativeCloseAction(external) { it.close() },
                nativeCloseAction(secretKey) { it.destroy() },
                nativeCloseAction(mnemonic) { it.destroy() }
            )
            // Ownership moves to the barrier before any close starts. If one sibling close fails,
            // the finally block must never retry another wrapper that was already closed.
            change = null
            external = null
            secretKey = null
            mnemonic = null
            closeOrFail(descriptorCleanup)

            val receiveAddresses = revealAddresses(
                wallet,
                KeychainKind.EXTERNAL,
                revealedAddresses
            )
            val changeAddresses = revealAddresses(
                wallet,
                KeychainKind.INTERNAL,
                revealedAddresses
            )
            assertTrue(wallet.persist(persister))
            assertTrue(testDatabase.isFile)
            assertTrue(testDatabase.length() > 0L)

            return PersistedAddresses(
                externalDescriptor = externalPublic,
                changeDescriptor = changePublic,
                receiveAddresses = receiveAddresses,
                changeAddresses = changeAddresses
            )
        } finally {
            val cleanup = buildList {
                revealedAddresses.asReversed().forEach { addressInfo ->
                    add(checkNotNull(nativeCloseAction(addressInfo) { it.destroy() }))
                }
                listOfNotNull(
                    nativeCloseAction(wallet) { it.close() },
                    nativeCloseAction(persister) { it.close() },
                    nativeCloseAction(change) { it.close() },
                    nativeCloseAction(external) { it.close() },
                    nativeCloseAction(secretKey) { it.destroy() },
                    nativeCloseAction(mnemonic) { it.destroy() }
                ).forEach(::add)
            }
            revealedAddresses.clear()
            wallet = null
            persister = null
            change = null
            external = null
            secretKey = null
            mnemonic = null
            closeOrFail(cleanup)
        }
    }

    private fun assertReloadedAddresses(persisted: PersistedAddresses) {
        var external: Descriptor? = null
        var change: Descriptor? = null
        var persister: Persister? = null
        var wallet: Wallet? = null
        val derivedAddresses = mutableListOf<AddressInfo>()

        try {
            external = Descriptor(persisted.externalDescriptor, Network.TESTNET.toNetworkKind())
            change = Descriptor(persisted.changeDescriptor, Network.TESTNET.toNetworkKind())
            persister = Persister.newSqlite(testDatabase.absolutePath)
            wallet = Wallet.load(external, change, persister)

            val descriptorCleanup = listOfNotNull(
                nativeCloseAction(change) { it.close() },
                nativeCloseAction(external) { it.close() }
            )
            change = null
            external = null
            closeOrFail(descriptorCleanup)

            assertEquals(Network.TESTNET, wallet.network())
            assertReloadedKeychain(
                wallet,
                KeychainKind.EXTERNAL,
                persisted.receiveAddresses,
                derivedAddresses
            )
            assertReloadedKeychain(
                wallet,
                KeychainKind.INTERNAL,
                persisted.changeAddresses,
                derivedAddresses
            )
        } finally {
            val cleanup = buildList {
                derivedAddresses.asReversed().forEach { addressInfo ->
                    add(checkNotNull(nativeCloseAction(addressInfo) { it.destroy() }))
                }
                listOfNotNull(
                    nativeCloseAction(wallet) { it.close() },
                    nativeCloseAction(persister) { it.close() },
                    nativeCloseAction(change) { it.close() },
                    nativeCloseAction(external) { it.close() }
                ).forEach(::add)
            }
            derivedAddresses.clear()
            wallet = null
            persister = null
            change = null
            external = null
            closeOrFail(cleanup)
        }
    }

    private fun revealAddresses(
        wallet: Wallet,
        keychain: KeychainKind,
        ownedAddresses: MutableList<AddressInfo>
    ): List<DerivedAddress> {
        val addresses = List(REVEALED_ADDRESSES_PER_KEYCHAIN) {
            wallet.revealNextAddress(keychain).also(ownedAddresses::add).let { addressInfo ->
                DerivedAddress(addressInfo.index, addressInfo.address.toString())
            }
        }
        assertEquals(addresses.first().index + 1u, addresses.last().index)
        assertTrue(addresses.first().address != addresses.last().address)
        return addresses
    }

    private fun assertReloadedKeychain(
        wallet: Wallet,
        keychain: KeychainKind,
        persisted: List<DerivedAddress>,
        ownedAddresses: MutableList<AddressInfo>
    ) {
        val last = persisted.last()
        assertEquals(last.index, wallet.derivationIndex(keychain))
        persisted.forEach { expected ->
            val actual = wallet.peekAddress(keychain, expected.index)
                .also(ownedAddresses::add)
            assertEquals(expected.address, actual.address.toString())
        }

        val next = wallet.revealNextAddress(keychain).also(ownedAddresses::add)
        assertEquals(last.index + 1u, next.index)
        assertTrue(next.address.toString() != last.address)
    }

    private fun closeOrFail(actions: Collection<NativeWalletResourceCleanup.CloseAction>) {
        operationBarrier.closeNativeResourcesOrFail(actions)
    }

    private fun deleteTestDatabase() {
        context.deleteDatabase(TEST_DATABASE)
        context.getDatabasePath("$TEST_DATABASE-journal").delete()
        context.getDatabasePath("$TEST_DATABASE-shm").delete()
        context.getDatabasePath("$TEST_DATABASE-wal").delete()
    }

    private val testDatabase
        get() = context.getDatabasePath(TEST_DATABASE)

    private data class PersistedAddresses(
        val externalDescriptor: String,
        val changeDescriptor: String,
        val receiveAddresses: List<DerivedAddress>,
        val changeAddresses: List<DerivedAddress>
    )

    private data class DerivedAddress(
        val index: UInt,
        val address: String
    )

    private companion object {
        const val TEST_DATABASE = "bdk3-wallet-persistence"
        const val REVEALED_ADDRESSES_PER_KEYCHAIN = 2
        const val TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
