package net.clench.wallet.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.clench.wallet.data.local.entity.WalletKeystoreMetadataEntity

@Dao
interface WalletKeystoreMetadataDao {
    @Query("SELECT * FROM wallet_keystore_metadata WHERE walletId = :walletId")
    suspend fun getForWallet(walletId: String): List<WalletKeystoreMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: WalletKeystoreMetadataEntity)
}
