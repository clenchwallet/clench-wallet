package net.clench.wallet.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transaction_labels")
data class TransactionLabelEntity(
    @PrimaryKey val key: String,  // walletId:txid composite key
    val walletId: String,
    val txid: String,
    val label: String,
    val updatedAt: Long = System.currentTimeMillis()
)
