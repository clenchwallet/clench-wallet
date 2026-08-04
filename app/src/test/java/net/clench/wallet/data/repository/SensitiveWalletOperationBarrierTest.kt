package net.clench.wallet.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveWalletOperationBarrierTest {
    @Test
    fun `drain rejects new work and waits for an already-admitted operation`() = runBlocking {
        val barrier = SensitiveWalletOperationBarrier()
        val releaseOperation = CompletableDeferred<Unit>()
        val operationStarted = CompletableDeferred<Unit>()
        val operation = async {
            barrier.withLease { lease ->
                barrier.assertActive(lease)
                operationStarted.complete(Unit)
                releaseOperation.await()
            }
        }
        operationStarted.await()

        val ticket = barrier.beginDrain()
        assertFalse(barrier.isOpen())
        assertThrows(WalletCacheEvictionInProgressException::class.java) { barrier.acquire() }
        val drain = async { barrier.awaitAndMarkEvicting(ticket) }
        assertFalse(drain.isCompleted)

        releaseOperation.complete(Unit)
        withTimeout(1_000L) { operation.await(); drain.await() }
        assertEquals(SensitiveWalletOperationBarrier.Mode.EVICTING, barrier.currentMode())
    }

    @Test
    fun `secured cleanup may retry but native failure can never reopen`() = runBlocking {
        val retryable = SensitiveWalletOperationBarrier()
        val first = retryable.beginDrain()
        retryable.awaitAndMarkEvicting(first)
        retryable.markSecured(first)

        val retry = retryable.beginDrain()
        retryable.awaitAndMarkEvicting(retry)
        retryable.markSecured(retry)
        retryable.reopen(retry)
        retryable.withLease { lease -> assertTrue(lease.id > 0L) }

        val fatal = SensitiveWalletOperationBarrier()
        val fatalTicket = fatal.beginDrain()
        fatal.awaitAndMarkEvicting(fatalTicket)
        fatal.markFailedRestartRequired(fatalTicket)
        assertTrue(fatal.awaitAndMarkEvicting(fatal.beginDrain()))
        assertThrows(WalletCacheRestartRequiredException::class.java) { fatal.acquire() }
        Unit
    }

    @Test
    fun `failed secured-state invariant permanently closes same-process admission`() = runBlocking {
        val barrier = SensitiveWalletOperationBarrier()
        val ticket = barrier.beginDrain()
        barrier.awaitAndMarkEvicting(ticket)
        barrier.markSecured(ticket)

        // Models an empty-cache/reopen invariant failure discovered after native cleanup.
        barrier.markFailedRestartRequired(ticket)

        assertEquals(
            SensitiveWalletOperationBarrier.Mode.FAILED_RESTART_REQUIRED,
            barrier.currentMode()
        )
        assertThrows(WalletCacheRestartRequiredException::class.java) { barrier.acquire() }
        assertTrue(barrier.awaitAndMarkEvicting(barrier.beginDrain()))
        Unit
    }

    @Test
    fun `native failure during drain releases waiter as restart-required`() = runBlocking {
        val barrier = SensitiveWalletOperationBarrier()
        val lease = barrier.acquire()
        val ticket = barrier.beginDrain()
        val awaitingDrain = async {
            barrier.awaitAndMarkEvicting(ticket)
        }

        barrier.markFailedRestartRequiredFromOperation()
        barrier.release(lease)

        withTimeout(1_000L) { assertTrue(awaitingDrain.await()) }
        assertEquals(
            SensitiveWalletOperationBarrier.Mode.FAILED_RESTART_REQUIRED,
            barrier.currentMode()
        )
        assertThrows(WalletCacheRestartRequiredException::class.java) { barrier.acquire() }
        Unit
    }

    @Test
    fun `fatal before drain still permits best-effort cleanup ticket but never admission`() = runBlocking {
        val barrier = SensitiveWalletOperationBarrier()
        barrier.markFailedRestartRequiredFromOperation()

        val ticket = barrier.beginDrain()
        assertTrue(barrier.awaitAndMarkEvicting(ticket))
        assertEquals(
            SensitiveWalletOperationBarrier.Mode.FAILED_RESTART_REQUIRED,
            barrier.currentMode()
        )
        assertThrows(WalletCacheRestartRequiredException::class.java) { barrier.acquire() }
        assertThrows(IllegalStateException::class.java) { barrier.reopen(ticket) }
        Unit
    }

    @Test
    fun `fatal transition with active lease establishes one reusable drain`() = runBlocking {
        val barrier = SensitiveWalletOperationBarrier()
        val lease = barrier.acquire()

        barrier.markFailedRestartRequiredFromOperation()
        val first = barrier.beginDrain()
        val second = barrier.beginDrain()
        val firstDrain = async { barrier.awaitAndMarkEvicting(first) }
        val secondDrain = async { barrier.awaitAndMarkEvicting(second) }

        assertFalse(firstDrain.isCompleted)
        assertFalse(secondDrain.isCompleted)
        barrier.release(lease)

        withTimeout(1_000L) {
            assertTrue(firstDrain.await())
            assertTrue(secondDrain.await())
        }
        assertEquals(
            SensitiveWalletOperationBarrier.Mode.FAILED_RESTART_REQUIRED,
            barrier.currentMode()
        )
    }

    @Test
    fun `quarantined native wrapper close is never retried`() {
        val barrier = SensitiveWalletOperationBarrier()
        val wrapper = Any()
        var closeCalls = 0
        val action = NativeWalletResourceCleanup.CloseAction(wrapper) {
            closeCalls++
            error("freed then failed")
        }

        assertFalse(barrier.attemptCloseNativeResources(listOf(action)))
        assertFalse(barrier.attemptCloseNativeResources(listOf(action)))
        assertEquals(1, closeCalls)
        assertTrue(barrier.hasQuarantinedNativeResources())
    }
}
