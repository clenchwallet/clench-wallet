package net.clench.wallet.data.repository

import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A non-serializing admission barrier for native wallet and secret-bearing operations.
 *
 * Foreground operations remain concurrent. Backgrounding closes admission synchronously, waits
 * for every already-admitted lease to finish, and only then permits native-cache detachment.
 */
@Singleton
class SensitiveWalletOperationBarrier @Inject internal constructor() {
    internal enum class Mode {
        OPEN,
        DRAINING,
        EVICTING,
        SECURED,
        FAILED_RESTART_REQUIRED
    }

    internal class Lease internal constructor(
        internal val generation: Long,
        internal val id: Long
    )

    internal class Ticket internal constructor(
        internal val generation: Long,
        internal val drained: Deferred<Unit>
    )

    private val guard = Any()
    private var generation = 0L
    private var nextId = 0L
    private var mode = Mode.OPEN
    private val liveLeaseIds = mutableSetOf<Long>()
    private val failedNativeResources: MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap())
    private var restartRequiredListener: (() -> Unit)? = null
    private var drained = completedDeferred()

    internal fun acquire(): Lease = synchronized(guard) {
        if (mode == Mode.FAILED_RESTART_REQUIRED) throw WalletCacheRestartRequiredException()
        if (mode != Mode.OPEN) throw WalletCacheEvictionInProgressException()
        Lease(generation = generation, id = ++nextId).also { liveLeaseIds += it.id }
    }

    internal fun release(lease: Lease) = synchronized(guard) {
        check(lease.generation == generation && liveLeaseIds.remove(lease.id)) {
            "Invalid sensitive wallet operation lease"
        }
        if (liveLeaseIds.isEmpty() &&
            (mode == Mode.DRAINING || mode == Mode.FAILED_RESTART_REQUIRED)
        ) {
            drained.complete(Unit)
        }
    }

    internal suspend fun <T> withLease(block: suspend (Lease) -> T): T {
        val lease = acquire()
        return try {
            block(lease)
        } finally {
            release(lease)
        }
    }

    internal fun <T> withSynchronousLease(block: (Lease) -> T): T {
        val lease = acquire()
        return try {
            block(lease)
        } finally {
            release(lease)
        }
    }

    internal fun assertActive(lease: Lease) = synchronized(guard) {
        check(lease.generation == generation && lease.id in liveLeaseIds) {
            "Sensitive wallet operation is no longer active"
        }
    }

    internal fun beginDrain(): Ticket = synchronized(guard) {
        when (mode) {
            Mode.OPEN -> {
                mode = Mode.DRAINING
                drained = CompletableDeferred<Unit>().also {
                    if (liveLeaseIds.isEmpty()) it.complete(Unit)
                }
            }
            Mode.SECURED -> {
                // Retry only fallible legacy/DAO/file verification while admission stays closed.
                mode = Mode.DRAINING
                drained = completedDeferred()
            }
            Mode.DRAINING, Mode.EVICTING -> Unit
            Mode.FAILED_RESTART_REQUIRED -> {
                // Admission remains permanently closed, but a cleanup pass must still be able
                // to drain existing work and attempt every other independent resource/file.
                // Reuse the fatal transition's deferred: replacing it would strand a ticket
                // already handed to another cleanup caller.
                if (liveLeaseIds.isEmpty()) drained.complete(Unit)
            }
        }
        Ticket(generation = generation, drained = drained)
    }

    /** Returns true when cleanup must preserve an already-latched restart-required outcome. */
    internal suspend fun awaitAndMarkEvicting(ticket: Ticket): Boolean = withContext(NonCancellable) {
        ticket.drained.await()
        synchronized(guard) {
            check(ticket.generation == generation &&
                (mode == Mode.DRAINING || mode == Mode.FAILED_RESTART_REQUIRED)
            ) {
                "Invalid sensitive wallet eviction ticket"
            }
            check(liveLeaseIds.isEmpty()) { "Sensitive wallet operations did not drain" }
            if (mode == Mode.FAILED_RESTART_REQUIRED) {
                true
            } else {
                mode = Mode.EVICTING
                false
            }
        }
    }

    internal fun markSecured(ticket: Ticket) = synchronized(guard) {
        check(ticket.generation == generation && mode == Mode.EVICTING)
        mode = Mode.SECURED
    }

    internal fun markFailedRestartRequired(ticket: Ticket) {
        val listener = synchronized(guard) {
            check(ticket.generation == generation && mode != Mode.OPEN)
            mode = Mode.FAILED_RESTART_REQUIRED
            if (liveLeaseIds.isEmpty()) drained.complete(Unit)
            restartRequiredListener
        }
        listener?.invoke()
    }

    /** A normal foreground replacement/lock close failed; no later access is trustworthy. */
    internal fun markFailedRestartRequiredFromOperation() {
        val listener = synchronized(guard) {
            if (mode != Mode.FAILED_RESTART_REQUIRED && mode == Mode.OPEN) {
                drained = CompletableDeferred<Unit>().also {
                    if (liveLeaseIds.isEmpty()) it.complete(Unit)
                }
            }
            mode = Mode.FAILED_RESTART_REQUIRED
            if (liveLeaseIds.isEmpty()) drained.complete(Unit)
            restartRequiredListener
        }
        listener?.invoke()
    }

    /**
     * Connect the native-resource fatal latch to the process-wide UI security gate.
     * Registration is race-safe: a listener installed after a failure is invoked immediately.
     */
    internal fun registerRestartRequiredListener(listener: () -> Unit) {
        val invokeImmediately = synchronized(guard) {
            check(restartRequiredListener == null || restartRequiredListener === listener) {
                "Sensitive wallet restart-required listener is already registered"
            }
            restartRequiredListener = listener
            mode == Mode.FAILED_RESTART_REQUIRED
        }
        if (invokeImmediately) listener()
    }

    internal fun reopen(ticket: Ticket) = synchronized(guard) {
        check(ticket.generation == generation && mode == Mode.SECURED)
        check(liveLeaseIds.isEmpty())
        generation++
        mode = Mode.OPEN
        drained = completedDeferred()
    }

    internal fun currentMode(): Mode = synchronized(guard) { mode }

    internal fun isOpen(): Boolean = synchronized(guard) { mode == Mode.OPEN }

    /** Attempt all destroys, quarantine failed wrappers, then permanently fail this process. */
    internal fun closeNativeResourcesOrFail(
        actions: Collection<NativeWalletResourceCleanup.CloseAction>
    ) {
        if (!attemptCloseNativeResources(actions)) throw WalletCacheRestartRequiredException()
    }

    /** Returns false only after quarantining every failed wrapper and latching fatal state. */
    internal fun attemptCloseNativeResources(
        actions: Collection<NativeWalletResourceCleanup.CloseAction>
    ): Boolean {
        val (alreadyQuarantined, newActions) = synchronized(guard) {
            actions.partition { it.resource in failedNativeResources }
        }
        val failures = NativeWalletResourceCleanup.closeResources(
            actions = newActions,
            retainFailed = { resource -> synchronized(guard) { failedNativeResources.add(resource) } }
        )
        if (alreadyQuarantined.isNotEmpty() || failures != 0) {
            markFailedRestartRequiredFromOperation()
            return false
        }
        return true
    }

    internal fun hasQuarantinedNativeResources(): Boolean = synchronized(guard) {
        failedNativeResources.isNotEmpty()
    }

    /** Retain a wrapper whose close was already attempted elsewhere; never retry that close. */
    internal fun quarantineNativeResource(resource: Any) {
        synchronized(guard) { failedNativeResources.add(resource) }
        markFailedRestartRequiredFromOperation()
    }

    private fun completedDeferred(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { it.complete(Unit) }
}
