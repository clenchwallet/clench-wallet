package net.clench.wallet.data.repository

/**
 * Closes both halves of every native wallet entry, even when an earlier close fails.
 *
 * The returned count deliberately contains no exception text: native error messages can
 * include descriptors or filesystem details and must not cross the repository boundary.
 */
internal object NativeWalletResourceCleanup {
    internal class CloseAction(
        val resource: Any,
        val close: () -> Unit
    )

    internal class CloseState<T>(val value: T) {
        var walletCloseAttempted: Boolean = false
            private set
        var persisterCloseAttempted: Boolean = false
            private set
        var walletClosed: Boolean = false
            private set
        var persisterClosed: Boolean = false
            private set

        val fullyClosed: Boolean
            get() = walletClosed && persisterClosed

        internal fun markWalletClosed() {
            walletClosed = true
        }

        internal fun markWalletCloseAttempted() {
            walletCloseAttempted = true
        }

        internal fun markPersisterClosed() {
            persisterClosed = true
        }

        internal fun markPersisterCloseAttempted() {
            persisterCloseAttempted = true
        }
    }

    fun <T> closeAll(
        entries: Collection<CloseState<T>>,
        closeWallet: (T) -> Unit,
        closePersister: (T) -> Unit
    ): Int {
        var failureCount = 0
        entries.forEach { state ->
            if (!state.walletCloseAttempted) {
                // A native close can free its handle and still throw. Mark attempted first and
                // never invoke it again; retain the failed wrapper for the rest of the process.
                state.markWalletCloseAttempted()
                try {
                    closeWallet(state.value)
                    state.markWalletClosed()
                } catch (_: Throwable) {
                    failureCount++
                }
            }
            if (!state.persisterCloseAttempted) {
                state.markPersisterCloseAttempted()
                try {
                    closePersister(state.value)
                    state.markPersisterClosed()
                } catch (_: Throwable) {
                    failureCount++
                }
            }
        }
        return failureCount
    }

    /** Attempt every independent native destroy and strongly retain each failed wrapper. */
    fun closeResources(
        actions: Collection<CloseAction>,
        retainFailed: (Any) -> Unit
    ): Int {
        var failureCount = 0
        actions.forEach { action ->
            try {
                action.close()
            } catch (_: Throwable) {
                retainFailed(action.resource)
                failureCount++
            }
        }
        return failureCount
    }
}

internal fun <T : Any> nativeCloseAction(
    resource: T?,
    close: (T) -> Unit
): NativeWalletResourceCleanup.CloseAction? = resource?.let { value ->
    NativeWalletResourceCleanup.CloseAction(value) { close(value) }
}

class WalletCacheSecurityCleanupException : IllegalStateException(
    "Wallet security cleanup did not complete. Restart Clench before continuing."
)

class WalletCacheEvictionInProgressException : IllegalStateException(
    "Wallet cache is being secured. Retry after returning to Clench."
)

class WalletCacheRestartRequiredException : IllegalStateException(
    "Native wallet cleanup could not be verified. Restart Clench before continuing."
)
