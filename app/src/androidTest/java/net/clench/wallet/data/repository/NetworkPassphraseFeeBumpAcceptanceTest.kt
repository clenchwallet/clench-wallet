package net.clench.wallet.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import net.clench.wallet.domain.model.ElectrumConfig
import org.json.JSONObject
import java.net.Socket
import java.net.InetSocketAddress
import net.clench.wallet.data.local.entity.UtxoMetadataEntity
import net.clench.wallet.domain.model.toNetworkKind
import net.clench.wallet.domain.repository.MultisigPhoneSignerSecret
import org.bitcoindevkit.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical acceptance overlay: public synthetic transactions from Mac loopback Electrum.
 * No history inserts, native graph injection, broadcasts, or real funds. */
@RunWith(AndroidJUnit4::class)
class NetworkPassphraseFeeBumpAcceptanceTest {
    @Test fun networkDerivedFeeBumpRejectsFrozenInputEvictsAndRecoversAfterRealResync() = runBlocking {
        WalletRepositoryFixture().use { f ->
            val passphrase = " \t "
            val wallet = f.repository.importWallet("PUBLIC RBF fixture", WORDS, passphrase)
            assertTrue(wallet.hasPassphrase)
            f.repository.unlockPassphraseWallet(wallet.id, passphrase)
            val native = borrowedNative(f.repository, wallet.id)
            val funding = funding(native)
            funding.use {
                val config = ElectrumConfig("127.0.0.1", 51041, false, true)
                f.settings.saveElectrumConfig(config)
                f.settings.setOfflineMode(false)
                control("transaction", funding.serialize().joinToString("") { "%02x".format(it.toInt() and 0xff) })
                f.repository.syncWallet(wallet.id, config)
                assertTrue("Funding must arrive over native Electrum sync", native.transactions().isNotEmpty())
                val destination = outsideAddress()
                val originalHex = f.repository.buildTransaction(wallet.id, destination, 40_000L, 1f)
                Transaction(decodeHex(originalHex)).use { original ->
                    assertTrue("Native positive control must be RBF eligible", original.isExplicitlyRbf())
                    assertTrue("Original must contain an actual signature", hasWitness(original))
                    control("transaction", originalHex)
                    f.repository.syncWallet(wallet.id, config)
                    val originalId = original.computeTxid().toString()
                    val outpoint = "${funding.computeTxid()}:0"
                    assertEquals(listOf(outpoint), transactionInputs(original))
                    val rows = f.database.transactionDao().getForWallet(wallet.id)
                    assertTrue("Real sync must persist outgoing history", rows.any { it.txid == originalId })
                    f.database.utxoMetadataDao().upsert(UtxoMetadataEntity(outpoint, wallet.id, isFrozen = true))
                    val rejection = runCatching { f.repository.bumpFee(wallet.id, originalId, 10f) }.exceptionOrNull()
                    assertTrue("Fee-bump policy rejection must explicitly require unlock/resync",
                        rejection is IllegalStateException && rejection.message?.contains("Unlock and resync") == true)
                    assertTrue("Rejection must be the actual frozen-input policy, not a fixture/build failure",
                        rejection?.cause is IllegalArgumentException && rejection.cause?.message?.contains("frozen") == true)
                    assertFalse(f.repository.isPassphraseWalletUnlocked(wallet.id))
                    assertNull("Rejected staged native graph must be evicted", cacheEntry(f.repository, wallet.id))
                    assertEquals(rows, f.database.transactionDao().getForWallet(wallet.id))
                    assertEquals(wallet.descriptor, f.database.walletDao().getById(wallet.id)!!.descriptor)
                    assertNoPersistedPassphraseGraph(f, wallet.id)

                    // Unlock alone intentionally does not resurrect an old ephemeral graph.
                    f.repository.unlockPassphraseWallet(wallet.id, passphrase)
                    assertTrue(f.repository.isPassphraseWalletUnlocked(wallet.id))
                    val reloaded = borrowedNative(f.repository, wallet.id)
                    assertTrue(reloaded.transactions().isEmpty())
                    assertTrue(runCatching { f.repository.bumpFee(wallet.id, originalId, 10f) }.isFailure)
                    assertTrue(f.repository.isPassphraseWalletUnlocked(wallet.id))
                    // Repopulate exclusively through the production native Electrum sync.
                    f.repository.syncWallet(wallet.id, config)
                    val originalDetails = requireNotNull(reloaded.txDetails(original.computeTxid()))
                    originalDetails.tx.close()
                    f.database.utxoMetadataDao().upsert(UtxoMetadataEntity(outpoint, wallet.id, isFrozen = false))
                    val replacementHex = f.repository.bumpFee(wallet.id, originalId, 10f)
                    Transaction(decodeHex(replacementHex)).use { replacement ->
                        assertNotEquals(originalId, replacement.computeTxid().toString())
                        assertEquals(listOf(outpoint), transactionInputs(replacement))
                        assertTrue("Recovered replacement must be genuinely signed", hasWitness(replacement))
                        assertTrue(reloaded.calculateFee(replacement).toSat() > reloaded.calculateFee(original).toSat())
                    }
                    assertTrue(f.repository.isPassphraseWalletUnlocked(wallet.id))
                    assertFalse(f.settings.isOfflineMode())
                    assertNoPersistedPassphraseGraph(f, wallet.id)
                    f.repository.lockPassphraseWallet(wallet.id)
                    assertFalse(f.repository.isPassphraseWalletUnlocked(wallet.id))
                    assertNoPersistedPassphraseGraph(f, wallet.id)
                }
            }
        }
    }

    private fun control(path: String, raw: String) {
        // Test control plane only; production wallet traffic uses ElectrumConnectionFactory.
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 51042), 10_000)
            socket.soTimeout = 10_000
            val body = JSONObject().put("raw", raw).toString().toByteArray()
            val headers = "POST /$path HTTP/1.0\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n"
            socket.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() }
            val response = socket.getInputStream().bufferedReader().readText()
            assertTrue("Local fixture registration", response.startsWith("HTTP/1.0 200"))
        }
    }

    private data class Account(val publicKey: String, val secretKey: String)

    private fun account(passphrase: String): Account = Mnemonic.fromString(WORDS.joinToString(" ")).use { mnemonic ->
        DescriptorSecretKey(Network.TESTNET.toNetworkKind(), mnemonic, passphrase).use { root ->
            DerivationPath("m/48'/1'/0'/2'").use { path ->
                root.derive(path).use { key ->
                    key.asPublic().use { public ->
                        root.asPublic().use { rootPublic ->
                            val origin = "[${rootPublic.masterFingerprint()}/48'/1'/0'/2']"
                            fun wrap(value: String): String = MultisigAccountKeyPolicy.normalizeGeneratedAccountKey(value)
                                .let { if (it.startsWith("[")) it else origin + it }
                            Account(wrap(public.toString()), wrap(key.toString()))
                        }
                    }
                }
            }
        }
    }

    /** Reflection only observes the real cached native wallet and eviction.
     * No mocks replace signing, selection, Room metadata, network sync, or recovery methods.
     * Borrowed wrappers remain owned and closed exclusively by the production repository.
     */
    private fun cacheEntry(repository: BdkBitcoinRepository, id: String): Any? {
        val field = BdkBitcoinRepository::class.java.getDeclaredField("walletCache").apply { isAccessible = true }
        return (field.get(repository) as Map<*, *>)[id]
    }
    private fun borrowedNative(repository: BdkBitcoinRepository, id: String): Wallet {
        val entry = requireNotNull(cacheEntry(repository, id))
        return entry.javaClass.getDeclaredField("wallet").apply { isAccessible = true }.get(entry) as Wallet
    }

    private fun revealFirstAddress(wallet: Wallet): ByteArray {
        val address = wallet.revealNextAddress(KeychainKind.EXTERNAL)
        try { return address.address.scriptPubkey().use { it.toBytes() } }
        finally { address.destroy() }
    }

    private fun funding(wallet: Wallet): Transaction {
        val script = revealFirstAddress(wallet)
        val parent = Transaction(ByteArrayOutputStream().apply {
            write(le(2, 4)); write(1); write(ByteArray(32)); write(le(0xffff_ffffL, 4))
            write(2); write(byteArrayOf(1, 1)); write(le(0xffff_ffffL, 4)); write(1)
            write(le(301_000, 8)); write(1); write(0x51); write(le(0, 4))
        }.toByteArray())
        return parent.use {
            control("reset", parent.serialize().joinToString("") { "%02x".format(it.toInt() and 0xff) })
            Transaction(ByteArrayOutputStream().apply {
                write(le(2, 4)); write(1); write(decodeHex(parent.computeTxid().toString()).reversedArray()); write(le(0, 4))
                write(0); write(le(0xffff_fffdL, 4)); write(1)
                write(le(300_000, 8)); write(script.size); write(script); write(le(0, 4))
            }.toByteArray())
        }
    }

    private fun outsideAddress(): String = Descriptor(
        "wpkh(0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798)",
        Network.TESTNET.toNetworkKind()).use { receive ->
        Descriptor("wpkh(02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5)",
            Network.TESTNET.toNetworkKind()).use { change ->
            Persister.newInMemory().use { persister ->
                Wallet(receive, change, Network.TESTNET, persister).use { wallet ->
                    val address = wallet.peekAddress(KeychainKind.EXTERNAL, 0u)
                    try { address.address.toString() } finally { address.destroy() }
                }
            }
        }
    }

    private fun partialSignatureCount(base64: String): Int = Psbt(base64).use { psbt ->
        val inputs = psbt.input()
        try { inputs.sumOf { it.partialSigs.size } } finally { inputs.forEach { it.destroy() } }
    }
    private fun psbtInputs(base64: String): List<String> = Psbt(base64).use { it.extractTx().use(::transactionInputs) }
    private fun transactionInputs(tx: Transaction): List<String> {
        val inputs = tx.input()
        try { return inputs.map { "${it.previousOutput.txid}:${it.previousOutput.vout}" } }
        finally { inputs.forEach { it.destroy() } }
    }
    private fun hasWitness(tx: Transaction): Boolean {
        val inputs = tx.input()
        try { return inputs.isNotEmpty() && inputs.all { it.witness.isNotEmpty() } }
        finally { inputs.forEach { it.destroy() } }
    }
    private fun assertNoPersistedPassphraseGraph(f: WalletRepositoryFixture, id: String) {
        assertNull(f.keystore.getSecretDescriptor(id))
        assertNull(f.keystore.getSecretChangeDescriptor(id))
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            assertFalse(f.context.getDatabasePath("wallet_$id.db$suffix").exists())
        }
    }
    private fun decodeHex(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun le(value: Long, size: Int) = ByteArray(size) { (value ushr (8 * it)).toByte() }

    companion object {
        private val WORDS = List(11) { "abandon" } + "about" // Published BIP39 vector, not real funds.
        private val SEEN = 1_700_000_000uL
    }
}
