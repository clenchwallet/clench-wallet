package net.clench.wallet.data.network

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Waits for a blocking/native operation without allowing it to hold a wallet
 * mutex forever.
 *
 * Coroutine timeouts cannot pre-empt a thread that is blocked in native code.
 * Electrum operations therefore run on a dedicated executor and are awaited
 * through this helper. Timeout cleanup closes the underlying connection before
 * cancellation so a socket-blocked native call has a chance to unwind.
 */
internal object BoundedBlockingCall {
    private const val TERMINATION_POLL_MS = 250L
    /**
     * Create a resource behind the same hard deadline while ensuring that a
     * native constructor which ignores interruption cannot leak a resource by
     * completing after its caller has already timed out.
     */
    fun <T : Any> awaitResource(
        executor: ExecutorService,
        timeoutMs: Long,
        operation: String,
        create: () -> T,
        close: (T) -> Unit,
        onCloseFailure: (T) -> Unit = {}
    ): T {
        val abandoned = AtomicBoolean(false)
        val published = AtomicReference<T?>(null)
        val future = executor.submit(java.util.concurrent.Callable {
            val resource = create()
            if (abandoned.get()) {
                try {
                    close(resource)
                } catch (_: Throwable) {
                    onCloseFailure(resource)
                }
                throw InterruptedException("$operation completed after its caller stopped waiting")
            }

            published.set(resource)
            if (abandoned.get() && published.compareAndSet(resource, null)) {
                try {
                    close(resource)
                } catch (_: Throwable) {
                    onCloseFailure(resource)
                }
                throw InterruptedException("$operation completed after its caller stopped waiting")
            }
            resource
        })

        return await(
            future = future,
            timeoutMs = timeoutMs,
            operation = operation,
            onTimeout = {
                abandoned.set(true)
                published.getAndSet(null)?.let { resource ->
                    try {
                        close(resource)
                    } catch (_: Throwable) {
                        onCloseFailure(resource)
                    }
                }
            }
        )
    }

    fun <T> await(
        future: Future<T>,
        timeoutMs: Long,
        operation: String,
        onTimeout: () -> Unit = {}
    ): T {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(operation.isNotBlank()) { "operation must not be blank" }

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            runCatching(onTimeout)
            future.cancel(true)
            throw TimeoutException("$operation timed out after ${timeoutMs}ms").also {
                it.initCause(timeout)
            }
        } catch (interrupted: InterruptedException) {
            runCatching(onTimeout)
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    /**
     * Cancel a dedicated native-worker executor, latch fail-closed after the grace period, and
     * never return until its worker has actually terminated.
     *
     * `Future.cancel(true)` and `shutdownNow()` are requests, not termination proof. Callers own
     * native Wallet/Transaction/request wrappers used by the task and must not close them until
     * this method returns. If native code ignores cancellation, [onTerminationStalled] should
     * close process-wide admission while this call deliberately retains the owner's stack/lease.
     */
    fun shutdownAndAwaitTermination(
        executor: ExecutorService,
        operation: String,
        terminationGraceMs: Long = 5_000L,
        onTerminationStalled: () -> Unit = {}
    ) {
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(terminationGraceMs >= 0L) { "terminationGraceMs must not be negative" }
        executor.shutdownNow()
        val graceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(terminationGraceMs)
        var stalledReported = false
        var restoreInterrupt = false
        while (true) {
            val terminated = try {
                executor.awaitTermination(TERMINATION_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // Native owner cleanup is a security boundary. Preserve the interrupt but keep
                // waiting so a cancelled coroutine cannot close wrappers still used by a worker.
                restoreInterrupt = true
                executor.shutdownNow()
                false
            }
            if (terminated) break
            if (!stalledReported && System.nanoTime() >= graceDeadline) {
                stalledReported = true
                runCatching(onTerminationStalled)
            }
        }
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }
}
