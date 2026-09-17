package net.clench.wallet.data.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NetworkAccessGateTest {
    @Test fun `initial offline blocks admission and native work`() {
        val gate = NetworkAccessGate { true }
        assertTrue(runCatching { gate.begin() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { gate.token() }.exceptionOrNull() is IOException)
    }

    @Test fun `toggle closes active transports once and rejects delayed work after reconnect`() {
        var offline = false
        val gate = NetworkAccessGate { offline }
        val oldToken = gate.token()
        val old = gate.begin()
        var closes = 0
        val cancelled = java.util.concurrent.CountDownLatch(1)
        old.register { closes++; cancelled.countDown() }
        gate.changeMode { offline = true }
        assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, closes)
        assertTrue(runCatching { old.requireCurrent() }.isFailure)
        gate.changeMode { offline = false }
        assertTrue(runCatching { gate.requireCurrent(oldToken) }.isFailure)
        assertTrue(runCatching { old.admit { fail("Stale work must not execute") } }.isFailure)
        gate.begin().use { it.requireCurrent() }
        old.close()
        assertEquals(1, closes)
    }

    @Test fun `late resource registration is cancelled and cleanup failures do not skip siblings`() {
        var offline = false
        val gate = NetworkAccessGate { offline }
        val lease = gate.begin()
        var closed = 0
        val cancelled = java.util.concurrent.CountDownLatch(1)
        lease.register { closed++; cancelled.countDown() }
        lease.register { throw IOException("cleanup") }
        gate.changeMode { offline = true }
        assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, closed)
        assertTrue(runCatching { lease.register { closed++ } }.isFailure)
        assertEquals(2, closed)
    }

    @Test fun `offline invalidation never waits for admitted local commit or transport cleanup`() {
        var offline = false
        val gate = NetworkAccessGate { offline }
        val lease = gate.begin()
        val cleanupStarted = java.util.concurrent.CountDownLatch(1)
        val allowCleanup = java.util.concurrent.CountDownLatch(1)
        lease.register { cleanupStarted.countDown(); allowCleanup.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        val token = gate.token()
        val commitStarted = java.util.concurrent.CountDownLatch(1)
        val allowCommit = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val commit = worker.submit { gate.commit(token) { commitStarted.countDown(); allowCommit.await(5, java.util.concurrent.TimeUnit.SECONDS) } }
            assertTrue(commitStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            gate.changeMode { offline = true }
            assertTrue(runCatching { gate.begin() }.isFailure)
            assertTrue(cleanupStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runCatching { lease.requireCurrent() }.isFailure)
            allowCleanup.countDown(); allowCommit.countDown()
            commit.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally { allowCleanup.countDown(); allowCommit.countDown(); worker.shutdownNow() }
    }

    @Test fun `stale results cannot commit after mode cycle while fresh results can`() {
        var offline = false
        val gate = NetworkAccessGate { offline }
        val original = gate.token()
        gate.changeMode { offline = true }
        gate.changeMode { offline = false }
        var committed = false
        assertTrue(runCatching { gate.commit(original) { committed = true } }.isFailure)
        assertFalse(committed)
        gate.commit(gate.token()) { committed = true }
        assertTrue(committed)
    }
}
