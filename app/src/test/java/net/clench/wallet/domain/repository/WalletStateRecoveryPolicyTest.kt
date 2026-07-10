package net.clench.wallet.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletStateRecoveryPolicyTest {
    @Test
    fun `recommended gaps cover bounded shallow through extended scans`() {
        assertEquals(listOf(20, 100, 250, 500, 1_000), WalletStateRecoveryPolicy.recommendedStopGaps)
        assertTrue(WalletStateRecoveryPolicy.recommendedStopGaps.all { WalletStateRecoveryPolicy.isValidStopGap(it.toUInt()) })
    }

    @Test
    fun `normalization and validation enforce recovery boundaries`() {
        assertEquals(20, WalletStateRecoveryPolicy.normalizeStopGap(1))
        assertEquals(1_000, WalletStateRecoveryPolicy.normalizeStopGap(10_000))
        assertFalse(WalletStateRecoveryPolicy.isValidStopGap(19u))
        assertTrue(WalletStateRecoveryPolicy.isValidStopGap(20u))
        assertTrue(WalletStateRecoveryPolicy.isValidStopGap(1_000u))
        assertFalse(WalletStateRecoveryPolicy.isValidStopGap(1_001u))
    }
}
