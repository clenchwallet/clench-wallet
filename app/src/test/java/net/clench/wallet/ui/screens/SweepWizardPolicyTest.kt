package net.clench.wallet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepWizardPolicyTest {
    @Test
    fun `wizard exposes the required five stages in order`() {
        assertEquals(
            listOf("Source", "Discovery", "Destination", "Fee", "Review"),
            SweepWizardStep.entries.map { it.label }
        )
    }

    @Test
    fun `wallet destination continues without external acknowledgement`() {
        assertTrue(SweepWizardPolicy.destinationReady("bc1wallet", "bc1wallet", false))
    }

    @Test
    fun `external destination requires acknowledgement`() {
        assertFalse(SweepWizardPolicy.destinationReady("bc1external", "bc1wallet", false))
        assertTrue(SweepWizardPolicy.destinationReady("bc1external", "bc1wallet", true))
    }

    @Test
    fun `seed readiness requires an exact supported word count`() {
        assertTrue(SweepWizardPolicy.seedReady(12, 12))
        assertTrue(SweepWizardPolicy.seedReady(24, 24))
        assertFalse(SweepWizardPolicy.seedReady(11, 12))
        assertFalse(SweepWizardPolicy.seedReady(15, 15))
    }
}
