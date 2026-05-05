package net.clench.wallet.data.local.dao

import androidx.room.*
import net.clench.wallet.data.local.entity.UtxoMetadataEntity

@Dao
interface UtxoMetadataDao {

    @Query("SELECT * FROM utxo_metadata WHERE walletId = :walletId")
    suspend fun getForWallet(walletId: String): List<UtxoMetadataEntity>

    @Query("SELECT * FROM utxo_metadata WHERE walletId = :walletId AND isFrozen = 1")
    suspend fun getFrozenForWallet(walletId: String): List<UtxoMetadataEntity>

    @Query("SELECT * FROM utxo_metadata WHERE outpoint = :outpoint")
    suspend fun getByOutpoint(outpoint: String): UtxoMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UtxoMetadataEntity)

    @Query("UPDATE utxo_metadata SET isFrozen = :frozen WHERE outpoint = :outpoint")
    suspend fun setFrozen(outpoint: String, frozen: Boolean)

    @Query("UPDATE utxo_metadata SET label = :label WHERE outpoint = :outpoint")
    suspend fun setLabel(outpoint: String, label: String?)

    @Query("DELETE FROM utxo_metadata WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)
}
