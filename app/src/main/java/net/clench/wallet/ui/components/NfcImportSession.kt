package net.clench.wallet.ui.components

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Owns one NFC import and rejects callbacks from cancelled or superseded readers. */
internal class NfcImportSession {
    private val active = AtomicReference<Attempt?>(null)

    fun begin(pin: CharArray?): Attempt {
        val attempt = Attempt(pin ?: CharArray(0))
        active.getAndSet(attempt)?.cancel()
        return attempt
    }

    fun isCurrent(attempt: Attempt): Boolean = active.get() === attempt && !attempt.cancelled

    fun hasActiveAttempt(): Boolean = active.get()?.let { !it.cancelled } == true

    fun cancel() { active.getAndSet(null)?.cancel() }

    class Attempt internal constructor(private val pendingPin: CharArray) {
        @Volatile var cancelled = false
            private set
        private var claimed = false
        private var closeConnection: (() -> Unit)? = null

        /** The worker owns this copy until its finally block; cancellation cannot alter it. */
        @Synchronized fun claimPin(): CharArray? {
            if (cancelled || claimed) return null
            claimed = true
            return pendingPin.copyOf().also { pendingPin.fill('0') }
        }

        fun attach(close: () -> Unit) {
            val rejected = synchronized(this) {
                if (cancelled) true else {
                    check(closeConnection == null) { "NFC connection already attached" }
                    closeConnection = close
                    false
                }
            }
            if (rejected) {
                runCatching { close() }
                throw CancellationException("NFC import cancelled")
            }
        }

        fun requireActive() {
            if (cancelled) throw CancellationException("NFC import cancelled")
        }

        internal fun cancel() {
            val close = synchronized(this) {
                cancelled = true
                pendingPin.fill('0')
                closeConnection.also { closeConnection = null }
            }
            // Closing an active IsoDep interrupts blocked I/O. Do not hold our monitor
            // while calling the platform, and never let cleanup restore an old attempt.
            if (close != null) runCatching { close() }
        }
    }
}
