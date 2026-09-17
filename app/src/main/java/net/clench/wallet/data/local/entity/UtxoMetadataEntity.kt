package net.clench.wallet.data.local.entity

import androidx.room.Entity

@Entity(tableName = "utxo_metadata", primaryKeys = ["walletId", "outpoint"])
data class UtxoMetadataEntity(
    val outpoint: String,  // "txid:vout"
    val walletId: String,
    val label: String? = null,
    val isFrozen: Boolean = false
)
