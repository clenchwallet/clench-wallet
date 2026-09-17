package net.clench.wallet.data.repository

/** Applied both before native construction and to actual inputs before signing/export/broadcast. */
internal object FrozenInputPolicy {
    private val outpoint = Regex("[0-9a-f]{64}:(0|[1-9][0-9]*)")

    fun requireCanonicalOutpoint(value: String) {
        require(outpoint.matches(value) && value.substringAfter(':').toUIntOrNull() != null) {
            "Invalid selected outpoint"
        }
    }

    fun requireAllowed(inputs: Collection<String>, frozen: Set<String>) {
        inputs.forEach(::requireCanonicalOutpoint)
        require(inputs.none { it in frozen }) {
            "A transaction input is frozen. Unfreeze it explicitly or rebuild and review the transaction."
        }
    }
}
