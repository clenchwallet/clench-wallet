package net.clench.wallet.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.clench.wallet.BuildConfig
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupWalletIdentifierTest {
    private fun isolated(block: suspend (ClenchStateBackupManager, ClenchDatabase) -> Unit) = runBlocking {
        check(BuildConfig.DEBUG)
        check(InstrumentationRegistry.getArguments().getString("clenchDisposableEmulator") == "YES")
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "backup-id-fixture-${UUID.randomUUID()}"
        val wrapper = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
        }
        val db = Room.inMemoryDatabaseBuilder(context, ClenchDatabase::class.java).build()
        try {
            block(ClenchStateBackupManager(db, db.walletDao(), db.transactionLabelDao(), db.utxoMetadataDao(), SettingsManager(wrapper)), db)
        } finally {
            db.close()
            context.deleteSharedPreferences(preferences)
        }
    }

    @Test fun unsafeIdentifiersAreRejectedBeforeAnyDatabaseWrite() = isolated { manager, db ->
        for (id in listOf("part/child", "part\\child", "..", "has space", "nul\u0000id", "id\nid", "x".repeat(129))) {
            // The first wallet is intentionally incomplete: every identifier must
            // be checked before descriptor/native work or a partially applied import.
            val failure = runCatching { manager.importStateBackupJson(backup("safe-id", id).toString()) }.exceptionOrNull()
            assertTrue("Unexpected preflight result: $failure", failure is IllegalArgumentException)
            assertTrue(failure?.message?.contains("invalid wallet identifier") == true)
            assertTrue(db.walletDao().getAll().isEmpty())
        }
    }

    @Test fun duplicateIdentifiersAreRejectedBeforeAnyDatabaseWrite() = isolated { manager, db ->
        val failure = runCatching { manager.importStateBackupJson(backup("same-id", "same-id").toString()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("duplicate wallet identifiers") == true)
        assertTrue(db.walletDao().getAll().isEmpty())
    }

    @Test fun validIdentifiersPreserveRecordAssociations() {
        for (id in listOf("wallet-1", "a_2", "550e8400-e29b-41d4-a716-446655440000", "x".repeat(128), "")) {
            isolated { manager, db ->
                val document = backup(id)
                val wallet = document.getJSONArray("wallets").getJSONObject(0)
                // Public test keys only; no secret, real wallet, BDK database or network.
                wallet.put("descriptor", "wpkh(0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798)")
                wallet.put("changeDescriptor", "wpkh(02c6047f9441ed7d6d3045406e95c07cd85a778e4b8cef3ca7abac09b95c709ee5)")
                wallet.put("network", "testnet")
                val txid = "1".repeat(64)
                if (id.isNotEmpty()) {
                    document.put("transactionLabels", JSONArray().put(JSONObject().put("walletId", id).put("txid", txid).put("label", "fixture label")))
                    document.put("utxoMetadata", JSONArray().put(JSONObject().put("walletId", id).put("outpoint", "$txid:0").put("label", "fixture coin")))
                }
                val result = manager.importStateBackupJson(document.toString())
                assertEquals(1, result.importedWallets)
                val restored = db.walletDao().getAll().single()
                assertTrue(restored.isWatchOnly)
                if (id.isEmpty()) {
                    assertEquals(restored.id, UUID.fromString(restored.id).toString())
                } else {
                    assertEquals(id, restored.id)
                    assertEquals("fixture label", db.transactionLabelDao().getByTxid(id, txid)?.label)
                    assertEquals(id, db.utxoMetadataDao().getByOutpoint("$txid:0")?.walletId)
                    assertEquals(1, result.importedLabels)
                    assertEquals(1, result.importedUtxoMetadata)
                }
            }
        }
    }

    private fun backup(vararg ids: String): JSONObject = JSONObject()
        .put("format", "clench-state-backup")
        .put("version", 1)
        .put("wallets", JSONArray().also { wallets -> ids.forEach { wallets.put(JSONObject().put("id", it)) } })
}
