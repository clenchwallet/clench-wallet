package net.clench.wallet.domain.model

import org.bitcoindevkit.Transaction

data class RawTransactionPreview(
    val normalizedHex: String,
    val txid: String,
    val vsize: Long,
    val totalSize: Long,
    val isRbf: Boolean
)

object RawTransactionPayload {

    fun parse(input: String): RawTransactionPreview {
        val bytes = decode(input)
        val tx = Transaction(bytes)
        return RawTransactionPreview(
            normalizedHex = bytes.toHex(),
            txid = tx.computeTxid().toString(),
            vsize = tx.vsize().toLong(),
            totalSize = tx.totalSize().toLong(),
            isRbf = tx.isExplicitlyRbf()
        )
    }

    fun decode(input: String): ByteArray {
        val trimmed = input.trim()
            .removePrefix("bitcoin:")
            .substringBefore("?")
            .trim()
        if (trimmed.isBlank()) error("Paste or import a raw transaction first")

        val compact = trimmed
            .removePrefix("0x")
            .filterNot { it.isWhitespace() }

        if (compact.matches(Regex("^[0-9a-fA-F]+$"))) {
            require(compact.length % 2 == 0) { "Raw transaction hex must have an even number of characters" }
            return compact.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        val base64Bytes = runCatching { java.util.Base64.getDecoder().decode(compact) }
            .recoverCatching { java.util.Base64.getMimeDecoder().decode(compact) }
            .getOrNull()
        if (base64Bytes != null && base64Bytes.isNotEmpty()) return base64Bytes

        error("Raw transaction must be transaction hex or base64 bytes")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
