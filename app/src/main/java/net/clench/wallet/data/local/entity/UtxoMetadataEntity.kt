package net.clench.wallet.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "utxo_metadata")
data class UtxoMetadataEntity(
    @PrimaryKey val outpoint: String,  // "txid:vout"
    val walletId: String,
    val label: String? = null,
    val isFrozen: Boolean = false
)
