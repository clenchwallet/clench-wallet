package net.clench.wallet.data.local.dao

import androidx.room.*
import net.clench.wallet.data.local.entity.TransactionLabelEntity

@Dao
interface TransactionLabelDao {

    @Query("SELECT * FROM transaction_labels WHERE walletId = :walletId AND txid = :txid LIMIT 1")
    suspend fun getByTxid(walletId: String, txid: String): TransactionLabelEntity?

    @Query("SELECT * FROM transaction_labels WHERE walletId = :walletId ORDER BY updatedAt DESC")
    suspend fun getForWallet(walletId: String): List<TransactionLabelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransactionLabelEntity)

    @Query("DELETE FROM transaction_labels WHERE walletId = :walletId AND txid = :txid")
    suspend fun delete(walletId: String, txid: String)

    @Query("DELETE FROM transaction_labels WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)
}
