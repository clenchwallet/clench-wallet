package net.clench.wallet.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinThrottlePolicyTest {

    @Test
    fun `same boot uses monotonic elapsed time`() {
        val decision = PinThrottlePolicy.remainingDelay(
            attempts = 6,
            storedElapsedMs = 10_000L,
            storedBootCount = 42,
            nowElapsedMs = 35_000L,
            nowBootCount = 42
        )

        assertEquals(35_000L, decision.remainingMs)
        assertFalse(decision.reanchor)
    }

    @Test
    fun `reboot restarts delay instead of bypassing throttle`() {
        val decision = PinThrottlePolicy.remainingDelay(
            attempts = 7,
            storedElapsedMs = 900_000L,
            storedBootCount = 42,
            nowElapsedMs = 5_000L,
            nowBootCount = 43
        )

        assertEquals(120_000L, decision.remainingMs)
        assertTrue(decision.reanchor)
    }

    @Test
    fun `monotonic rollback with unavailable boot counter fails closed`() {
        val decision = PinThrottlePolicy.remainingDelay(
            attempts = 5,
            storedElapsedMs = 900_000L,
            storedBootCount = -1,
            nowElapsedMs = 5_000L,
            nowBootCount = -1
        )

        assertEquals(30_000L, decision.remainingMs)
        assertTrue(decision.reanchor)
    }

    @Test
    fun `boot counter availability change fails closed`() {
        val decision = PinThrottlePolicy.remainingDelay(
            attempts = 5,
            storedElapsedMs = 10_000L,
            storedBootCount = -1,
            nowElapsedMs = 20_000L,
            nowBootCount = 7
        )

        assertEquals(30_000L, decision.remainingMs)
        assertTrue(decision.reanchor)
    }

    @Test
    fun `legacy record without monotonic anchor starts full delay`() {
        val decision = PinThrottlePolicy.remainingDelay(
            attempts = 5,
            storedElapsedMs = -1L,
            storedBootCount = -1,
            nowElapsedMs = 25_000L,
            nowBootCount = 3
        )

        assertEquals(30_000L, decision.remainingMs)
        assertTrue(decision.reanchor)
    }

    @Test
    fun `delay saturates without shift wraparound`() {
        assertEquals(0L, PinThrottlePolicy.delayForAttempts(4))
        assertEquals(30_000L, PinThrottlePolicy.delayForAttempts(5))
        assertEquals(30L * 60L * 1_000L, PinThrottlePolicy.delayForAttempts(Int.MAX_VALUE))
    }
}
