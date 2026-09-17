package net.clench.wallet.data.network

import java.io.IOException

/** Process-local network admission. Toggling invalidates old work even after going online again.
 * In-flight transports are closed; already transmitted bytes cannot be recalled.
 */
class NetworkAccessGate(private val isOffline: () -> Boolean) {
    private val monitor = Any()
    private var generation = 0L
    private val leases = mutableSetOf<Lease>()

    fun token(): Long = synchronized(monitor) {
        if (isOffline()) throw IOException("Network actions are unavailable in offline mode")
        generation
    }

    fun requireCurrent(token: Long) = synchronized(monitor) {
        if (isOffline() || token != generation) throw IOException("Network operation was cancelled by an offline-mode change")
    }

    // Admission is short. A local commit already admitted before a toggle may finish;
    // never hold the mode-change monitor across native work, database fsync or I/O.
    fun <T> commit(token: Long, block: () -> T): T { requireCurrent(token); return block() }

    fun begin(): Lease = synchronized(monitor) { Lease(token()).also { leases.add(it) } }

    /** Persist the preference and close admission atomically, before closing transports. */
    fun changeMode(persist: () -> Unit) {
        val actions = synchronized(monitor) {
            persist()
            generation++
            leases.toList().flatMap { it.invalidateLocked() }
        }
        // Cancellation markers are terminal and already visible. Socket/disconnect cleanup
        // may wait on a vendor implementation; never make the settings UI wait for it.
        if (actions.isNotEmpty()) Thread({ actions.asReversed().forEach { runCatching(it) } },
            "clench-offline-cleanup").apply { isDaemon = true; start() }
    }

    inner class Lease internal constructor(private val epoch: Long) : AutoCloseable {
        private var closed = false
        private val cleanup = mutableListOf<() -> Unit>()

        fun requireCurrent() = synchronized(monitor) {
            if (closed) throw IOException("Network operation is closed")
            this@NetworkAccessGate.requireCurrent(epoch)
        }

        fun register(cancel: () -> Unit) {
            val accepted = synchronized(monitor) {
                if (closed || isOffline() || epoch != generation) false
                else { cleanup.add(cancel); true }
            }
            if (!accepted) {
                runCatching(cancel)
                throw IOException("Network operation was cancelled")
            }
        }

        fun <T> admit(block: () -> T): T { requireCurrent(); return block() }

        internal fun invalidateLocked(): List<() -> Unit> {
            if (closed) return emptyList()
            closed = true
            leases.remove(this)
            return cleanup.toList().also { cleanup.clear() }
        }

        override fun close() {
            val actions = synchronized(monitor) { invalidateLocked() }
            actions.asReversed().forEach { runCatching(it) }
        }

    }
}
