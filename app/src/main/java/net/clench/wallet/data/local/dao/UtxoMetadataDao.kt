package net.clench.wallet.data.local.dao

import androidx.room.*
import net.clench.wallet.data.local.entity.UtxoMetadataEntity

@Dao
interface UtxoMetadataDao {

    @Query("SELECT * FROM utxo_metadata WHERE walletId = :walletId")
    suspend fun getForWallet(walletId: String): List<UtxoMetadataEntity>

    @Query("SELECT * FROM utxo_metadata WHERE walletId = :walletId AND isFrozen = 1")
    suspend fun getFrozenForWallet(walletId: String): List<UtxoMetadataEntity>

    @Query("SELECT * FROM utxo_metadata WHERE walletId = :walletId AND outpoint = :outpoint")
    suspend fun getByOutpoint(walletId: String, outpoint: String): UtxoMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UtxoMetadataEntity)

    @Query("UPDATE utxo_metadata SET isFrozen = :frozen WHERE walletId = :walletId AND outpoint = :outpoint")
    suspend fun setFrozen(walletId: String, outpoint: String, frozen: Boolean)

    @Query("UPDATE utxo_metadata SET label = :label WHERE walletId = :walletId AND outpoint = :outpoint")
    suspend fun setLabel(walletId: String, outpoint: String, label: String?)

    // One Room transaction prevents concurrent label changes from undoing a freeze.
    @Transaction
    suspend fun toggleFrozen(walletId: String, outpoint: String): Boolean {
        val current = getByOutpoint(walletId, outpoint)
            ?: UtxoMetadataEntity(outpoint = outpoint, walletId = walletId)
        val frozen = !current.isFrozen
        upsert(current.copy(isFrozen = frozen))
        return frozen
    }

    @Transaction
    suspend fun upsertLabel(walletId: String, outpoint: String, label: String?) {
        val current = getByOutpoint(walletId, outpoint)
            ?: UtxoMetadataEntity(outpoint = outpoint, walletId = walletId)
        upsert(current.copy(label = label))
    }

    @Query("DELETE FROM utxo_metadata WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)
}
