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
    val createdAtEpochMs: Long,
    val network: String = "mainnet",  // "mainnet" or "testnet"
    val preferredHardwareWallet: String? = null,
    val hasPassphrase: Boolean = false,
    val identiconBytes: ByteArray? = null   // 8-byte identicon hash (SHA-256 of masterFp + passphrase), drives visual fingerprint
)
