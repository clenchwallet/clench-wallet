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
    val identiconBytes: ByteArray? = null,   // Legacy 8-byte fallback hash for older fingerprint image rendering
    val masterFingerprint: String? = null,    // e.g., "D3E95C19" — extracted from descriptor origin at import
    val derivationPath: String? = null,       // e.g., "84'/0'/0'" — extracted from descriptor origin at import
    val importedViaDevice: String? = null     // e.g., "COLDCARD_Q" — HardwareWalletType enum name used at import
)
