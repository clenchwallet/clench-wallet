package net.clench.wallet.data.repository

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Enforces policy invariants that are easy for a descriptor parser to accept
 * but unsafe for a wallet UI to describe as M-of-N independent signers.
 */
internal object MultisigDescriptorSafety {
    private val multisigFunction = Regex("""(?i)(?:sortedmulti|multi)\(""")
    private const val BASE58_ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun validate(descriptor: String) {
        val withoutChecksum = descriptor.substringBefore('#').trim()
        val functions = multisigFunction.findAll(withoutChecksum).toList()
        if (functions.isEmpty()) return
        functions.forEach { function ->
            validateFunction(withoutChecksum, function)
        }
    }

    private fun validateFunction(descriptor: String, function: MatchResult) {
        val openParen = descriptor.indexOf('(', function.range.first)
        val closeParen = matchingParen(descriptor, openParen)
        require(closeParen > openParen) { "Multisig descriptor has unbalanced parentheses" }

        val arguments = splitTopLevel(descriptor.substring(openParen + 1, closeParen))
        require(arguments.size >= 3) { "Multisig descriptor must include a threshold and at least two signers" }
        val threshold = arguments.first().trim().toIntOrNull()
            ?: throw IllegalArgumentException("Multisig descriptor has an invalid threshold")
        val keys = arguments.drop(1).map { it.trim() }
        require(keys.size <= 20) { "Multisig descriptor contains too many signers" }
        require(threshold in 1..keys.size) { "Multisig threshold is outside the signer set" }
        require(keys.none { it.isBlank() }) { "Multisig descriptor contains an empty signer key" }

        val canonicalKeys = keys.map(::canonicalSigner)
        require(canonicalKeys.distinct().size == canonicalKeys.size) {
            "Duplicate cosigner key detected. Each multisig signer must be unique."
        }
    }

    private fun matchingParen(text: String, openParen: Int): Int {
        if (openParen < 0) return -1
        var depth = 0
        for (index in openParen until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                    if (depth < 0) return -1
                }
            }
        }
        return -1
    }

    private fun splitTopLevel(arguments: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var bracketDepth = 0
        var start = 0
        arguments.forEachIndexed { index, character ->
            when (character) {
                '(' -> depth++
                ')' -> depth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                ',' -> if (depth == 0 && bracketDepth == 0) {
                    result += arguments.substring(start, index)
                    start = index + 1
                }
            }
            require(depth >= 0 && bracketDepth >= 0) { "Multisig descriptor is malformed" }
        }
        require(depth == 0 && bracketDepth == 0) { "Multisig descriptor is malformed" }
        result += arguments.substring(start)
        return result
    }

    fun canonicalSigner(raw: String): String {
        val trimmed = raw.trim()
        val withoutOrigin = if (trimmed.startsWith('[')) {
            val end = trimmed.indexOf(']')
            require(end > 0) { "Multisig signer has a malformed key origin" }
            trimmed.substring(end + 1)
        } else trimmed
        val key = withoutOrigin.substringBefore('/').trim()
        require(key.isNotEmpty()) { "Multisig signer has no public key" }
        val extendedKey = decodeBase58Check(key)
        return when {
            extendedKey?.size == 78 ->
                // Version/depth/parent fingerprint/child number are serialization metadata.
                // Child derivation identity is the chain code and key, starting at byte 13.
                "bip32:" + extendedKey.copyOfRange(13, extendedKey.size).toHex()
            key.matches(Regex("(?i)^[0-9a-f]{66}$")) -> key.lowercase()
            else -> key
        }
    }

    private fun decodeBase58Check(value: String): ByteArray? {
        if (value.length !in 16..120) return null
        var number = BigInteger.ZERO
        val radix = BigInteger.valueOf(58)
        for (character in value) {
            val digit = BASE58_ALPHABET.indexOf(character)
            if (digit < 0) return null
            number = number.multiply(radix).add(BigInteger.valueOf(digit.toLong()))
        }
        val magnitude = number.toByteArray().let { bytes ->
            if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
        val decoded = ByteArray(value.takeWhile { it == '1' }.length + magnitude.size)
        magnitude.copyInto(decoded, decoded.size - magnitude.size)
        if (decoded.size < 5) return null
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val digest = MessageDigest.getInstance("SHA-256")
        val expected = digest.digest(digest.digest(payload)).copyOfRange(0, 4)
        return payload.takeIf { MessageDigest.isEqual(checksum, expected) }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
