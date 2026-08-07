package net.clench.wallet.verification.bdkupgrade

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.runBlocking
import net.clench.wallet.ClenchApplication
import net.clench.wallet.domain.model.WalletBalance
import org.bitcoindevkit.AddressInfo
import org.bitcoindevkit.Balance
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet
import org.junit.Test
import org.junit.runner.RunWith

/** First BDK 3.0.0 load, immediately after adb install -r replaces the BDK 2 application APK. */
@RunWith(AndroidJUnit4::class)
class Bdk3PersistedWalletVerifierPhaseOneTest {
    @Test
    fun loadBdk2WalletStateWithBdk3AfterApkReplacement() {
        Bdk3UpgradeVerifier.verifyPhaseOne()
    }
}

/** Second BDK 3.0.0 load in a distinct process, after an explicit force-stop/restart boundary. */
@RunWith(AndroidJUnit4::class)
class Bdk3PersistedWalletVerifierPhaseTwoTest {
    @Test
    fun reloadSameBdk3WalletStateAfterProcessRestart() {
        Bdk3UpgradeVerifier.verifyPhaseTwo()
    }
}

private object Bdk3UpgradeVerifier {
    fun verifyPhaseOne() {
        val context = targetContext()
        val evidence = loadEvidence(context)
        verifyPersistedIdentity(context, evidence)

        val phase = Properties().apply {
            setProperty("phase", "PASS")
            setProperty("consumer_bdk", CONSUMER_BDK)
            setProperty("process_id", Process.myPid().toString())
            setProperty("evidence_sha256", sha256(evidence.canonicalBytes()))
        }
        writePropertiesAtomically(File(context.noBackupFilesDir, PHASE_ONE_FILE), phase)
    }

    fun verifyPhaseTwo() {
        val context = targetContext()
        val evidence = loadEvidence(context)
        val phase = loadProperties(File(context.noBackupFilesDir, PHASE_ONE_FILE))
        require(phase.stringPropertyNames() == PHASE_ONE_KEYS) { "Unexpected phase-one evidence schema" }
        requireSame("PASS", phase.getProperty("phase"), "phase-one result")
        requireSame(CONSUMER_BDK, phase.getProperty("consumer_bdk"), "phase-one BDK version")
        requireSame(sha256(evidence.canonicalBytes()), phase.getProperty("evidence_sha256"), "phase-one evidence fingerprint")
        val phaseOnePid = phase.getProperty("process_id")?.toIntOrNull()
            ?: throw AssertionError("Invalid phase-one process marker")
        require(phaseOnePid != Process.myPid()) { "Verifier did not cross a process restart" }

        verifyPersistedIdentity(context, evidence)
        writeResultAtomically(context, evidence)

        require(File(context.noBackupFilesDir, EVIDENCE_FILE).delete()) {
            "Could not remove full public fixture evidence"
        }
        require(File(context.noBackupFilesDir, PHASE_ONE_FILE).delete()) {
            "Could not remove phase-one evidence"
        }
    }

    private fun targetContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.also { context ->
            require(context.packageName == TARGET_PACKAGE) { "Unexpected instrumentation target" }
        }

    private fun loadEvidence(context: Context): PublicWalletEvidence {
        val properties = loadProperties(File(context.noBackupFilesDir, EVIDENCE_FILE))
        require(properties.stringPropertyNames() == EVIDENCE_KEYS) { "Unexpected fixture evidence schema" }
        requireSame(FIXTURE_VERSION, properties.required("fixture_version"), "fixture version")
        requireSame(PRODUCER_BDK, properties.required("producer_bdk"), "producer BDK version")
        requireSame(WALLET_ID, properties.required("wallet_id"), "wallet identifier")
        requireSame(DATABASE_NAME, properties.required("database_name"), "database name")
        requireSame(TESTNET, properties.required("network"), "network metadata")

        val evidence = PublicWalletEvidence(
            externalDescriptor = properties.required("external_descriptor"),
            internalDescriptor = properties.required("internal_descriptor"),
            externalAddresses = properties.required("external_addresses").split(',').filter(String::isNotBlank),
            internalAddresses = properties.required("internal_addresses").split(',').filter(String::isNotBlank),
            externalLastIndex = properties.required("external_last_index").toUIntOrNull()
                ?: throw AssertionError("Invalid external derivation index"),
            internalLastIndex = properties.required("internal_last_index").toUIntOrNull()
                ?: throw AssertionError("Invalid internal derivation index"),
            nextUnusedExternalIndex = properties.required("next_unused_external_index").toUIntOrNull()
                ?: throw AssertionError("Invalid next-unused external index"),
            nextUnusedExternalAddress = properties.required("next_unused_external_address"),
            confirmedBalanceSat = properties.required("balance_confirmed_sat").toLongOrNull()
                ?: throw AssertionError("Invalid confirmed-balance evidence"),
            trustedPendingBalanceSat = properties.required("balance_trusted_pending_sat").toLongOrNull()
                ?: throw AssertionError("Invalid trusted-pending-balance evidence"),
            untrustedPendingBalanceSat = properties.required("balance_untrusted_pending_sat").toLongOrNull()
                ?: throw AssertionError("Invalid untrusted-pending-balance evidence"),
            totalBalanceSat = properties.required("balance_total_sat").toLongOrNull()
                ?: throw AssertionError("Invalid balance evidence"),
            transactionCount = properties.required("transaction_count").toIntOrNull()
                ?: throw AssertionError("Invalid transaction-count evidence"),
            transactionTxid = properties.required("transaction_txid"),
            transactionPosition = properties.required("transaction_position"),
            transactionLastSeen = properties.required("transaction_last_seen").toULongOrNull()
                ?: throw AssertionError("Invalid transaction last-seen evidence"),
            unspentCount = properties.required("unspent_count").toIntOrNull()
                ?: throw AssertionError("Invalid unspent-count evidence"),
            unspentOutpoint = properties.required("unspent_outpoint"),
            unspentValueSat = properties.required("unspent_value_sat").toLongOrNull()
                ?: throw AssertionError("Invalid unspent-value evidence"),
            unspentScriptSha256 = properties.required("unspent_script_sha256"),
            checkpointHeight = properties.required("checkpoint_height").toUIntOrNull()
                ?: throw AssertionError("Invalid checkpoint-height evidence"),
            checkpointHash = properties.required("checkpoint_hash")
        )
        require(evidence.externalAddresses.size == EXTERNAL_ADDRESS_COUNT) {
            "Unexpected external address count"
        }
        require(evidence.internalAddresses.size == INTERNAL_ADDRESS_COUNT) {
            "Unexpected internal address count"
        }
        requireSame(0L, evidence.confirmedBalanceSat, "fixture confirmed balance")
        requireSame(0L, evidence.trustedPendingBalanceSat, "fixture trusted-pending balance")
        requireSame(FIXTURE_VALUE_SAT, evidence.untrustedPendingBalanceSat, "fixture untrusted-pending balance")
        requireSame(FIXTURE_VALUE_SAT, evidence.totalBalanceSat, "fixture total balance")
        requireSame(1, evidence.transactionCount, "fixture transaction count")
        require(Regex("^[0-9a-f]{64}$").matches(evidence.transactionTxid)) {
            "Invalid fixture transaction id"
        }
        requireSame(UNCONFIRMED, evidence.transactionPosition, "fixture transaction position")
        requireSame(FIXTURE_LAST_SEEN, evidence.transactionLastSeen, "fixture transaction last-seen")
        requireSame(1, evidence.unspentCount, "fixture unspent count")
        requireSame("${evidence.transactionTxid}:0", evidence.unspentOutpoint, "fixture unspent outpoint")
        requireSame(FIXTURE_VALUE_SAT, evidence.unspentValueSat, "fixture unspent value")
        require(Regex("^[0-9a-f]{64}$").matches(evidence.unspentScriptSha256)) {
            "Invalid fixture script digest"
        }
        require(Regex("^[0-9a-f]{64}$").matches(TESTNET_GENESIS_HASH)) {
            "Invalid expected Testnet3 genesis hash"
        }
        require(Regex("^[0-9a-f]{64}$").matches(evidence.checkpointHash)) {
            "Invalid fixture checkpoint hash"
        }
        requireSame(0u, evidence.checkpointHeight, "fixture checkpoint height")
        requireSame(TESTNET_GENESIS_HASH, evidence.checkpointHash, "fixture checkpoint hash")
        requirePublicDescriptor(evidence.externalDescriptor)
        requirePublicDescriptor(evidence.internalDescriptor)
        return evidence
    }

    private fun verifyPersistedIdentity(context: Context, evidence: PublicWalletEvidence) {
        verifyRoomMetadata(context, evidence)
        verifyProductionRepositoryLoad(context, evidence)

        val database = context.getDatabasePath(DATABASE_NAME)
        require(database.isFile && database.length() > 0L) { "Preserved BDK database missing" }

        var external: Descriptor? = null
        var internal: Descriptor? = null
        var persister: Persister? = null
        var wallet: Wallet? = null
        try {
            external = Descriptor(evidence.externalDescriptor, NetworkKind.TEST)
            internal = Descriptor(evidence.internalDescriptor, NetworkKind.TEST)
            persister = Persister.newSqlite(database.absolutePath)
            wallet = Wallet.load(external, internal, persister)
            external.close()
            external = null
            internal.close()
            internal = null

            requireSame(evidence.externalDescriptor, wallet.publicDescriptor(KeychainKind.EXTERNAL), "external descriptor")
            requireSame(evidence.internalDescriptor, wallet.publicDescriptor(KeychainKind.INTERNAL), "internal descriptor")
            requireSame(Network.TESTNET, wallet.network(), "network")
            requireSame(evidence.externalLastIndex, wallet.derivationIndex(KeychainKind.EXTERNAL), "external derivation index")
            requireSame(evidence.internalLastIndex, wallet.derivationIndex(KeychainKind.INTERNAL), "internal derivation index")

            evidence.externalAddresses.forEachIndexed { index, expectedAddress ->
                requirePeekedAddress(wallet, KeychainKind.EXTERNAL, index, expectedAddress)
            }
            evidence.internalAddresses.forEachIndexed { index, expectedAddress ->
                requirePeekedAddress(wallet, KeychainKind.INTERNAL, index, expectedAddress)
            }

            requireBalance(wallet, evidence)
            requireTransactionGraph(wallet, evidence)
            requireUnspentOutput(wallet, evidence)
            requireCheckpoint(wallet, evidence)
            requireNextUnusedAddress(wallet, evidence)

            // Persist any version/schema migration staged by Wallet.load. Phase two proves the
            // result survives another complete instrumentation-process restart.
            wallet.persist(persister)
        } finally {
            closeAllOrFail(
                { wallet?.close() },
                { persister?.close() },
                { internal?.close() },
                { external?.close() }
            )
        }
    }

    private fun verifyProductionRepositoryLoad(context: Context, evidence: PublicWalletEvidence) {
        val app = context.applicationContext as? ClenchApplication
            ?: throw AssertionError("Unexpected target application")
        runBlocking {
            try {
                requireProductionBalance(app.bitcoinRepository.getBalance(WALLET_ID), evidence)
                val address = app.bitcoinRepository.getLastAddress(WALLET_ID)
                requireSame(
                    evidence.nextUnusedExternalIndex.toInt(),
                    address.index,
                    "production next-unused address index"
                )
                requireSame(
                    evidence.nextUnusedExternalAddress,
                    address.address,
                    "production next-unused address"
                )
            } finally {
                // Close the production cache before the independent native inspection below.
                app.bitcoinRepository.beginSensitiveSessionEviction()
                app.bitcoinRepository.completeSensitiveSessionEviction()
                app.bitcoinRepository.allowSensitiveSessionAccess()
            }
        }
    }

    private fun requireProductionBalance(balance: WalletBalance, evidence: PublicWalletEvidence) {
        requireSame(evidence.confirmedBalanceSat, balance.confirmedSat, "production confirmed balance")
        requireSame(
            evidence.trustedPendingBalanceSat,
            balance.trustedPendingSat,
            "production trusted-pending balance"
        )
        requireSame(
            evidence.untrustedPendingBalanceSat,
            balance.untrustedPendingSat,
            "production untrusted-pending balance"
        )
        requireSame(0L, balance.immatureSat, "production immature balance")
        requireSame(evidence.totalBalanceSat, balance.totalSat, "production total balance")
    }

    private fun verifyRoomMetadata(context: Context, evidence: PublicWalletEvidence) {
        val app = context.applicationContext as? ClenchApplication
            ?: throw AssertionError("Unexpected target application")
        val roomWallet = runBlocking { app.walletDao.getById(WALLET_ID) }
            ?: throw AssertionError("Preserved wallet metadata missing")
        requireSame(evidence.externalDescriptor, roomWallet.descriptor, "Room external descriptor")
        requireSame(evidence.internalDescriptor, roomWallet.changeDescriptor, "Room internal descriptor")
        requireSame(TESTNET, roomWallet.network, "Room network")
        require(roomWallet.isWatchOnly && !roomWallet.isMultisig && !roomWallet.hasPassphrase) {
            "Preserved Room wallet type mismatch"
        }
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

    private fun requireBalance(wallet: Wallet, evidence: PublicWalletEvidence) {
        var balance: Balance? = null
        try {
            balance = wallet.balance()
            requireSame(evidence.confirmedBalanceSat, balance.confirmed.toSat().toLong(), "confirmed balance")
            requireSame(
                evidence.trustedPendingBalanceSat,
                balance.trustedPending.toSat().toLong(),
                "trusted-pending balance"
            )
            requireSame(
                evidence.untrustedPendingBalanceSat,
                balance.untrustedPending.toSat().toLong(),
                "untrusted-pending balance"
            )
            requireSame(evidence.totalBalanceSat, balance.total.toSat().toLong(), "total balance")
        } finally {
            balance?.destroy()
        }
    }

    private fun requireTransactionGraph(wallet: Wallet, evidence: PublicWalletEvidence) {
        val transactions = wallet.transactions()
        try {
            requireSame(evidence.transactionCount, transactions.size, "transaction history count")
            require(transactions.size == 1) { "Upgrade fixture must contain exactly one transaction" }
            val canonical = transactions.single()
            var txid: org.bitcoindevkit.Txid? = null
            try {
                txid = canonical.transaction.computeTxid()
                requireSame(evidence.transactionTxid, txid.toString(), "transaction id")
                val position = canonical.chainPosition as? ChainPosition.Unconfirmed
                    ?: throw AssertionError("Upgrade fixture transaction is no longer unconfirmed")
                requireSame(evidence.transactionPosition, UNCONFIRMED, "transaction position")
                requireSame(evidence.transactionLastSeen, position.timestamp, "transaction last-seen")
            } finally {
                txid?.close()
            }
        } finally {
            transactions.forEach { it.destroy() }
        }
    }

    private fun requireUnspentOutput(wallet: Wallet, evidence: PublicWalletEvidence) {
        val outputs = wallet.listUnspent()
        try {
            requireSame(evidence.unspentCount, outputs.size, "unspent output count")
            require(outputs.size == 1) { "Upgrade fixture must contain exactly one unspent output" }
            val output = outputs.single()
            val position = output.chainPosition as? ChainPosition.Unconfirmed
                ?: throw AssertionError("Upgrade fixture output is no longer unconfirmed")
            requireSame(evidence.transactionLastSeen, position.timestamp, "output last-seen")
            requireSame(KeychainKind.EXTERNAL, output.keychain, "output keychain")
            requireSame(0u, output.derivationIndex, "output derivation index")
            require(!output.isSpent) { "Upgrade fixture output unexpectedly spent" }
            requireSame(
                evidence.unspentOutpoint,
                "${output.outpoint.txid}:${output.outpoint.vout}",
                "unspent outpoint"
            )
            requireSame(evidence.unspentValueSat, output.txout.value.toSat().toLong(), "unspent value")
            requireSame(
                evidence.unspentScriptSha256,
                sha256(output.txout.scriptPubkey.toBytes()),
                "unspent script"
            )
        } finally {
            outputs.forEach { it.destroy() }
        }
    }

    private fun requireCheckpoint(wallet: Wallet, evidence: PublicWalletEvidence) {
        var checkpoint: org.bitcoindevkit.BlockId? = null
        val checkpoints = wallet.checkpoints()
        try {
            checkpoint = wallet.latestCheckpoint()
            requireSame(evidence.checkpointHeight, checkpoint.height, "latest checkpoint height")
            requireSame(evidence.checkpointHash, checkpoint.hash.toString(), "latest checkpoint hash")
            require(checkpoints.isNotEmpty()) { "BDK3 checkpoint history unexpectedly empty" }
            require(
                checkpoints.any { it.height == evidence.checkpointHeight && it.hash.toString() == evidence.checkpointHash }
            ) { "Persisted checkpoint absent from BDK3 checkpoint history" }
        } finally {
            checkpoint?.destroy()
            checkpoints.forEach { it.destroy() }
        }
    }

    private fun requireNextUnusedAddress(wallet: Wallet, evidence: PublicWalletEvidence) {
        var addressInfo: AddressInfo? = null
        try {
            addressInfo = wallet.nextUnusedAddress(KeychainKind.EXTERNAL)
            requireSame(evidence.nextUnusedExternalIndex, addressInfo.index, "next-unused address index")
            requireSame(evidence.nextUnusedExternalAddress, addressInfo.address.toString(), "next-unused address")
        } finally {
            addressInfo?.destroy()
        }
    }

    private fun writeResultAtomically(context: Context, evidence: PublicWalletEvidence) {
        val lines = sortedMapOf(
            "balance.confirmed_sat" to evidence.confirmedBalanceSat.toString(),
            "balance.total_sat" to evidence.totalBalanceSat.toString(),
            "balance.trusted_pending_sat" to evidence.trustedPendingBalanceSat.toString(),
            "balance.untrusted_pending_sat" to evidence.untrustedPendingBalanceSat.toString(),
            "checkpoint.hash" to evidence.checkpointHash,
            "checkpoint.height" to evidence.checkpointHeight.toString(),
            "consumer.bdk" to CONSUMER_BDK,
            "database.load_verified" to "true",
            "external.addresses.sha256" to sha256(
                evidence.externalAddresses.joinToString("\n").toByteArray(Charsets.UTF_8)
            ),
            "external.descriptor.sha256" to sha256(evidence.externalDescriptor.toByteArray(Charsets.UTF_8)),
            "external.last_index" to evidence.externalLastIndex.toString(),
            "fixture.version" to FIXTURE_VERSION,
            "history.last_seen" to evidence.transactionLastSeen.toString(),
            "history.position" to evidence.transactionPosition,
            "history.transaction_count" to evidence.transactionCount.toString(),
            "history.txid" to evidence.transactionTxid,
            "in_place_upgrade_verified" to "true",
            "internal.addresses.sha256" to sha256(
                evidence.internalAddresses.joinToString("\n").toByteArray(Charsets.UTF_8)
            ),
            "internal.descriptor.sha256" to sha256(evidence.internalDescriptor.toByteArray(Charsets.UTF_8)),
            "internal.last_index" to evidence.internalLastIndex.toString(),
            "network" to TESTNET,
            "next_unused.external_address.sha256" to sha256(
                evidence.nextUnusedExternalAddress.toByteArray(Charsets.UTF_8)
            ),
            "next_unused.external_index" to evidence.nextUnusedExternalIndex.toString(),
            "process_restart_verified" to "true",
            "producer.bdk" to PRODUCER_BDK,
            "production.load_verified" to "true",
            "result" to "PASS",
            "room.metadata_preserved" to "true",
            "unspent.count" to evidence.unspentCount.toString(),
            "unspent.outpoint" to evidence.unspentOutpoint,
            "unspent.script_sha256" to evidence.unspentScriptSha256,
            "unspent.value_sat" to evidence.unspentValueSat.toString()
        )
        require(lines.keys == RESULT_KEYS) { "Unexpected result evidence schema" }
        val contents = lines.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" }
        writeTextAtomically(File(context.noBackupFilesDir, RESULT_FILE), contents)
    }

    private fun loadProperties(file: File): Properties {
        require(file.isFile && file.length() in 1..MAX_EVIDENCE_BYTES) { "Fixture evidence missing or oversized" }
        return Properties().apply { file.inputStream().use { input -> load(input) } }
    }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf(String::isNotBlank)
            ?: throw AssertionError("Missing fixture evidence field")

    private fun writePropertiesAtomically(destination: File, properties: Properties) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            properties.store(output, "Safe BDK upgrade phase marker")
            output.fd.sync()
        }
        require(temporary.renameTo(destination)) { "Could not commit phase evidence" }
    }

    private fun writeTextAtomically(destination: File, contents: String) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        require(temporary.renameTo(destination)) { "Could not commit result evidence" }
    }

    private fun PublicWalletEvidence.canonicalBytes(): ByteArray = listOf(
        externalDescriptor,
        internalDescriptor,
        externalAddresses.joinToString(","),
        internalAddresses.joinToString(","),
        externalLastIndex.toString(),
        internalLastIndex.toString(),
        nextUnusedExternalIndex.toString(),
        nextUnusedExternalAddress,
        confirmedBalanceSat.toString(),
        trustedPendingBalanceSat.toString(),
        untrustedPendingBalanceSat.toString(),
        totalBalanceSat.toString(),
        transactionCount.toString(),
        transactionTxid,
        transactionPosition,
        transactionLastSeen.toString(),
        unspentCount.toString(),
        unspentOutpoint,
        unspentValueSat.toString(),
        unspentScriptSha256,
        checkpointHeight.toString(),
        checkpointHash
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
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
        firstFailure?.let { throw AssertionError("Native upgrade verifier cleanup failed", it) }
    }

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

    private const val TARGET_PACKAGE = "net.clench.wallet.debug"
    private const val FIXTURE_VERSION = "2"
    private const val PRODUCER_BDK = "2.3.1"
    private const val CONSUMER_BDK = "3.0.0"
    private const val TESTNET = "testnet"
    private const val WALLET_ID = "00000000-0000-4000-8000-000000000326"
    private const val DATABASE_NAME = "wallet_$WALLET_ID.db"
    private const val EVIDENCE_FILE = "bdk2-to-bdk3-public-evidence.properties"
    private const val PHASE_ONE_FILE = "bdk2-to-bdk3-phase-one.properties"
    private const val RESULT_FILE = "bdk2-to-bdk3-result.properties"
    private const val EXTERNAL_ADDRESS_COUNT = 3
    private const val INTERNAL_ADDRESS_COUNT = 2
    private const val FIXTURE_VALUE_SAT = 50_000L
    private val FIXTURE_LAST_SEEN = 1_700_000_326uL
    private const val UNCONFIRMED = "unconfirmed"
    private const val TESTNET_GENESIS_HASH =
        "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"
    private const val MAX_EVIDENCE_BYTES = 64 * 1024L

    private val PRIVATE_DESCRIPTOR_MARKERS = listOf("xprv", "tprv", "yprv", "zprv", "uprv", "vprv")
    private val EVIDENCE_KEYS = setOf(
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
    private val PHASE_ONE_KEYS = setOf("phase", "consumer_bdk", "process_id", "evidence_sha256")
    private val RESULT_KEYS = setOf(
        "balance.confirmed_sat",
        "balance.total_sat",
        "balance.trusted_pending_sat",
        "balance.untrusted_pending_sat",
        "checkpoint.hash",
        "checkpoint.height",
        "consumer.bdk",
        "database.load_verified",
        "external.addresses.sha256",
        "external.descriptor.sha256",
        "external.last_index",
        "fixture.version",
        "history.last_seen",
        "history.position",
        "history.transaction_count",
        "history.txid",
        "in_place_upgrade_verified",
        "internal.addresses.sha256",
        "internal.descriptor.sha256",
        "internal.last_index",
        "network",
        "next_unused.external_address.sha256",
        "next_unused.external_index",
        "process_restart_verified",
        "producer.bdk",
        "production.load_verified",
        "result",
        "room.metadata_preserved",
        "unspent.count",
        "unspent.outpoint",
        "unspent.script_sha256",
        "unspent.value_sat"
    )
}
