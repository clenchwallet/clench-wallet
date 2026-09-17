package net.clench.wallet.data.local.dao

import androidx.room.*
import net.clench.wallet.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    // NULLs (unconfirmed) appear first, then confirmed newest-first
    @Query("SELECT * FROM transactions WHERE walletId = :walletId ORDER BY timestampEpochMs IS NOT NULL, timestampEpochMs DESC")
    fun observeForWallet(walletId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE walletId = :walletId ORDER BY timestampEpochMs IS NOT NULL, timestampEpochMs DESC")
    suspend fun getForWallet(walletId: String): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    /** Only a complete successful native scan may replace display history.
     * Missing/failed optional explorer lookups are not authoritative evidence.
     */
    @Transaction
    suspend fun replaceFromSuccessfulSync(walletId: String, snapshot: List<TransactionEntity>) {
        require(snapshot.all { it.walletId == walletId }) { "History snapshot belongs to a different wallet" }
        require(snapshot.map { it.txid }.distinct().size == snapshot.size) { "Duplicate transaction in history snapshot" }
        deleteForWallet(walletId)
        insertAll(snapshot)
    }

    @Query("DELETE FROM transactions WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)
}
