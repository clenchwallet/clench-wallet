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

    fun <T> commit(token: Long, block: () -> T): T = synchronized(monitor) { requireCurrent(token); block() }

    fun begin(): Lease = synchronized(monitor) { Lease(token()).also { leases.add(it) } }

    /** Persist the preference and close admission atomically, before closing transports. */
    fun changeMode(persist: () -> Unit) {
        val cancelled = synchronized(monitor) {
            persist()
            generation++
            leases.toList().also { leases.clear() }
        }
        cancelled.forEach { it.close() }
    }

    inner class Lease internal constructor(private val epoch: Long) : AutoCloseable {
        private var closed = false
        private val cleanup = mutableListOf<() -> Unit>()

        fun requireCurrent() = synchronized(monitor) {
            if (closed) throw IOException("Network operation is closed")
            this@NetworkAccessGate.requireCurrent(epoch)
        }

        fun register(cancel: () -> Unit) {
            synchronized(monitor) {
                try { requireCurrent() } catch (failure: IOException) {
                    runCatching(cancel)
                    throw failure
                }
                cleanup.add(cancel)
            }
        }

        /** Admission and a short write/commit share the toggle boundary. Never use for reads. */
        fun <T> admit(block: () -> T): T = synchronized(monitor) { requireCurrent(); block() }

        override fun close() {
            val actions = synchronized(monitor) {
                if (closed) return
                closed = true
                leases.remove(this)
                cleanup.toList().also { cleanup.clear() }
            }
            actions.asReversed().forEach { runCatching(it) }
        }
    }
}
