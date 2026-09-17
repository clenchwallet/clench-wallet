package net.clench.wallet.data.local.dao

import androidx.room.*
import net.clench.wallet.data.local.UtxoMetadataPolicy
import net.clench.wallet.data.local.entity.UtxoMetadataEntity

@Dao
interface UtxoMetadataDao {

    // Raw rows are retained for lossless backup and conservative transaction policy.
    @Query("SELECT * FROM utxo_metadata WHERE walletId = :walletId")
    suspend fun getForWallet(walletId: String): List<UtxoMetadataEntity>

    @Query("SELECT * FROM utxo_metadata WHERE walletId = :walletId AND isFrozen = 1")
    suspend fun getFrozenForWallet(walletId: String): List<UtxoMetadataEntity>

    suspend fun getProjectedForWallet(walletId: String): List<UtxoMetadataEntity> =
        UtxoMetadataPolicy.project(getForWallet(walletId))

    suspend fun getByOutpoint(walletId: String, outpoint: String): UtxoMetadataEntity? =
        UtxoMetadataPolicy.project(UtxoMetadataPolicy.equivalentRows(getForWallet(walletId), outpoint)).singleOrNull()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UtxoMetadataEntity)

    @Transaction
    suspend fun setFrozen(walletId: String, outpoint: String, frozen: Boolean) {
        UtxoMetadataPolicy.equivalentRows(getForWallet(walletId), outpoint).forEach {
            upsert(it.copy(isFrozen = frozen))
        }
    }

    @Transaction
    suspend fun setLabel(walletId: String, outpoint: String, label: String?) {
        UtxoMetadataPolicy.equivalentRows(getForWallet(walletId), outpoint).forEach {
            upsert(it.copy(label = label))
        }
    }

    // One transaction prevents label races from undoing freezes. A frozen alias must
    // also be explicitly clearable: toggle the effective OR and update every alias.
    @Transaction
    suspend fun toggleFrozen(walletId: String, outpoint: String): Boolean {
        val aliases = UtxoMetadataPolicy.equivalentRows(getForWallet(walletId), outpoint).ifEmpty {
            listOf(UtxoMetadataEntity(requireNotNull(UtxoMetadataPolicy.canonicalOutpoint(outpoint)), walletId))
        }
        val frozen = !aliases.any { it.isFrozen }
        aliases.forEach { upsert(it.copy(isFrozen = frozen)) }
        return frozen
    }

    @Transaction
    suspend fun upsertLabel(walletId: String, outpoint: String, label: String?) {
        val aliases = UtxoMetadataPolicy.equivalentRows(getForWallet(walletId), outpoint).ifEmpty {
            listOf(UtxoMetadataEntity(requireNotNull(UtxoMetadataPolicy.canonicalOutpoint(outpoint)), walletId))
        }
        aliases.forEach { upsert(it.copy(label = label)) }
    }

    @Query("DELETE FROM utxo_metadata WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)
}
