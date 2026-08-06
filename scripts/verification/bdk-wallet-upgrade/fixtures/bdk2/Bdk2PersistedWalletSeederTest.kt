package net.clench.wallet.verification.bdkupgrade

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.runBlocking
import net.clench.wallet.ClenchApplication
import net.clench.wallet.data.local.entity.WalletEntity
import org.bitcoindevkit.AddressInfo
import org.bitcoindevkit.Balance
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.UnconfirmedTx
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
        val expected = try {
            external = Descriptor(descriptors.external, Network.TESTNET)
            internal = Descriptor(descriptors.internal, Network.TESTNET)
            persister = Persister.newSqlite(database.absolutePath)
            wallet = Wallet(external, internal, Network.TESTNET, persister)
            external.close()
            external = null
            internal.close()
            internal = null

            repeat(EXTERNAL_ADDRESS_COUNT) {
                var addressInfo: AddressInfo? = null
                try {
                    addressInfo = wallet.revealNextAddress(KeychainKind.EXTERNAL)
                    externalAddresses += addressInfo.address.toString()
                } finally {
                    addressInfo?.destroy()
                }
            }
            repeat(INTERNAL_ADDRESS_COUNT) {
                var addressInfo: AddressInfo? = null
                try {
                    addressInfo = wallet.revealNextAddress(KeychainKind.INTERNAL)
                    internalAddresses += addressInfo.address.toString()
                } finally {
                    addressInfo?.destroy()
                }
            }

            applyDeterministicOfflineTransaction(wallet, externalAddresses.first())
            wallet.persist(persister)
            require(database.isFile && database.length() > 0L) { "BDK fixture database missing" }

            val expected = observeWallet(wallet, descriptors, externalAddresses, internalAddresses)
            requireDeterministicFixture(expected)
            expected
        } finally {
            closeAllOrFail(
                { wallet?.close() },
                { persister?.close() },
                { internal?.close() },
                { external?.close() }
            )
        }
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

        val actual = observeWallet(
            wallet = wallet,
            descriptors = PublicDescriptors(expected.externalDescriptor, expected.internalDescriptor),
            externalAddresses = expected.externalAddresses,
            internalAddresses = expected.internalAddresses
        )
        requireSame(expected, actual, "persisted public wallet state")
    }

    private fun applyDeterministicOfflineTransaction(wallet: Wallet, receivingAddress: String) {
        var address: org.bitcoindevkit.Address? = null
        var script: org.bitcoindevkit.Script? = null
        var transaction: Transaction? = null
        var update: UnconfirmedTx? = null
        try {
            address = org.bitcoindevkit.Address(receivingAddress, Network.TESTNET)
            script = address.scriptPubkey()
            transaction = Transaction(buildFundingTransaction(script.toBytes()))
            update = UnconfirmedTx(transaction, FIXTURE_LAST_SEEN)
            transaction = null // Ownership moved into the recursively disposable update record.
            wallet.applyUnconfirmedTxs(listOf(update))
        } finally {
            closeAllOrFail(
                { update?.destroy() },
                { transaction?.close() },
                { script?.close() },
                { address?.close() }
            )
        }
    }

    private fun buildFundingTransaction(scriptPubkey: ByteArray): ByteArray {
        require(scriptPubkey.isNotEmpty() && scriptPubkey.size < 0xfd) {
            "Unexpected fixture script length"
        }
        return ByteArrayOutputStream().use { output ->
            output.write(intToLittleEndian(2)) // transaction version
            output.write(1) // one input
            output.write(ByteArray(32) { 0x11.toByte() }) // deterministic, nonexistent previous txid
            output.write(intToLittleEndian(1)) // previous output index
            output.write(0) // empty scriptSig
            output.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
            output.write(1) // one output
            output.write(longToLittleEndian(FIXTURE_VALUE_SAT))
            output.write(scriptPubkey.size)
            output.write(scriptPubkey)
            output.write(intToLittleEndian(0)) // lock time
            output.toByteArray()
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray = ByteArray(4) { index ->
        ((value ushr (index * 8)) and 0xff).toByte()
    }

    private fun longToLittleEndian(value: Long): ByteArray = ByteArray(8) { index ->
        ((value ushr (index * 8)) and 0xff).toByte()
    }

    private fun observeWallet(
        wallet: Wallet,
        descriptors: PublicDescriptors,
        externalAddresses: List<String>,
        internalAddresses: List<String>
    ): PublicWalletEvidence {
        val balance = balanceEvidence(wallet)
        val transaction = transactionEvidence(wallet)
        val output = outputEvidence(wallet)
        val checkpoint = checkpointEvidence(wallet)
        val nextUnused = nextUnusedExternalAddress(wallet)
        return PublicWalletEvidence(
            externalDescriptor = descriptors.external,
            internalDescriptor = descriptors.internal,
            externalAddresses = externalAddresses,
            internalAddresses = internalAddresses,
            externalLastIndex = (EXTERNAL_ADDRESS_COUNT - 1).toUInt(),
            internalLastIndex = (INTERNAL_ADDRESS_COUNT - 1).toUInt(),
            nextUnusedExternalIndex = nextUnused.index,
            nextUnusedExternalAddress = nextUnused.address,
            confirmedBalanceSat = balance.confirmed,
            trustedPendingBalanceSat = balance.trustedPending,
            untrustedPendingBalanceSat = balance.untrustedPending,
            totalBalanceSat = balance.total,
            transactionCount = transaction.count,
            transactionTxid = transaction.txid,
            transactionPosition = transaction.position,
            transactionLastSeen = transaction.lastSeen,
            unspentCount = output.count,
            unspentOutpoint = output.outpoint,
            unspentValueSat = output.valueSat,
            unspentScriptSha256 = output.scriptSha256,
            checkpointHeight = checkpoint.height,
            checkpointHash = checkpoint.hash
        )
    }

    private fun balanceEvidence(wallet: Wallet): BalanceEvidence {
        var balance: Balance? = null
        return try {
            balance = wallet.balance()
            BalanceEvidence(
                confirmed = balance.confirmed.toSat().toLong(),
                trustedPending = balance.trustedPending.toSat().toLong(),
                untrustedPending = balance.untrustedPending.toSat().toLong(),
                total = balance.total.toSat().toLong()
            )
        } finally {
            balance?.destroy()
        }
    }

    private fun transactionEvidence(wallet: Wallet): TransactionEvidence {
        val transactions = wallet.transactions()
        return try {
            require(transactions.size == 1) { "Fixture must contain exactly one transaction" }
            val canonical = transactions.single()
            var txid: org.bitcoindevkit.Txid? = null
            try {
                txid = canonical.transaction.computeTxid()
                val position = canonical.chainPosition as? ChainPosition.Unconfirmed
                    ?: throw AssertionError("Fixture transaction must be unconfirmed")
                val lastSeen = position.timestamp
                    ?: throw AssertionError("Fixture transaction missing last-seen timestamp")
                TransactionEvidence(
                    count = transactions.size,
                    txid = txid.toString(),
                    position = UNCONFIRMED,
                    lastSeen = lastSeen
                )
            } finally {
                txid?.close()
            }
        } finally {
            transactions.forEach { it.destroy() }
        }
    }

    private fun outputEvidence(wallet: Wallet): OutputEvidence {
        val outputs = wallet.listUnspent()
        return try {
            require(outputs.size == 1) { "Fixture must contain exactly one unspent output" }
            val output = outputs.single()
            val position = output.chainPosition as? ChainPosition.Unconfirmed
                ?: throw AssertionError("Fixture output must be unconfirmed")
            val lastSeen = position.timestamp
                ?: throw AssertionError("Fixture output missing last-seen timestamp")
            requireSame(FIXTURE_LAST_SEEN, lastSeen, "unspent output last-seen timestamp")
            requireSame(KeychainKind.EXTERNAL, output.keychain, "unspent output keychain")
            requireSame(0u, output.derivationIndex, "unspent output derivation index")
            require(!output.isSpent) { "Fixture output unexpectedly spent" }
            OutputEvidence(
                count = outputs.size,
                outpoint = "${output.outpoint.txid}:${output.outpoint.vout}",
                valueSat = output.txout.value.toSat().toLong(),
                scriptSha256 = sha256(output.txout.scriptPubkey.toBytes())
            )
        } finally {
            outputs.forEach { it.destroy() }
        }
    }

    private fun checkpointEvidence(wallet: Wallet): CheckpointEvidence {
        var checkpoint: org.bitcoindevkit.BlockId? = null
        return try {
            checkpoint = wallet.latestCheckpoint()
            CheckpointEvidence(checkpoint.height, checkpoint.hash.toString())
        } finally {
            checkpoint?.destroy()
        }
    }

    private fun nextUnusedExternalAddress(wallet: Wallet): DerivedAddress {
        var addressInfo: AddressInfo? = null
        return try {
            addressInfo = wallet.nextUnusedAddress(KeychainKind.EXTERNAL)
            DerivedAddress(addressInfo.index, addressInfo.address.toString())
        } finally {
            addressInfo?.destroy()
        }
    }

    private fun requireDeterministicFixture(evidence: PublicWalletEvidence) {
        require(Regex("^[0-9a-f]{64}$").matches(TESTNET_GENESIS_HASH)) {
            "Invalid expected Testnet3 genesis hash"
        }
        requireSame(0L, evidence.confirmedBalanceSat, "confirmed balance")
        requireSame(0L, evidence.trustedPendingBalanceSat, "trusted-pending balance")
        requireSame(FIXTURE_VALUE_SAT, evidence.untrustedPendingBalanceSat, "untrusted-pending balance")
        requireSame(FIXTURE_VALUE_SAT, evidence.totalBalanceSat, "total balance")
        requireSame(1, evidence.transactionCount, "transaction count")
        requireSame(UNCONFIRMED, evidence.transactionPosition, "transaction position")
        requireSame(FIXTURE_LAST_SEEN, evidence.transactionLastSeen, "transaction last-seen")
        requireSame(1, evidence.unspentCount, "unspent count")
        requireSame("${evidence.transactionTxid}:0", evidence.unspentOutpoint, "unspent outpoint")
        requireSame(FIXTURE_VALUE_SAT, evidence.unspentValueSat, "unspent value")
        requireSame(0u, evidence.checkpointHeight, "latest checkpoint height")
        requireSame(TESTNET_GENESIS_HASH, evidence.checkpointHash, "latest checkpoint hash")
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
            setProperty("next_unused_external_index", evidence.nextUnusedExternalIndex.toString())
            setProperty("next_unused_external_address", evidence.nextUnusedExternalAddress)
            setProperty("balance_confirmed_sat", evidence.confirmedBalanceSat.toString())
            setProperty("balance_trusted_pending_sat", evidence.trustedPendingBalanceSat.toString())
            setProperty("balance_untrusted_pending_sat", evidence.untrustedPendingBalanceSat.toString())
            setProperty("balance_total_sat", evidence.totalBalanceSat.toString())
            setProperty("transaction_count", evidence.transactionCount.toString())
            setProperty("transaction_txid", evidence.transactionTxid)
            setProperty("transaction_position", evidence.transactionPosition)
            setProperty("transaction_last_seen", evidence.transactionLastSeen.toString())
            setProperty("unspent_count", evidence.unspentCount.toString())
            setProperty("unspent_outpoint", evidence.unspentOutpoint)
            setProperty("unspent_value_sat", evidence.unspentValueSat.toString())
            setProperty("unspent_script_sha256", evidence.unspentScriptSha256)
            setProperty("checkpoint_height", evidence.checkpointHeight.toString())
            setProperty("checkpoint_hash", evidence.checkpointHash)
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
        val nextUnusedExternalIndex: UInt,
        val nextUnusedExternalAddress: String,
        val confirmedBalanceSat: Long,
        val trustedPendingBalanceSat: Long,
        val untrustedPendingBalanceSat: Long,
        val totalBalanceSat: Long,
        val transactionCount: Int,
        val transactionTxid: String,
        val transactionPosition: String,
        val transactionLastSeen: ULong,
        val unspentCount: Int,
        val unspentOutpoint: String,
        val unspentValueSat: Long,
        val unspentScriptSha256: String,
        val checkpointHeight: UInt,
        val checkpointHash: String
    )

    private data class BalanceEvidence(
        val confirmed: Long,
        val trustedPending: Long,
        val untrustedPending: Long,
        val total: Long
    )

    private data class TransactionEvidence(
        val count: Int,
        val txid: String,
        val position: String,
        val lastSeen: ULong
    )

    private data class OutputEvidence(
        val count: Int,
        val outpoint: String,
        val valueSat: Long,
        val scriptSha256: String
    )

    private data class CheckpointEvidence(val height: UInt, val hash: String)
    private data class DerivedAddress(val index: UInt, val address: String)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    private companion object {
        const val TARGET_PACKAGE = "net.clench.wallet.debug"
        const val FIXTURE_VERSION = "2"
        const val PRODUCER_BDK = "2.3.1"
        const val TESTNET = "testnet"
        const val WALLET_ID = "00000000-0000-4000-8000-000000000326"
        const val DATABASE_NAME = "wallet_$WALLET_ID.db"
        const val EVIDENCE_FILE = "bdk2-to-bdk3-public-evidence.properties"
        const val PHASE_ONE_FILE = "bdk2-to-bdk3-phase-one.properties"
        const val RESULT_FILE = "bdk2-to-bdk3-result.properties"
        const val EXTERNAL_ADDRESS_COUNT = 3
        const val INTERNAL_ADDRESS_COUNT = 2
        const val FIXTURE_VALUE_SAT = 50_000L
        val FIXTURE_LAST_SEEN = 1_700_000_326uL
        const val UNCONFIRMED = "unconfirmed"
        const val TESTNET_GENESIS_HASH =
            "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"

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
            "next_unused_external_index",
            "next_unused_external_address",
            "balance_confirmed_sat",
            "balance_trusted_pending_sat",
            "balance_untrusted_pending_sat",
            "balance_total_sat",
            "transaction_count",
            "transaction_txid",
            "transaction_position",
            "transaction_last_seen",
            "unspent_count",
            "unspent_outpoint",
            "unspent_value_sat",
            "unspent_script_sha256",
            "checkpoint_height",
            "checkpoint_hash"
        )
    }
}
