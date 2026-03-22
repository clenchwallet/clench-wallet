package net.clench.wallet.data.repository

import net.clench.wallet.domain.model.WalletBalance
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for WalletBalance calculations — pure data class, no Android dependencies.
 */
class WalletBalanceTest {

    @Test
    fun `totalSat includes confirmed and pending`() {
        val balance = WalletBalance(
            confirmedSat = 100_000L,
            trustedPendingSat = 50_000L,
            untrustedPendingSat = 25_000L,
            immatureSat = 10_000L
        )
        assertEquals(175_000L, balance.totalSat)
    }

    @Test
    fun `spendableSat excludes untrusted pending`() {
        val balance = WalletBalance(
            confirmedSat = 100_000L,
            trustedPendingSat = 50_000L,
            untrustedPendingSat = 25_000L,
            immatureSat = 10_000L
        )
        assertEquals(150_000L, balance.spendableSat)
    }

    @Test
    fun `zero balance`() {
        val balance = WalletBalance(0L, 0L, 0L, 0L)
        assertEquals(0L, balance.totalSat)
        assertEquals(0L, balance.spendableSat)
    }

    @Test
    fun `confirmed only`() {
        val balance = WalletBalance(
            confirmedSat = 1_000_000L,
            trustedPendingSat = 0L,
            untrustedPendingSat = 0L,
            immatureSat = 0L
        )
        assertEquals(1_000_000L, balance.totalSat)
        assertEquals(1_000_000L, balance.spendableSat)
    }

    @Test
    fun `untrusted pending included in total but not spendable`() {
        val balance = WalletBalance(
            confirmedSat = 0L,
            trustedPendingSat = 0L,
            untrustedPendingSat = 500_000L,
            immatureSat = 0L
        )
        assertEquals(500_000L, balance.totalSat)
        assertEquals(0L, balance.spendableSat)
    }

    @Test
    fun `immature not included in total or spendable`() {
        val balance = WalletBalance(
            confirmedSat = 0L,
            trustedPendingSat = 0L,
            untrustedPendingSat = 0L,
            immatureSat = 1_000_000L
        )
        assertEquals(0L, balance.totalSat)
        assertEquals(0L, balance.spendableSat)
    }
}
