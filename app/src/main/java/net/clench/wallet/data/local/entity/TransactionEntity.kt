package net.clench.wallet.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "transactions",
    primaryKeys = ["txid", "walletId"]
)
data class TransactionEntity(
    val txid: String,
    val walletId: String,
    val amountSat: Long,
    val feeSat: Long?,
    val timestampEpochMs: Long?,
    val confirmations: Int,
    val direction: String,   // "SENT" or "RECEIVED"
    val address: String?
)
