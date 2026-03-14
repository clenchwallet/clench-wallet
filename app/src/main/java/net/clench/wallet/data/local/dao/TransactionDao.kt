package net.clench.wallet.data.local.dao

import androidx.room.*
import net.clench.wallet.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions WHERE walletId = :walletId ORDER BY timestampEpochMs DESC")
    fun observeForWallet(walletId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE walletId = :walletId ORDER BY timestampEpochMs DESC")
    suspend fun getForWallet(walletId: String): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("DELETE FROM transactions WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)
}
