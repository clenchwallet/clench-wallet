package net.clench.wallet.data.local

import net.clench.wallet.data.local.entity.UtxoMetadataEntity

/** Canonical coin identity; raw legacy metadata rows remain losslessly stored/exported. */
object UtxoMetadataPolicy {
    private val outpointPattern = Regex("(?i)^[0-9a-f]{64}:[0-9]{1,10}$")

    fun canonicalOutpoint(value: String): String? {
        if (!outpointPattern.matches(value)) return null
        val index = value.substringAfter(':').toLongOrNull() ?: return null
        if (index > 0xffffffffL) return null
        return "${value.substringBefore(':').lowercase()}:$index"
    }

    /** Read-only projection: any frozen alias freezes the coin; no label is deleted. */
    fun project(rows: List<UtxoMetadataEntity>): List<UtxoMetadataEntity> = rows
        .groupBy { it.walletId to (canonicalOutpoint(it.outpoint) ?: it.outpoint) }
        .map { (identity, aliases) ->
            val labels = aliases.mapNotNull { it.label }.distinct().sorted()
            UtxoMetadataEntity(
                outpoint = identity.second,
                walletId = identity.first,
                // Bounded display text, not the stored/exported labels. Only an explicit
                // user edit replaces the individual labels of equivalent aliases.
                label = labels.takeIf { it.isNotEmpty() }?.joinToString(" / ")?.take(500),
                isFrozen = aliases.any { it.isFrozen }
            )
        }

    fun equivalentRows(rows: List<UtxoMetadataEntity>, outpoint: String): List<UtxoMetadataEntity> {
        val canonical = requireNotNull(canonicalOutpoint(outpoint)) { "Invalid coin outpoint" }
        return rows.filter { canonicalOutpoint(it.outpoint) == canonical }
    }
}
