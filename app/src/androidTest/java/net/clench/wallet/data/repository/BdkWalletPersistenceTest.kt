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
        var receive: AddressInfo? = null
        var changeAddress: AddressInfo? = null

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

            closeOrFail(
                nativeCloseAction(change) { it.close() },
                nativeCloseAction(external) { it.close() },
                nativeCloseAction(secretKey) { it.destroy() },
                nativeCloseAction(mnemonic) { it.destroy() }
            )
            change = null
            external = null
            secretKey = null
            mnemonic = null

            receive = wallet.revealNextAddress(KeychainKind.EXTERNAL)
            changeAddress = wallet.revealNextAddress(KeychainKind.INTERNAL)
            wallet.persist(persister)

            return PersistedAddresses(
                externalDescriptor = externalPublic,
                changeDescriptor = changePublic,
                receiveIndex = receive.index,
                receiveAddress = receive.address.toString(),
                changeIndex = changeAddress.index,
                changeAddress = changeAddress.address.toString()
            )
        } finally {
            closeOrFail(
                nativeCloseAction(changeAddress) { it.destroy() },
                nativeCloseAction(receive) { it.destroy() },
                nativeCloseAction(wallet) { it.close() },
                nativeCloseAction(persister) { it.close() },
                nativeCloseAction(change) { it.close() },
                nativeCloseAction(external) { it.close() },
                nativeCloseAction(secretKey) { it.destroy() },
                nativeCloseAction(mnemonic) { it.destroy() }
            )
        }
    }

    private fun assertReloadedAddresses(persisted: PersistedAddresses) {
        var external: Descriptor? = null
        var change: Descriptor? = null
        var persister: Persister? = null
        var wallet: Wallet? = null
        var receive: AddressInfo? = null
        var changeAddress: AddressInfo? = null

        try {
            external = Descriptor(persisted.externalDescriptor, Network.TESTNET.toNetworkKind())
            change = Descriptor(persisted.changeDescriptor, Network.TESTNET.toNetworkKind())
            persister = Persister.newSqlite(testDatabase.absolutePath)
            wallet = Wallet.load(external, change, persister)

            closeOrFail(
                nativeCloseAction(change) { it.close() },
                nativeCloseAction(external) { it.close() }
            )
            change = null
            external = null

            receive = wallet.peekAddress(KeychainKind.EXTERNAL, persisted.receiveIndex)
            changeAddress = wallet.peekAddress(KeychainKind.INTERNAL, persisted.changeIndex)

            assertEquals(Network.TESTNET, wallet.network())
            assertEquals(persisted.receiveAddress, receive.address.toString())
            assertEquals(persisted.changeAddress, changeAddress.address.toString())
        } finally {
            closeOrFail(
                nativeCloseAction(changeAddress) { it.destroy() },
                nativeCloseAction(receive) { it.destroy() },
                nativeCloseAction(wallet) { it.close() },
                nativeCloseAction(persister) { it.close() },
                nativeCloseAction(change) { it.close() },
                nativeCloseAction(external) { it.close() }
            )
        }
    }

    private fun closeOrFail(vararg actions: NativeWalletResourceCleanup.CloseAction?) {
        operationBarrier.closeNativeResourcesOrFail(actions.filterNotNull())
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
        val receiveIndex: UInt,
        val receiveAddress: String,
        val changeIndex: UInt,
        val changeAddress: String
    )

    private companion object {
        const val TEST_DATABASE = "bdk3-wallet-persistence"
        const val TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
