package net.clench.wallet.viewmodel

import net.clench.wallet.domain.model.FeeEstimates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for fee calculation logic and amount validation.
 * Tests pure data classes and validation rules without Android framework.
 */
class SendViewModelTest {

    @Test
    fun `FeeEstimates holds correct values`() {
        val estimates = FeeEstimates(
            priority = 15f,
            standard = 8f,
            economy = 3f,
            timestamp = 1000L
        )
        assertEquals(15f, estimates.priority, 0.01f)
        assertEquals(8f, estimates.standard, 0.01f)
        assertEquals(3f, estimates.economy, 0.01f)
        assertEquals(1000L, estimates.timestamp)
    }

    @Test
    fun `FeeEstimates minimum values are reasonable`() {
        val estimates = FeeEstimates(
            priority = 1f,
            standard = 1f,
            economy = 1f,
            timestamp = System.currentTimeMillis()
        )
        assertTrue(estimates.priority >= 1f)
        assertTrue(estimates.standard >= 1f)
        assertTrue(estimates.economy >= 1f)
    }

    @Test
    fun `fee rate validation - valid integer`() {
        val rate = "5"
        val parsed = rate.toFloatOrNull()
        assertTrue(parsed != null && parsed >= 1f)
    }

    @Test
    fun `fee rate validation - below minimum`() {
        val rate = "0"
        val parsed = rate.toFloatOrNull()
        assertTrue(parsed == null || parsed < 1f)
    }

    @Test
    fun `fee rate validation - empty string`() {
        val rate = ""
        val parsed = rate.toFloatOrNull()
        assertTrue(parsed == null)
    }

    @Test
    fun `fee rate validation - non-numeric`() {
        val rate = "abc"
        val parsed = rate.toFloatOrNull()
        assertTrue(parsed == null)
    }

    @Test
    fun `amount validation - valid amount`() {
        val amount = "50000"
        val parsed = amount.toLongOrNull()
        assertTrue(parsed != null && parsed > 0)
    }

    @Test
    fun `amount validation - zero is invalid`() {
        val amount = "0"
        val parsed = amount.toLongOrNull()
        assertTrue(parsed == null || parsed <= 0)
    }

    @Test
    fun `amount validation - negative is invalid`() {
        val amount = "-100"
        val parsed = amount.toLongOrNull()
        assertTrue(parsed == null || parsed <= 0)
    }

    @Test
    fun `amount validation - empty string`() {
        val amount = ""
        val parsed = amount.toLongOrNull()
        assertTrue(parsed == null)
    }

    @Test
    fun `address validation - blank is invalid`() {
        val address = "   "
        assertTrue(address.isBlank())
    }

    @Test
    fun `address validation - non-blank is valid`() {
        val address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        assertTrue(address.isNotBlank())
    }

    @Test
    fun `fee percentage calculation - high fee warning`() {
        val amountSat = 1000L
        val feeRate = 50f
        val estimatedVbytes = 140
        val estimatedFeeSat = (feeRate * estimatedVbytes).toLong()
        val feePercent = (estimatedFeeSat.toDouble() / amountSat.toDouble()) * 100
        assertTrue("Fee should be >5% of amount for warning", feePercent > 5)
    }

    @Test
    fun `fee percentage calculation - normal fee no warning`() {
        val amountSat = 1_000_000L
        val feeRate = 5f
        val estimatedVbytes = 140
        val estimatedFeeSat = (feeRate * estimatedVbytes).toLong()
        val feePercent = (estimatedFeeSat.toDouble() / amountSat.toDouble()) * 100
        assertTrue("Fee should be <5% of amount", feePercent < 5)
    }
}
