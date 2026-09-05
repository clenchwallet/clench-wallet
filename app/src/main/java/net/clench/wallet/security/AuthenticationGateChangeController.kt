package net.clench.wallet.security

enum class AuthenticationGate { SEED, SEND }

/** Main-thread policy boundary. Each weakening needs a fresh, single-use authentication callback. */
class AuthenticationGateChangeController(
    private val isEnabled: (AuthenticationGate) -> Boolean,
    private val persist: (AuthenticationGate, Boolean) -> Unit
) {
    private var pending: Any? = null

    fun cancel() { pending = null }

    fun request(
        gate: AuthenticationGate,
        enabled: Boolean,
        authenticate: (onSuccess: () -> Unit, onAbort: () -> Unit) -> Unit
    ) {
        cancel()
        if (isEnabled(gate) == enabled) return
        if (enabled) {
            persist(gate, true)
            return
        }
        val token = Any()
        pending = token
        try {
            authenticate(
                {
                    if (pending === token) {
                        pending = null
                        persist(gate, false)
                    }
                },
                { if (pending === token) pending = null }
            )
        } catch (t: Throwable) {
            if (pending === token) pending = null
            throw t
        }
    }
}
