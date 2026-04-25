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

    @Query("SELECT * FROM wallets ORDER BY createdAtEpochMs DESC")
    fun getAllSync(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE id = :id")
    suspend fun getById(id: String): WalletEntity?

    @Query("SELECT * FROM wallets WHERE id = :walletId LIMIT 1")
    suspend fun getWalletById(walletId: String): WalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(wallet: WalletEntity)

    @Delete
    suspend fun delete(wallet: WalletEntity)

    @Query("DELETE FROM wallets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE wallets SET name = :name WHERE id = :walletId")
    suspend fun updateName(walletId: String, name: String)

    @Query("UPDATE wallets SET preferredHardwareWallet = :device WHERE id = :walletId")
    suspend fun updatePreferredHardwareWallet(walletId: String, device: String?)

    @Query("UPDATE wallets SET isWatchOnly = :isWatchOnly WHERE id = :walletId")
    suspend fun setWatchOnly(walletId: String, isWatchOnly: Boolean)

    @Query("UPDATE wallets SET isWatchOnly = :isWatchOnly, hasPassphrase = :hasPassphrase WHERE id = :walletId")
    suspend fun setWatchOnlyAndPassphrase(walletId: String, isWatchOnly: Boolean, hasPassphrase: Boolean)

    @Query("UPDATE wallets SET masterFingerprint = :fingerprint, derivationPath = :path, importedViaDevice = :device WHERE id = :walletId")
    suspend fun updateHardwareWalletInfo(walletId: String, fingerprint: String?, path: String?, device: String?)
}
