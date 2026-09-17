package net.clench.wallet.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.entity.UtxoMetadataEntity
import net.clench.wallet.data.local.entity.WalletEntity
import net.clench.wallet.data.network.ElectrumConnectionFactory
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.toNetworkKind
import net.clench.wallet.domain.repository.Recipient
import net.clench.wallet.security.BdkWalletMnemonicFactory
import net.clench.wallet.security.SecureRandomWalletEntropySource
import net.clench.wallet.security.WalletMnemonicGenerator
import org.bitcoindevkit.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Real Room -> production repository -> pinned Android BDK. Public synthetic data, no network/signing. */
@RunWith(AndroidJUnit4::class)
class FrozenInputRepositoryTest {
    private data class Fixture(val repo: BdkBitcoinRepository, val db: ClenchDatabase, val id: String,
        val allowed: String, val frozen: String, val address: String)

    private fun fixture(failMetadata: Boolean = false, block: suspend (Fixture) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "frozen-policy-${UUID.randomUUID()}"
        val prefs = "settings-$id"
        val wrapper = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(prefs, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, ClenchDatabase::class.java).build()
        val settings = SettingsManager(wrapper).also { it.setNetwork("testnet") }
        val ext = "wpkh(0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798)"
        val change = "wpkh(02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5)"
        var repo: BdkBitcoinRepository? = null
        try {
            val descriptor = Descriptor(ext, Network.TESTNET.toNetworkKind())
            val changeDescriptor = Descriptor(change, Network.TESTNET.toNetworkKind())
            val persister = Persister.newSqlite(context.getDatabasePath("wallet_$id.db").absolutePath)
            val wallet = Wallet(descriptor, changeDescriptor, Network.TESTNET, persister)
            val revealed = wallet.revealNextAddress(KeychainKind.EXTERNAL)
            val script = revealed.address.scriptPubkey()
            val address = revealed.address.toString()
            val scriptBytes = script.toBytes()
            // An artificial non-coinbase transaction creates two 100,000-sat fixture outputs.
            val raw = ByteBuffer.allocate(4 + 1 + 32 + 4 + 1 + 4 + 1 + 2 * (8 + 1 + scriptBytes.size) + 4)
                .order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(2); put(1); put(ByteArray(32) { 0x11 }); putInt(0); put(0); putInt(-3)
                    put(2)
                    repeat(2) { putLong(100_000); put(scriptBytes.size.toByte()); put(scriptBytes) }
                    putInt(0)
                }.array()
            val funding = Transaction(raw)
            val txid = funding.computeTxid().toString()
            wallet.applyUnconfirmedTxs(listOf(UnconfirmedTx(funding, 1_700_000_000uL)))
            wallet.persist(persister)
            funding.close(); script.close(); revealed.destroy(); wallet.close(); persister.close()
            changeDescriptor.close(); descriptor.close()
            db.walletDao().insert(WalletEntity(id, "Public fixture", ext, change, true, false, 0, "testnet"))
            val metadata = if (!failMetadata) db.utxoMetadataDao() else object : UtxoMetadataDao by db.utxoMetadataDao() {
                override suspend fun getFrozenForWallet(walletId: String): List<UtxoMetadataEntity> =
                    throw IllegalStateException("Fixture metadata unavailable")
            }
            repo = BdkBitcoinRepository(context, db.walletDao(), db.transactionDao(), db.transactionLabelDao(),
                metadata, db.addressBookDao(), KeystoreManager(wrapper), settings,
                ElectrumConnectionFactory(settings), TorAwareHttpClient(settings),
                WalletMnemonicGenerator(SecureRandomWalletEntropySource(), BdkWalletMnemonicFactory()), SensitiveWalletOperationBarrier())
            db.utxoMetadataDao().upsert(UtxoMetadataEntity("$txid:1", id, isFrozen = true))
            block(Fixture(repo, db, id, "$txid:0", "$txid:1", address))
        } finally {
            repo?.beginSensitiveSessionEviction()
            repo?.completeSensitiveSessionEviction()
            db.close()
            context.deleteDatabase("wallet_$id.db")
            context.deleteSharedPreferences(prefs)
        }
    }

    private fun inputs(base64: String): List<String> = Psbt(base64).use { psbt ->
        // Exercise the same pre-finalization extraction used by production policy.
        psbt.extractTx().use { tx -> tx.input().map { "${it.previousOutput.txid}:${it.previousOutput.vout}" } }
    }

    @Test fun singleBatchAndDrainRespectRoomFrozenState() = fixture { f ->
        assertTrue(runCatching { f.repo.createPsbt(f.id, f.address, 150_000, 1f) }.isFailure)
        assertTrue(runCatching { f.repo.createBatchPsbt(f.id,
            listOf(Recipient(f.address, 75_000), Recipient(f.address, 75_000)), 1f) }.isFailure)
        assertEquals(listOf(f.allowed), inputs(f.repo.createPsbt(f.id, f.address, null, 1f)))
        assertEquals(listOf(f.allowed), inputs(f.repo.createPsbt(f.id, f.address, 50_000, 1f)))
        assertEquals(listOf(f.allowed), inputs(f.repo.createBatchPsbt(f.id,
            listOf(Recipient(f.address, 20_000), Recipient(f.address, 20_000)), 1f)))
    }

    @Test fun explicitFrozenSelectionCannotOverridePolicyAndLegacyAliasesStayFrozen() = fixture { f ->
        assertTrue(runCatching { f.repo.createPsbt(f.id, f.address, 10_000, 1f, selectedOutpoints = listOf(f.frozen)) }.isFailure)
        assertTrue(runCatching { f.repo.createPsbt(f.id, f.address, null, 1f, selectedOutpoints = listOf(f.frozen)) }.isFailure)
        f.db.utxoMetadataDao().upsert(UtxoMetadataEntity(f.allowed.substringBefore(':').uppercase() + ":0000", f.id, isFrozen = true))
        assertTrue(runCatching { f.repo.createPsbt(f.id, f.address, null, 1f) }.isFailure)
    }

    @Test fun retainedDraftRejectsFreezeBeforeAnyBroadcastConnection() = fixture { f ->
        val draft = f.repo.createPsbt(f.id, f.address, 10_000, 1f)
        val raw = Psbt(draft).use { it.extractTx().use { tx -> tx.serialize().joinToString("") { byte -> "%02x".format(byte) } } }
        f.db.utxoMetadataDao().upsert(UtxoMetadataEntity(f.allowed, f.id, isFrozen = true))
        val failure = runCatching { f.repo.broadcastTransaction(ElectrumConfig(serverUrl = "must-not-resolve.invalid"), raw, f.id) }.exceptionOrNull()
        assertTrue("Policy must reject before DNS/native connection: $failure", failure is IllegalArgumentException && failure.message!!.contains("frozen"))
        f.db.utxoMetadataDao().upsert(UtxoMetadataEntity(f.allowed, f.id, isFrozen = false))
        assertEquals(listOf(f.allowed), inputs(f.repo.createPsbt(f.id, f.address, 10_000, 1f)))
    }

    @Test fun metadataReadFailureStopsPsbtConstruction() = fixture(failMetadata = true) { f ->
        val failure = runCatching { f.repo.createPsbt(f.id, f.address, 10_000, 1f) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("metadata unavailable") == true)
    }
}
