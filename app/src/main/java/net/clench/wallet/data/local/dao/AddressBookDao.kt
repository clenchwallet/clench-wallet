package net.clench.wallet.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.clench.wallet.data.local.entity.AddressBookEntryEntity

@Dao
interface AddressBookDao {

    @Query("SELECT * FROM address_book_entries WHERE walletId = :walletId ORDER BY lastUsedAt DESC, label COLLATE NOCASE")
    suspend fun getForWallet(walletId: String): List<AddressBookEntryEntity>

    @Query("SELECT * FROM address_book_entries WHERE walletId = :walletId AND address = :address LIMIT 1")
    suspend fun getByAddress(walletId: String, address: String): AddressBookEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AddressBookEntryEntity)

    @Query("DELETE FROM address_book_entries WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM address_book_entries WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)
}
