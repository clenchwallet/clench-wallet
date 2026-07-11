package net.clench.wallet.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FeePresentationTest {
    @Test
    fun `invalid rates are rejected by presentation guidance`() {
        assertEquals(FeeRateGuidance.Invalid, FeePresentation.guidance("", 10.0))
        assertEquals(FeeRateGuidance.Invalid, FeePresentation.guidance("NaN", 10.0))
        assertEquals(FeeRateGuidance.Invalid, FeePresentation.guidance("0", 10.0))
        assertEquals(FeeRateGuidance.Invalid, FeePresentation.guidance("-1", 10.0))
    }

    @Test
    fun `elevated and ceiling rates are distinguished`() {
        assertEquals(FeeRateGuidance.NetworkEstimate, FeePresentation.guidance("30", 10.0))
        assertEquals(FeeRateGuidance.Elevated, FeePresentation.guidance("30.1", 10.0))
        assertEquals(FeeRateGuidance.Elevated, FeePresentation.guidance("1000", 10.0))
        assertEquals(FeeRateGuidance.HardLimit, FeePresentation.guidance("1000.1", 10.0))
    }
}
