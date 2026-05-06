package net.clench.wallet.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "wallet_keystore_metadata",
    primaryKeys = ["walletId", "keyId"],
    indices = [Index("walletId")]
)
data class WalletKeystoreMetadataEntity(
    val walletId: String,
    val keyId: String,
    val label: String,
    val preferredHardwareWallet: String? = null,
    val updatedAtEpochMs: Long
)
