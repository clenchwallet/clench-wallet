package net.clench.wallet.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "address_book_entries",
    indices = [
        Index(value = ["walletId"]),
        Index(value = ["walletId", "address"], unique = true)
    ]
)
data class AddressBookEntryEntity(
    @PrimaryKey val key: String,
    val walletId: String,
    val label: String,
    val address: String,
    val lastUsedAt: Long,
    val createdAt: Long
)
