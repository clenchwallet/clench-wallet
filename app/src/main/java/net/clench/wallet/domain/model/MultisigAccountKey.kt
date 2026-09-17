package net.clench.wallet.domain.model

import java.math.BigInteger
import java.security.MessageDigest

/** A single public account key, never descriptor syntax or a list of keys. */
internal data class MultisigAccountKey private constructor(
    val expression: String,
    val identity: String,
    val isTestnet: Boolean
) {
    companion object {
        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private val grammar = Regex("""^(?:\[([0-9a-fA-F]{8})((?:/[0-9]+['hH]?)*)\])?([1-9A-HJ-NP-Za-km-z]{100,120})(/(?:[01]/\*|\*\*))?$""")
        private val versions = mapOf(
            "0488b21e" to false, "049d7cb2" to false, "04b24746" to false,
            "0295b43f" to false, "02aa7ed3" to false,
            "043587cf" to true, "044a5262" to true, "045f1cf6" to true,
            "024289ef" to true, "02575483" to true
        )

        fun parse(raw: String, expectedTestnet: Boolean? = null): MultisigAccountKey {
            val match = grammar.matchEntire(raw.trim())
                ?: throw IllegalArgumentException("Each cosigner must contain exactly one public account key and an optional receive/change suffix")
            val bytes = decode(match.groupValues[3])
            require(bytes.size == 78) { "Invalid extended public key length" }
            val testnet = versions[bytes.copyOfRange(0, 4).hex()]
                ?: throw IllegalArgumentException("Unsupported public account key version")
            require(expectedTestnet == null || expectedTestnet == testnet) { "Cosigner network does not match the wallet" }
            require(bytes[45] == 2.toByte() || bytes[45] == 3.toByte()) { "Cosigner must be a compressed public account key" }
            val fingerprint = match.groupValues[1].uppercase()
            val path = match.groupValues[2].split('/').filter { it.isNotEmpty() }
            if (path.isNotEmpty()) {
                require(path.size == 4 && path.all { it.last() in "'hH" }) { "Cosigner origin must be a hardened BIP48 account path" }
                val indexes = path.map { it.dropLast(1).toLongOrNull() }
                require(indexes.all { it != null && it in 0..0x7fffffffL } &&
                    indexes[0] == 48L && indexes[1] == (if (testnet) 1L else 0L) && indexes[3] == 2L) {
                    "Cosigner origin must use the wallet network's native SegWit BIP48 account path"
                }
            }
            val origin = if (fingerprint.isEmpty()) "" else "[$fingerprint${path.joinToString("") { "/${it.dropLast(1).toLong()}'" }}]"
            val canonicalVersion = if (testnet) "043587cf" else "0488b21e"
            canonicalVersion.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(bytes)
            return MultisigAccountKey(
                expression = origin + encode(bytes),
                identity = bytes.copyOfRange(13, 78).hex(),
                isTestnet = testnet
            )
        }

        private fun decode(value: String): ByteArray {
            var number = BigInteger.ZERO
            value.forEach { number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(ALPHABET.indexOf(it).toLong())) }
            val magnitude = number.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.drop(1).toByteArray() else it }
            val decoded = ByteArray(value.takeWhile { it == '1' }.length) + magnitude
            require(decoded.size >= 5) { "Invalid extended key" }
            val payload = decoded.copyOfRange(0, decoded.size - 4)
            require(MessageDigest.isEqual(checksum(payload), decoded.takeLast(4).toByteArray())) { "Invalid extended public key checksum" }
            return payload
        }

        private fun encode(payload: ByteArray): String {
            val bytes = payload + checksum(payload)
            var number = BigInteger(1, bytes)
            val result = StringBuilder()
            while (number > BigInteger.ZERO) {
                val parts = number.divideAndRemainder(BigInteger.valueOf(58))
                result.append(ALPHABET[parts[1].toInt()])
                number = parts[0]
            }
            return "1".repeat(bytes.takeWhile { it == 0.toByte() }.size) + result.reverse()
        }

        private fun checksum(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").let {
            it.digest(it.digest(bytes)).copyOfRange(0, 4)
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
