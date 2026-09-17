package net.clench.wallet.security

/** One-shot, current-policy authorization for relaxing background locking. */
class RelockTimeoutChangeController(
    private val currentKey: () -> String,
    private val currentMode: () -> String,
    private val persist: (String) -> Unit
) {
    private var pending: Any? = null
    fun cancel() { pending = null }

    fun request(
        key: String,
        isSessionCurrent: () -> Boolean,
        authenticate: (onSuccess: () -> Unit, onAbort: () -> Unit) -> Unit
    ) {
        cancel()
        val desired = duration(key)
        val oldKey = currentKey()
        val oldMode = currentMode()
        if (key == oldKey || !isSessionCurrent()) return
        if (desired <= duration(oldKey) || oldMode == "none") {
            persist(key)
            return
        }
        check(oldMode == "pin" || oldMode == "biometric") { "Unsupported app lock mode" }
        val token = Any()
        pending = token
        try {
            authenticate({
                if (pending === token) {
                    pending = null
                    if (isSessionCurrent() && currentMode() == oldMode && currentKey() == oldKey) {
                        persist(key)
                    }
                }
            }, { if (pending === token) pending = null })
        } catch (failure: Throwable) {
            if (pending === token) pending = null
            throw failure
        }
    }

    companion object {
        fun duration(key: String): Long = when (key) {
            "30s" -> 30_000L
            "1min" -> 60_000L
            "5min" -> 300_000L
            "never" -> Long.MAX_VALUE
            else -> throw IllegalArgumentException("Unsupported relock timeout")
        }
    }
}
