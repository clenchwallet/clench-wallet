package net.clench.wallet.verification.passphraserestart

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.clench.wallet.BuildConfig
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.ElectrumConnectionFactory
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.data.repository.BdkBitcoinRepository
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.domain.model.toNetworkKind
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.security.BdkWalletMnemonicFactory
import net.clench.wallet.security.SecureRandomWalletEntropySource
import net.clench.wallet.security.WalletMnemonicGenerator
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Overlay-only acceptance fixture. Not part of the ordinary instrumentation suite. */
@RunWith(AndroidJUnit4::class)
class PassphraseProcessRestartFixture {
    @Test fun runExplicitPhaseOnDisposableEmulator() = runBlocking {
        Fixture.guard()
        when (InstrumentationRegistry.getArguments().getString("clenchPassphraseRestartPhase")) {
            "write" -> write()
            "verify" -> verify()
            else -> error("An explicit write or verify phase is required")
        }
    }

    private suspend fun write() {
        check(!Fixture.directory.exists()) { "Writer requires a fresh owned fixture directory" }
        check(Fixture.directory.mkdirs())
        val session = Fixture.Session()
        // Deliberately retain live repository/Room objects. The host force-stops the app;
        // no repository reconstruction or orderly cache eviction substitutes for that step.
        Fixture.retained = session
        val expected = Fixture.derive(Fixture.WHITESPACE)
        val emptyExpected = Fixture.derive("")
        assertNotEquals(expected.receive, emptyExpected.receive)
        val whitespace = session.repository.importWallet("PUBLIC whitespace fixture", Fixture.WORDS, Fixture.WHITESPACE)
        val empty = session.repository.importWallet("PUBLIC empty control", Fixture.WORDS, null)
        assertEquals(expected.receive, whitespace.descriptor)
        assertEquals(expected.change, whitespace.changeDescriptor)
        assertEquals(emptyExpected.receive, empty.descriptor)
        assertTrue(whitespace.hasPassphrase)
        assertFalse(empty.hasPassphrase)
        assertFalse(session.repository.isPassphraseWalletUnlocked(whitespace.id))
        assertNull(session.keystore.getSecretDescriptor(whitespace.id))
        assertNull(session.keystore.getSecretChangeDescriptor(whitespace.id))
        assertNotNull(session.keystore.getSecretDescriptor(empty.id))
        assertFalse(session.hasNativeGraph(whitespace.id))
        assertEquals(expected.fingerprint, session.repository.getPassphraseFingerprint(whitespace.id, Fixture.WHITESPACE)!!.second.hex())
        assertEquals(emptyExpected.address, session.repository.getLastAddress(empty.id).address)

        // This receipt contains only public derivation outputs and disposable wallet/process IDs.
        val receipt = JSONObject().put("format", "clench-passphrase-process-fixture-v1")
            .put("writerProcess", Fixture.processInstance).put("writerPid", Process.myPid())
            .put("roomVersion", session.database.openHelper.writableDatabase.version)
            .put("whitespace", expected.toJson(whitespace)).put("empty", emptyExpected.toJson(empty))
        val atomic = AtomicFile(Fixture.receipt)
        val output = atomic.startWrite()
        try {
            output.write(receipt.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
        Fixture.marker("WRITE")
    }

    private suspend fun verify() {
        check(Fixture.directory.isDirectory && Fixture.receipt.isFile)
        check(Fixture.receipt.length() in 1..32_768)
        val receipt = JSONObject(Fixture.receipt.readText())
        assertEquals("clench-passphrase-process-fixture-v1", receipt.getString("format"))
        assertNotEquals("Verifier must run in a fresh OS process", receipt.getString("writerProcess"), Fixture.processInstance)
        val session = Fixture.Session()
        Fixture.retained = session
        val whitespace = receipt.getJSONObject("whitespace")
        val empty = receipt.getJSONObject("empty")
        val whitespaceId = whitespace.getString("id")
        val emptyId = empty.getString("id")
        assertNotEquals(whitespaceId, emptyId)
        assertEquals(receipt.getInt("roomVersion"), session.database.openHelper.writableDatabase.version)
        assertEquals(2, session.database.walletDao().getAll().size)
        val whitespaceRow = requireNotNull(session.database.walletDao().getById(whitespaceId))
        val emptyRow = requireNotNull(session.database.walletDao().getById(emptyId))
        assertTrue(whitespaceRow.hasPassphrase)
        assertFalse(emptyRow.hasPassphrase)
        assertEquals(whitespace.getString("receive"), whitespaceRow.descriptor)
        assertEquals(whitespace.getString("change"), whitespaceRow.changeDescriptor)
        assertEquals(empty.getString("receive"), emptyRow.descriptor)
        assertEquals(empty.getString("change"), emptyRow.changeDescriptor)
        assertFalse(session.repository.isPassphraseWalletUnlocked(whitespaceId))
        assertNull(session.keystore.getSecretDescriptor(whitespaceId))
        assertNull(session.keystore.getSecretChangeDescriptor(whitespaceId))
        assertNotNull(session.keystore.getSecretDescriptor(emptyId))
        assertFalse(session.hasNativeGraph(whitespaceId))

        val independent = Fixture.derive(Fixture.WHITESPACE)
        assertEquals(whitespace.getString("receive"), independent.receive)
        assertEquals(whitespace.getString("fingerprint"), independent.fingerprint)
        assertEquals(whitespace.getString("address"), independent.address)
        session.repository.unlockPassphraseWallet(whitespaceId, Fixture.WHITESPACE)
        assertTrue(session.repository.isPassphraseWalletUnlocked(whitespaceId))
        assertEquals(independent.address, session.repository.getLastAddress(whitespaceId).address)
        assertEquals(independent.fingerprint, session.repository.getPassphraseFingerprint(whitespaceId, Fixture.WHITESPACE)!!.second.hex())
        assertFalse(session.hasNativeGraph(whitespaceId))
        assertNull(session.keystore.getSecretDescriptor(whitespaceId))
        assertEquals(empty.getString("address"), session.repository.getLastAddress(emptyId).address)
        assertEquals(empty.getString("receive"), session.database.walletDao().getById(emptyId)!!.descriptor)
        assertFalse(session.database.walletDao().getById(emptyId)!!.hasPassphrase)
        session.repository.lockPassphraseWallet(whitespaceId)
        assertFalse(session.repository.isPassphraseWalletUnlocked(whitespaceId))
        assertFalse(session.hasNativeGraph(whitespaceId))
        assertEquals(whitespace.getString("receive"), session.database.walletDao().getById(whitespaceId)!!.descriptor)
        Fixture.marker("VERIFY")
    }
}

private object Fixture {
    // Published BIP39 zero-entropy example; NEVER use this fixture as a real wallet.
    val WORDS = List(11) { "abandon" } + "about"
    const val WHITESPACE = " \t "
    const val DIRECTORY = "clench-passphrase-process-fixture-v1"
    val processInstance = UUID.randomUUID().toString()
    var retained: Session? = null
    private val base: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val directory: File get() = File(base.filesDir, DIRECTORY)
    val receipt: File get() = File(directory, "public-receipt.json")

    fun guard() {
        check(BuildConfig.DEBUG && base.packageName == "net.clench.wallet.debug")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable Android emulator required" }
        check(InstrumentationRegistry.getArguments().getString("clenchDisposableEmulator") == "YES")
        // The harness verifies both packages were absent before installing. Disable optional
        // production-context lookups, too; no fixture operation is allowed to contact a server.
        SettingsManager(base).apply {
            setOfflineMode(true); setBtcPriceEnabled(false); setExternalFeeLookupEnabled(false)
        }
        System.loadLibrary("sqlcipher")
    }

    class Session {
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("clench_passphrase_restart_v1_$name", mode)
        }
        val settings = SettingsManager(context).apply {
            setNetwork("testnet"); setOfflineMode(true); setBtcPriceEnabled(false); setExternalFeeLookupEnabled(false)
        }
        val database = Room.databaseBuilder(context, ClenchDatabase::class.java, "fixture-room.db")
            .openHelperFactory(SupportOpenHelperFactory(ByteArray(32) { (it * 7 + 11).toByte() }))
            .build()
        val keystore = KeystoreManager(context)
        val repository = BdkBitcoinRepository(context, database.walletDao(), database.transactionDao(),
            database.transactionLabelDao(), database.utxoMetadataDao(), database.addressBookDao(),
            keystore, settings, ElectrumConnectionFactory(settings), TorAwareHttpClient(settings),
            WalletMnemonicGenerator(SecureRandomWalletEntropySource(), BdkWalletMnemonicFactory()), SensitiveWalletOperationBarrier())
        fun hasNativeGraph(id: String) = listOf("", "-wal", "-shm", "-journal")
            .any { File(directory, "wallet_$id.db$it").exists() }
    }

    data class Identity(val receive: String, val change: String, val fingerprint: String, val address: String) {
        fun toJson(wallet: WalletData) = JSONObject().put("id", wallet.id).put("receive", receive)
            .put("change", change).put("fingerprint", fingerprint).put("address", address)
    }

    fun derive(passphrase: String): Identity = Mnemonic.fromString(WORDS.joinToString(" ")).use { mnemonic ->
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

    fun marker(phase: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("clenchPassphraseRestartMarker", "CLENCH_PASSPHRASE_RESTART_${phase}_PASS")
            putString("clenchPassphraseProcess", processInstance)
            putString("clenchPassphrasePid", Process.myPid().toString())
        })
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
