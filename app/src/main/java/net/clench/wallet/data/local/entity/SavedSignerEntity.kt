package net.clench.wallet.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_signers",
    indices = [
        Index("network"),
        Index("scriptType"),
        Index(value = ["fingerprint", "derivationPath", "xpub"], unique = true)
    ]
)
data class SavedSignerEntity(
    @PrimaryKey val id: String,
    val label: String,
    val xpub: String,
    val fingerprint: String?,
    val derivationPath: String,
    val network: String,
    val scriptType: String,
    val deviceType: String?,
    val source: String?,
    val verified: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
