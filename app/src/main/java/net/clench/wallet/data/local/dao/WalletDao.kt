package net.clench.wallet.data.local.dao

import androidx.room.*
import net.clench.wallet.data.local.entity.WalletEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {

    @Query("SELECT * FROM wallets ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE network = :network ORDER BY createdAtEpochMs DESC")
    fun getWalletsByNetwork(network: String): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE network = :network ORDER BY createdAtEpochMs DESC")
    suspend fun getAllByNetwork(network: String): List<WalletEntity>

    @Query("SELECT * FROM wallets ORDER BY createdAtEpochMs DESC")
    suspend fun getAll(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE id = :id")
    suspend fun getById(id: String): WalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(wallet: WalletEntity)

    @Delete
    suspend fun delete(wallet: WalletEntity)

    @Query("DELETE FROM wallets WHERE id = :id")
    suspend fun deleteById(id: String)
}
