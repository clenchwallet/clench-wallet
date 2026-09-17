package net.clench.wallet.domain.model

/**
 * Empty alone means absent. Whitespace is wallet identity, never formatting to trim.
 * Pass the original characters to BDK; its BIP39 normalization is authoritative.
 */
object Bip39Passphrase {
    fun isPresent(value: String?): Boolean = !value.isNullOrEmpty()
    fun value(value: String?): String = value.orEmpty()
    fun optional(value: String?): String? = value?.takeIf { it.isNotEmpty() }
}
