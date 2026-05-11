package net.clench.wallet.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.clench.wallet.data.local.entity.SavedSignerEntity

@Dao
interface SavedSignerDao {
    @Query("SELECT * FROM saved_signers ORDER BY updatedAtEpochMs DESC")
    suspend fun getAll(): List<SavedSignerEntity>

    @Query("SELECT * FROM saved_signers WHERE network = :network AND scriptType = :scriptType ORDER BY updatedAtEpochMs DESC")
    suspend fun getForNetworkAndScript(network: String, scriptType: String): List<SavedSignerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(signer: SavedSignerEntity)

    @Query("DELETE FROM saved_signers WHERE id = :id")
    suspend fun delete(id: String)
}
