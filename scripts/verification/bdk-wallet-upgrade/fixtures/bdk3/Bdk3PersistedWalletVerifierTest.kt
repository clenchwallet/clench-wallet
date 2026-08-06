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
import org.bitcoindevkit.AddressInfo
import org.bitcoindevkit.Balance
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
            totalBalanceSat = properties.required("balance_total_sat").toLongOrNull()
                ?: throw AssertionError("Invalid balance evidence"),
            transactionCount = properties.required("transaction_count").toIntOrNull()
                ?: throw AssertionError("Invalid transaction-count evidence"),
            unspentCount = properties.required("unspent_count").toIntOrNull()
                ?: throw AssertionError("Invalid unspent-count evidence")
        )
        require(evidence.externalAddresses.size == EXTERNAL_ADDRESS_COUNT) {
            "Unexpected external address count"
        }
        require(evidence.internalAddresses.size == INTERNAL_ADDRESS_COUNT) {
            "Unexpected internal address count"
        }
        require(evidence.totalBalanceSat == 0L && evidence.transactionCount == 0 && evidence.unspentCount == 0) {
            "Upgrade fixture must remain unfunded"
        }
        requirePublicDescriptor(evidence.externalDescriptor)
        requirePublicDescriptor(evidence.internalDescriptor)
        return evidence
    }

    private fun verifyPersistedIdentity(context: Context, evidence: PublicWalletEvidence) {
        verifyRoomMetadata(context, evidence)

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

            requireSame(evidence.totalBalanceSat, totalBalanceSat(wallet), "balance")
            requireSame(evidence.transactionCount, transactionCount(wallet), "transaction history")
            requireSame(evidence.unspentCount, unspentCount(wallet), "unspent outputs")

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

    private fun writeResultAtomically(context: Context, evidence: PublicWalletEvidence) {
        val lines = sortedMapOf(
            "balance.total_sat" to evidence.totalBalanceSat.toString(),
            "consumer.bdk" to CONSUMER_BDK,
            "database.file_preserved" to "true",
            "external.addresses.sha256" to sha256(
                evidence.externalAddresses.joinToString("\n").toByteArray(Charsets.UTF_8)
            ),
            "external.descriptor.sha256" to sha256(evidence.externalDescriptor.toByteArray(Charsets.UTF_8)),
            "external.last_index" to evidence.externalLastIndex.toString(),
            "fixture.version" to FIXTURE_VERSION,
            "history.transaction_count" to evidence.transactionCount.toString(),
            "in_place_upgrade_verified" to "true",
            "internal.addresses.sha256" to sha256(
                evidence.internalAddresses.joinToString("\n").toByteArray(Charsets.UTF_8)
            ),
            "internal.descriptor.sha256" to sha256(evidence.internalDescriptor.toByteArray(Charsets.UTF_8)),
            "internal.last_index" to evidence.internalLastIndex.toString(),
            "network" to TESTNET,
            "process_restart_verified" to "true",
            "producer.bdk" to PRODUCER_BDK,
            "result" to "PASS",
            "room.metadata_preserved" to "true",
            "unspent.count" to evidence.unspentCount.toString()
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
        totalBalanceSat.toString(),
        transactionCount.toString(),
        unspentCount.toString()
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
        val totalBalanceSat: Long,
        val transactionCount: Int,
        val unspentCount: Int
    )

    private const val TARGET_PACKAGE = "net.clench.wallet.debug"
    private const val FIXTURE_VERSION = "1"
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
        "balance_total_sat",
        "transaction_count",
        "unspent_count"
    )
    private val PHASE_ONE_KEYS = setOf("phase", "consumer_bdk", "process_id", "evidence_sha256")
    private val RESULT_KEYS = setOf(
        "balance.total_sat",
        "consumer.bdk",
        "database.file_preserved",
        "external.addresses.sha256",
        "external.descriptor.sha256",
        "external.last_index",
        "fixture.version",
        "history.transaction_count",
        "in_place_upgrade_verified",
        "internal.addresses.sha256",
        "internal.descriptor.sha256",
        "internal.last_index",
        "network",
        "process_restart_verified",
        "producer.bdk",
        "result",
        "room.metadata_preserved",
        "unspent.count"
    )
}
