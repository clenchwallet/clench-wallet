package net.clench.wallet.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.entity.TransactionEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransactionHistorySnapshotTest {
    private fun row(txid: String = "11".repeat(32), confirmations: Int = 6, time: Long? = 1000, wallet: String = "a") =
        TransactionEntity(txid, wallet, 100, null, time, confirmations, "RECEIVED", null)

    @Test fun authoritativeUnconfirmedReconfirmationEvictionAndReappearanceSurviveRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-snapshot-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ClenchDatabase::class.java, name).build()
        try {
            db.transactionDao().insertAll(listOf(row(), row(wallet = "b")))
            // Fresh native Unconfirmed defeats cached positive confirmations and old block time.
            val unconfirmed = row(confirmations = 0, time = null)
            db.transactionDao().replaceFromSuccessfulSync("a", listOf(unconfirmed))
            assertEquals(listOf(unconfirmed), db.transactionDao().getForWallet("a"))
            assertEquals(6, db.transactionDao().getForWallet("b").single().confirmations)
            // Repeated sync cannot recover stale cache values.
            db.transactionDao().replaceFromSuccessfulSync("a", listOf(unconfirmed))
            db.close()
            db = Room.databaseBuilder(context, ClenchDatabase::class.java, name).build()
            assertEquals(listOf(unconfirmed), db.transactionDao().getForWallet("a"))
            // Reconfirmation updates both chain-dependent fields, never just the count.
            val reconfirmed = row(confirmations = 1, time = 9000)
            db.transactionDao().replaceFromSuccessfulSync("a", listOf(reconfirmed))
            assertEquals(listOf(reconfirmed), db.transactionDao().getForWallet("a"))
            // Complete native history no longer contains the old transaction after eviction.
            db.transactionDao().replaceFromSuccessfulSync("a", emptyList())
            assertTrue(db.transactionDao().getForWallet("a").isEmpty())
            db.transactionDao().replaceFromSuccessfulSync("a", listOf(unconfirmed))
            assertEquals(listOf(unconfirmed), db.transactionDao().getForWallet("a"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun invalidOrInterruptedSnapshotCannotPartiallyDemoteHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ClenchDatabase::class.java).build()
        try {
            val previous = row()
            db.transactionDao().insertAll(listOf(previous))
            assertTrue(runCatching { db.transactionDao().replaceFromSuccessfulSync("a", listOf(row(wallet = "b"))) }.isFailure)
            assertEquals(listOf(previous), db.transactionDao().getForWallet("a"))
            assertTrue(runCatching { db.transactionDao().replaceFromSuccessfulSync("a", listOf(row(), row())) }.isFailure)
            assertEquals(listOf(previous), db.transactionDao().getForWallet("a"))
            // Force insertion to fail after deletion: @Transaction must restore the old row.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_history BEFORE INSERT ON transactions BEGIN SELECT RAISE(ABORT, 'fixture interruption'); END")
            assertTrue(runCatching { db.transactionDao().replaceFromSuccessfulSync("a", listOf(row(confirmations = 0))) }.isFailure)
            assertEquals(listOf(previous), db.transactionDao().getForWallet("a"))
        } finally { db.close() }
    }
}
