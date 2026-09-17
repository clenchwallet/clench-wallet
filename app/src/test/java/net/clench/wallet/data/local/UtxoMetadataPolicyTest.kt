package net.clench.wallet.data.local

import net.clench.wallet.data.local.entity.UtxoMetadataEntity
import org.junit.Assert.*
import org.junit.Test

class UtxoMetadataPolicyTest {
    private val canonical = "${"a".repeat(64)}:0"
    private val alias = "${"A".repeat(64)}:00"

    @Test fun `canonical identities are bounded and losslessly interpreted`() {
        assertEquals(canonical, UtxoMetadataPolicy.canonicalOutpoint(alias))
        assertEquals("${"a".repeat(64)}:4294967295", UtxoMetadataPolicy.canonicalOutpoint("${"A".repeat(64)}:4294967295"))
        for (invalid in listOf("$canonical:1", "${"a".repeat(64)}:-1", "${"a".repeat(64)}:4294967296", "bad:0")) {
            assertNull(UtxoMetadataPolicy.canonicalOutpoint(invalid))
        }
    }

    @Test fun `projection uses freeze OR but never mutates labels or joins different wallets`() {
        val rows = listOf(UtxoMetadataEntity(canonical, "a", "first", false),
            UtxoMetadataEntity(alias, "a", "second", true), UtxoMetadataEntity(alias, "b", "other", false))
        val projected = UtxoMetadataPolicy.project(rows).associateBy { it.walletId }
        assertEquals(2, projected.size)
        assertEquals(true, projected["a"]?.isFrozen)
        assertEquals("first / second", projected["a"]?.label)
        assertEquals(false, projected["b"]?.isFrozen)
        assertEquals(canonical, projected["a"]?.outpoint)
        assertEquals(listOf("first", "second", "other"), rows.map { it.label })
        assertEquals(listOf(false, true, false), rows.map { it.isFrozen })
        assertEquals(projected, UtxoMetadataPolicy.project(rows.reversed()).associateBy { it.walletId })
    }
}
