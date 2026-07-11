package net.clench.wallet.data.repository

internal object ReplacementTransactionPolicy {
    fun evictionTimestamp(lastSeen: ULong?, nowEpochSeconds: ULong): ULong {
        val baseline = lastSeen ?: nowEpochSeconds
        return maxOf(nowEpochSeconds, baseline + 1uL)
    }

    inline fun <T> withTemporaryEviction(
        evict: () -> Unit,
        restore: () -> Unit,
        build: () -> T
    ): T {
        evict()
        return try {
            build()
        } finally {
            restore()
        }
    }
}
