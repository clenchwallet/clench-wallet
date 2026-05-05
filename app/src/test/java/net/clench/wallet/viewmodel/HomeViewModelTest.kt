package net.clench.wallet.viewmodel

import net.clench.wallet.ui.viewmodel.BalanceUnit
import net.clench.wallet.ui.viewmodel.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for HomeViewModel.formatBalance() — pure function, no Android dependencies.
 */
class HomeViewModelTest {

    @Test
    fun `formatBalance SATS zero`() {
        val result = HomeViewModel.formatBalance(0L, BalanceUnit.SATS, null, false)
        assertEquals("0 sats", result)
    }

    @Test
    fun `formatBalance SATS small amount`() {
        val result = HomeViewModel.formatBalance(1234L, BalanceUnit.SATS, null, false)
        assertEquals("1,234 sats", result)
    }

    @Test
    fun `formatBalance SATS large amount`() {
        val result = HomeViewModel.formatBalance(100_000_000L, BalanceUnit.SATS, null, false)
        assertEquals("100,000,000 sats", result)
    }

    @Test
    fun `formatBalance BTC zero`() {
        val result = HomeViewModel.formatBalance(0L, BalanceUnit.BTC, null, false)
        assertEquals("0.0 BTC", result)
    }

    @Test
    fun `formatBalance BTC one bitcoin`() {
        val result = HomeViewModel.formatBalance(100_000_000L, BalanceUnit.BTC, null, false)
        assertEquals("1.0 BTC", result)
    }

    @Test
    fun `formatBalance BTC small amount`() {
        val result = HomeViewModel.formatBalance(1L, BalanceUnit.BTC, null, false)
        assertEquals("0.00000001 BTC", result)
    }

    @Test
    fun `formatBalance BTC typical amount`() {
        val result = HomeViewModel.formatBalance(50_000L, BalanceUnit.BTC, null, false)
        assertEquals("0.0005 BTC", result)
    }

    @Test
    fun `formatBalance USD with price`() {
        val result = HomeViewModel.formatBalance(100_000_000L, BalanceUnit.USD, 50000.0, false)
        assertEquals("$50,000.00", result)
    }

    @Test
    fun `formatBalance USD with stale price`() {
        val result = HomeViewModel.formatBalance(100_000_000L, BalanceUnit.USD, 50000.0, true)
        assertEquals("~$50,000.00", result)
    }

    @Test
    fun `formatBalance USD no price`() {
        val result = HomeViewModel.formatBalance(100_000_000L, BalanceUnit.USD, null, false)
        assertEquals("USD unavailable", result)
    }

    @Test
    fun `formatBalance HIDDEN`() {
        val result = HomeViewModel.formatBalance(100_000_000L, BalanceUnit.HIDDEN, null, false)
        assertEquals("••••••", result)
    }

    @Test
    fun `formatBalance USD small amount`() {
        val result = HomeViewModel.formatBalance(1000L, BalanceUnit.USD, 65000.0, false)
        // 1000 sats at $65k = $0.65
        assertEquals("$0.65", result)
    }
}
