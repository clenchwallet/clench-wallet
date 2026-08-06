package net.clench.wallet.verification.bdkupgrade

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import kotlinx.coroutines.runBlocking
import net.clench.wallet.ClenchApplication
import net.clench.wallet.data.local.entity.WalletEntity
import org.bitcoindevkit.AddressInfo
import org.bitcoindevkit.Balance
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test-only BDK 2.3.1 producer for the cross-version, install-in-place upgrade gate.
 *
 * The mnemonic below is the public BIP-39 "abandon" test vector. It is deliberately embedded
 * only in the instrumentation APK, is never suitable for real funds, and is destroyed before a
 * wallet is constructed. The production APK never contains it. Only public descriptors and
 * unfunded public wallet state reach Room, the BDK SQLite file, or the comparison evidence.
 */
@RunWith(AndroidJUnit4::class)
class Bdk2PersistedWalletSeederTest {
    @Test
    fun seedUnfundedBdk2WalletForInPlaceUpgrade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == TARGET_PACKAGE) { "Unexpected instrumentation target" }
        deleteFixture(context)

        val descriptors = derivePublicDescriptors()
        seedRoomMetadata(context, descriptors)
        val expected = createAndReloadBdk2Wallet(context, descriptors)
        writeEvidenceAtomically(context, expected)
    }

    private fun derivePublicDescriptors(): PublicDescriptors {
        var mnemonic: Mnemonic? = null
        var secretKey: DescriptorSecretKey? = null
        var secretExternal: Descriptor? = null
        var secretInternal: Descriptor? = null
        try {
            mnemonic = Mnemonic.fromString(PUBLIC_NON_PRODUCTION_TEST_MNEMONIC)
            secretKey = DescriptorSecretKey(Network.TESTNET, mnemonic, "")
            secretExternal = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, Network.TESTNET)
            secretInternal = Descriptor.newBip84(secretKey, KeychainKind.INTERNAL, Network.TESTNET)

            val external = secretExternal.toString()
            val internal = secretInternal.toString()
            requirePublicDescriptor(external)
            requirePublicDescriptor(internal)
            return PublicDescriptors(external, internal)
        } finally {
            closeAllOrFail(
                { secretInternal?.close() },
                { secretExternal?.close() },
                { secretKey?.destroy() },
                { mnemonic?.destroy() }
            )
        }
    }

    private fun seedRoomMetadata(context: Context, descriptors: PublicDescriptors) {
        val app = context.applicationContext as? ClenchApplication
            ?: throw AssertionError("Unexpected target application")
        runBlocking {
            app.walletDao.insert(
                WalletEntity(
                    id = WALLET_ID,
                    name = "PUBLIC TEST FIXTURE - NEVER FUND",
                    descriptor = descriptors.external,
                    changeDescriptor = descriptors.internal,
                    isWatchOnly = true,
                    isMultisig = false,
                    createdAtEpochMs = 0L,
                    network = TESTNET
                )
            )
        }
    }

    private fun createAndReloadBdk2Wallet(
        context: Context,
        descriptors: PublicDescriptors
    ): PublicWalletEvidence {
        val database = context.getDatabasePath(DATABASE_NAME)
        database.parentFile?.mkdirs()

        var external: Descriptor? = null
        var internal: Descriptor? = null
        var persister: Persister? = null
        var wallet: Wallet? = null
        val externalAddresses = mutableListOf<String>()
        val internalAddresses = mutableListOf<String>()
        try {
            external = Descriptor(descriptors.external, Network.TESTNET)
            internal = Descriptor(descriptors.internal, Network.TESTNET)
            persister = Persister.newSqlite(database.absolutePath)
            wallet = Wallet(external, internal, Network.TESTNET, persister)
            external.close()
            external = null
            internal.close()
            internal = null

            repeat(EXTERNAL_ADDRESS_COUNT) {
                val addressInfo = wallet.revealNextAddress(KeychainKind.EXTERNAL)
                externalAddresses += addressInfo.address.toString()
                addressInfo.destroy()
            }
            repeat(INTERNAL_ADDRESS_COUNT) {
                val addressInfo = wallet.revealNextAddress(KeychainKind.INTERNAL)
                internalAddresses += addressInfo.address.toString()
                addressInfo.destroy()
            }
            wallet.persist(persister)
            require(database.isFile && database.length() > 0L) { "BDK fixture database missing" }
        } finally {
            closeAllOrFail(
                { wallet?.close() },
                { persister?.close() },
                { internal?.close() },
                { external?.close() }
            )
        }

        val expected = PublicWalletEvidence(
            externalDescriptor = descriptors.external,
            internalDescriptor = descriptors.internal,
            externalAddresses = externalAddresses,
            internalAddresses = internalAddresses,
            externalLastIndex = (EXTERNAL_ADDRESS_COUNT - 1).toUInt(),
            internalLastIndex = (INTERNAL_ADDRESS_COUNT - 1).toUInt(),
            totalBalanceSat = 0L,
            transactionCount = 0,
            unspentCount = 0
        )
        verifyBdk2Reload(database, expected)
        return expected
    }

    private fun verifyBdk2Reload(database: File, expected: PublicWalletEvidence) {
        var external: Descriptor? = null
        var internal: Descriptor? = null
        var persister: Persister? = null
        var wallet: Wallet? = null
        try {
            external = Descriptor(expected.externalDescriptor, Network.TESTNET)
            internal = Descriptor(expected.internalDescriptor, Network.TESTNET)
            persister = Persister.newSqlite(database.absolutePath)
            wallet = Wallet.load(external, internal, persister)
            external.close()
            external = null
            internal.close()
            internal = null

            requireWalletIdentity(wallet, expected)
        } finally {
            closeAllOrFail(
                { wallet?.close() },
                { persister?.close() },
                { internal?.close() },
                { external?.close() }
            )
        }
    }

    private fun requireWalletIdentity(wallet: Wallet, expected: PublicWalletEvidence) {
        requireSame(expected.externalDescriptor, wallet.publicDescriptor(KeychainKind.EXTERNAL), "external descriptor")
        requireSame(expected.internalDescriptor, wallet.publicDescriptor(KeychainKind.INTERNAL), "internal descriptor")
        requireSame(Network.TESTNET, wallet.network(), "network")
        requireSame(expected.externalLastIndex, wallet.derivationIndex(KeychainKind.EXTERNAL), "external derivation index")
        requireSame(expected.internalLastIndex, wallet.derivationIndex(KeychainKind.INTERNAL), "internal derivation index")

        expected.externalAddresses.forEachIndexed { index, expectedAddress ->
            requirePeekedAddress(wallet, KeychainKind.EXTERNAL, index, expectedAddress)
        }
        expected.internalAddresses.forEachIndexed { index, expectedAddress ->
            requirePeekedAddress(wallet, KeychainKind.INTERNAL, index, expectedAddress)
        }

        requireSame(expected.totalBalanceSat, totalBalanceSat(wallet), "balance")
        requireSame(expected.transactionCount, transactionCount(wallet), "transaction history")
        requireSame(expected.unspentCount, unspentCount(wallet), "unspent outputs")
    }

    private fun requirePeekedAddress(
        wallet: Wallet,
        keychain: KeychainKind,
        index: Int,
        expectedAddress: String
    ) {
        var addressInfo: AddressInfo? = null
        try {
            addressInfo = wallet.peekAddress(keychain, index.toUInt())
            requireSame(expectedAddress, addressInfo.address.toString(), "derived address")
        } finally {
            addressInfo?.destroy()
        }
    }

    private fun totalBalanceSat(wallet: Wallet): Long {
        var balance: Balance? = null
        return try {
            balance = wallet.balance()
            balance.total.toSat().toLong()
        } finally {
            balance?.destroy()
        }
    }

    private fun transactionCount(wallet: Wallet): Int {
        val transactions = wallet.transactions()
        return try {
            transactions.size
        } finally {
            transactions.forEach { it.destroy() }
        }
    }

    private fun unspentCount(wallet: Wallet): Int {
        val outputs = wallet.listUnspent()
        return try {
            outputs.size
        } finally {
            outputs.forEach { it.destroy() }
        }
    }

    private fun writeEvidenceAtomically(context: Context, evidence: PublicWalletEvidence) {
        val properties = Properties().apply {
            setProperty("fixture_version", FIXTURE_VERSION)
            setProperty("producer_bdk", PRODUCER_BDK)
            setProperty("wallet_id", WALLET_ID)
            setProperty("database_name", DATABASE_NAME)
            setProperty("network", TESTNET)
            setProperty("external_descriptor", evidence.externalDescriptor)
            setProperty("internal_descriptor", evidence.internalDescriptor)
            setProperty("external_addresses", evidence.externalAddresses.joinToString(","))
            setProperty("internal_addresses", evidence.internalAddresses.joinToString(","))
            setProperty("external_last_index", evidence.externalLastIndex.toString())
            setProperty("internal_last_index", evidence.internalLastIndex.toString())
            setProperty("balance_total_sat", evidence.totalBalanceSat.toString())
            setProperty("transaction_count", evidence.transactionCount.toString())
            setProperty("unspent_count", evidence.unspentCount.toString())
        }
        require(properties.stringPropertyNames() == EVIDENCE_KEYS) { "Unexpected fixture evidence schema" }

        val destination = File(context.noBackupFilesDir, EVIDENCE_FILE)
        val temporary = File(context.noBackupFilesDir, "$EVIDENCE_FILE.tmp")
        FileOutputStream(temporary).use { output ->
            properties.store(output, "Public-only deterministic BDK upgrade fixture")
            output.fd.sync()
        }
        require(temporary.renameTo(destination)) { "Could not commit fixture evidence" }
    }

    private fun deleteFixture(context: Context) {
        listOf(
            context.getDatabasePath(DATABASE_NAME),
            context.getDatabasePath("$DATABASE_NAME-journal"),
            context.getDatabasePath("$DATABASE_NAME-shm"),
            context.getDatabasePath("$DATABASE_NAME-wal")
        ).forEach { file -> require(!file.exists() || file.delete()) { "Could not reset test fixture" } }
        listOf(EVIDENCE_FILE, "$EVIDENCE_FILE.tmp", PHASE_ONE_FILE, RESULT_FILE).forEach { name ->
            val file = File(context.noBackupFilesDir, name)
            require(!file.exists() || file.delete()) { "Could not reset test evidence" }
        }
    }

    private fun requirePublicDescriptor(descriptor: String) {
        val lower = descriptor.lowercase()
        require(PRIVATE_DESCRIPTOR_MARKERS.none(lower::contains)) {
            "Test fixture descriptor unexpectedly contains private key material"
        }
    }

    private fun requireSame(expected: Any?, actual: Any?, field: String) {
        if (expected != actual) throw AssertionError("BDK upgrade fixture $field mismatch")
    }

    private fun closeAllOrFail(vararg actions: () -> Unit) {
        var firstFailure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw AssertionError("Native test fixture cleanup failed", it) }
    }

    private data class PublicDescriptors(
        val external: String,
        val internal: String
    )

    private data class PublicWalletEvidence(
        val externalDescriptor: String,
        val internalDescriptor: String,
        val externalAddresses: List<String>,
        val internalAddresses: List<String>,
        val externalLastIndex: UInt,
        val internalLastIndex: UInt,
        val totalBalanceSat: Long,
        val transactionCount: Int,
        val unspentCount: Int
    )

    private companion object {
        const val TARGET_PACKAGE = "net.clench.wallet.debug"
        const val FIXTURE_VERSION = "1"
        const val PRODUCER_BDK = "2.3.1"
        const val TESTNET = "testnet"
        const val WALLET_ID = "00000000-0000-4000-8000-000000000326"
        const val DATABASE_NAME = "wallet_$WALLET_ID.db"
        const val EVIDENCE_FILE = "bdk2-to-bdk3-public-evidence.properties"
        const val PHASE_ONE_FILE = "bdk2-to-bdk3-phase-one.properties"
        const val RESULT_FILE = "bdk2-to-bdk3-result.properties"
        const val EXTERNAL_ADDRESS_COUNT = 3
        const val INTERNAL_ADDRESS_COUNT = 2

        // Public BIP-39 test vector. NEVER USE THIS MNEMONIC WITH REAL FUNDS.
        const val PUBLIC_NON_PRODUCTION_TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        val PRIVATE_DESCRIPTOR_MARKERS = listOf("xprv", "tprv", "yprv", "zprv", "uprv", "vprv")
        val EVIDENCE_KEYS = setOf(
            "fixture_version",
            "producer_bdk",
            "wallet_id",
            "database_name",
            "network",
            "external_descriptor",
            "internal_descriptor",
            "external_addresses",
            "internal_addresses",
            "external_last_index",
            "internal_last_index",
            "balance_total_sat",
            "transaction_count",
            "unspent_count"
        )
    }
}
