package net.clench.wallet.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BoundedBlockingCallTest {

    @Test
    fun `completed blocking operation returns its result`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit(Callable { "complete" })

            assertEquals(
                "complete",
                BoundedBlockingCall.await(future, timeoutMs = 1_000, operation = "test operation")
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `timeout closes transport and cancels blocked operation`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanedUp = AtomicBoolean(false)
        try {
            val future = executor.submit(Callable {
                entered.countDown()
                release.await()
                "late"
            })
            assertTrue(entered.await(1, TimeUnit.SECONDS))

            val failure = runCatching {
                BoundedBlockingCall.await(
                    future,
                    timeoutMs = 25,
                    operation = "Electrum full scan",
                    onTimeout = { cleanedUp.set(true) }
                )
            }.exceptionOrNull()

            assertTrue(failure is TimeoutException)
            assertEquals("Electrum full scan timed out after 25ms", failure?.message)
            assertTrue(cleanedUp.get())
            assertTrue(future.isCancelled)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `execution failure preserves the original cause`() {
        val executor = Executors.newSingleThreadExecutor()
        val expected = IllegalStateException("native failure")
        try {
            val future = executor.submit(Callable<String> { throw expected })

            try {
                BoundedBlockingCall.await(future, timeoutMs = 1_000, operation = "test operation")
                fail("Expected original failure")
            } catch (actual: Throwable) {
                assertSame(expected, actual)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `resource completed after timeout is closed instead of leaked`() {
        data class Resource(val closed: AtomicBoolean = AtomicBoolean(false))

        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        try {
            val failure = runCatching {
                BoundedBlockingCall.awaitResource(
                    executor = executor,
                    timeoutMs = 25,
                    operation = "native connection",
                    create = {
                        entered.countDown()
                        var waiting = true
                        while (waiting) {
                            try {
                                waiting = !release.await(1, TimeUnit.SECONDS)
                            } catch (_: InterruptedException) {
                                // Deliberately model a native constructor that ignores interruption.
                            }
                        }
                        Resource()
                    },
                    close = {
                        it.closed.set(true)
                        closed.countDown()
                    }
                )
            }.exceptionOrNull()

            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(failure is TimeoutException)
            release.countDown()
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupted waiter closes transport cancels work and preserves interrupt`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanedUp = AtomicBoolean(false)
        val interruptedFlag = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        try {
            val future = executor.submit(Callable {
                entered.countDown()
                release.await()
                "late"
            })
            assertTrue(entered.await(1, TimeUnit.SECONDS))

            val waiter = Thread {
                try {
                    BoundedBlockingCall.await(
                        future,
                        timeoutMs = 30_000,
                        operation = "Electrum full scan",
                        onTimeout = { cleanedUp.set(true) }
                    )
                    fail("Expected interruption")
                } catch (actual: Throwable) {
                    failure.set(actual)
                    interruptedFlag.set(Thread.currentThread().isInterrupted)
                }
            }
            waiter.start()
            waiter.interrupt()
            waiter.join(1_000)

            assertTrue(failure.get() is InterruptedException)
            assertTrue(cleanedUp.get())
            assertTrue(future.isCancelled)
            assertTrue(interruptedFlag.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `native owner cleanup waits for actual worker termination and reports a stall`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = AtomicBoolean(false)
        val stalled = CountDownLatch(1)
        val terminationVerified = CountDownLatch(1)
        executor.submit {
            entered.countDown()
            while (!release.get()) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    // Model native work which does not honor Future cancellation.
                }
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val waiter = Thread {
            BoundedBlockingCall.shutdownAndAwaitTermination(
                executor = executor,
                operation = "stuck native owner",
                terminationGraceMs = 25L,
                onTerminationStalled = { stalled.countDown() }
            )
            terminationVerified.countDown()
        }
        waiter.start()

        assertTrue(stalled.await(1, TimeUnit.SECONDS))
        assertEquals(1L, terminationVerified.count)
        release.set(true)
        assertTrue(terminationVerified.await(1, TimeUnit.SECONDS))
        waiter.join(1_000L)
    }
}
