package net.clench.wallet.domain.model

import org.bitcoindevkit.Address
import org.bitcoindevkit.Network
import org.bitcoindevkit.Script
import org.bitcoindevkit.Transaction

data class RawTransactionPreview(
    val normalizedHex: String,
    val txid: String,
    val vsize: Long,
    val totalSize: Long,
    val isRbf: Boolean,
    val outputs: List<RawTransactionOutputPreview>
)

data class RawTransactionOutputPreview(
    val index: Int,
    val amountSat: Long,
    val address: String?,
    val scriptPubkeyHex: String
)

object RawTransactionPayload {

    fun parse(input: String, network: Network): RawTransactionPreview {
        val bytes = decode(input)
        val tx = Transaction(bytes)
        return try {
            val txid = tx.computeTxid()
            val txidText = try {
                txid.toString()
            } finally {
                txid.close()
            }
            val nativeOutputs = tx.output()
            val outputs = try {
                nativeOutputs.mapIndexed { index, output ->
                    val script = output.scriptPubkey
                    RawTransactionOutputPreview(
                        index = index,
                        amountSat = output.value.toSat().toLong(),
                        address = addressString(script, network),
                        scriptPubkeyHex = script.toBytes().toHex()
                    )
                }
            } finally {
                destroyAll(nativeOutputs)
            }
            RawTransactionPreview(
                normalizedHex = bytes.toHex(),
                txid = txidText,
                vsize = tx.vsize().toLong(),
                totalSize = tx.totalSize().toLong(),
                isRbf = tx.isExplicitlyRbf(),
                outputs = outputs
            )
        } finally {
            // Transaction is a UniFFI native wrapper. Do not leave it to a
            // later finalizer after parsing attacker-controlled raw input.
            tx.close()
        }
    }

    private fun addressString(script: Script, network: Network): String? {
        val address = try {
            Address.fromScript(script, network)
        } catch (_: Exception) {
            return null
        }
        return try {
            address.toString()
        } finally {
            address.close()
        }
    }

    /** Attempt every nested UniFFI cleanup before propagating the first failure. */
    private fun destroyAll(outputs: List<org.bitcoindevkit.TxOut>) {
        var failure: Throwable? = null
        outputs.forEach { output ->
            // TxOut.destroy() is fail-fast and may skip Script if Amount close
            // fails. Close both owned fields independently instead.
            listOf<() -> Unit>(
                { output.value.close() },
                { output.scriptPubkey.close() }
            ).forEach { close ->
                try {
                    close()
                } catch (t: Throwable) {
                    val previous = failure
                    if (previous == null) failure = t else previous.addSuppressed(t)
                }
            }
        }
        failure?.let { throw it }
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
