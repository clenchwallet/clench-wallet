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
        close: (T) -> Unit
    ): T {
        val abandoned = AtomicBoolean(false)
        val published = AtomicReference<T?>(null)
        val future = executor.submit(java.util.concurrent.Callable {
            val resource = create()
            if (abandoned.get()) {
                runCatching { close(resource) }
                throw InterruptedException("$operation completed after its caller stopped waiting")
            }

            published.set(resource)
            if (abandoned.get() && published.compareAndSet(resource, null)) {
                runCatching { close(resource) }
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
                published.getAndSet(null)?.let { runCatching { close(it) } }
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
}
