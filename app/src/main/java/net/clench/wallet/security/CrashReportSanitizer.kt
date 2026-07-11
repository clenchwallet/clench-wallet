package net.clench.wallet.security

/** Pure, testable redaction for any crash text that could contain wallet secrets. */
object CrashReportSanitizer {
    private val extendedKey = Regex("(?i)(?:xprv|tprv|yprv|zprv|uprv|vprv|xpub|tpub|ypub|zpub|upub|vpub)[1-9A-HJ-NP-Za-km-z]{90,}")
    private val wif = Regex("(?<![1-9A-HJ-NP-Za-km-z])[KL5c9][1-9A-HJ-NP-Za-km-z]{50,51}(?![1-9A-HJ-NP-Za-km-z])")
    private val rawPrivateKey = Regex("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")
    private val mnemonic = Regex("(?<!\\S)(?:[a-z]{3,8}\\s){11,23}[a-z]{3,8}(?!\\S)")

    fun sanitize(report: String): String = report
        .replace(extendedKey, "[REDACTED_EXTENDED_KEY]")
        .replace(wif, "[REDACTED_WIF]")
        .replace(rawPrivateKey, "[REDACTED_32_BYTE_SECRET]")
        .replace(mnemonic, "[REDACTED_MNEMONIC]")
}
