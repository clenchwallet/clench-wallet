package net.clench.wallet.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey val id: String,
    val name: String,
    val descriptor: String,
    val changeDescriptor: String,
    val isWatchOnly: Boolean,
    val isMultisig: Boolean,
    val createdAtEpochMs: Long
)
