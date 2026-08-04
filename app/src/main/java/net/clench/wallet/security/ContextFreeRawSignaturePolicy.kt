package net.clench.wallet.security

/**
 * A conservative signature-hash guard for the Advanced Tools raw broadcaster.
 *
 * A raw transaction does not carry its spent outputs, original PSBT, or the
 * wallet policy. Clench therefore cannot prove which input type was intended
 * or that every signature is valid. This guard only rejects recognizable
 * signatures that do not commit to every input and output:
 *
 * - canonical DER ECDSA signatures must use SIGHASH_ALL (0x01);
 * - plausible Taproot signatures may use omitted SIGHASH_DEFAULT (64 bytes),
 *   or explicit SIGHASH_ALL (65 bytes ending in 0x01).
 *
 * Ambiguous 64/65-byte witness data is classified as Taproot only in a
 * key-path-shaped witness, or before a recognizable current-version Taproot
 * script/control-block pair. Unusual scripts may still be rejected
 * conservatively. Hardware-wallet returns should use the PSBT coordinator,
 * which has the original prevouts and policy and can perform stronger checks.
 */
internal object ContextFreeRawSignaturePolicy {

    fun validate(rawTransaction: ByteArray) {
        val inputs = parseTransaction(rawTransaction)
        inputs.forEachIndexed { index, input ->
            val context = "raw transaction input $index"
            val scriptSigItems = parseScriptPushes(input.scriptSig, context)

            // Canonical DER is recognizable without prevout context. Scan
            // every pushed/witness item so a weak ECDSA signature cannot be
            // hidden beside unrelated stack data.
            (scriptSigItems + input.witness).forEach { item ->
                if (isStrictDerSignatureWithHashType(item)) {
                    requireAllSighash(item.last(), "$context ECDSA signature")
                }
            }

            plausibleTaprootSignatures(input.witness).forEach { signature ->
                when (signature.size) {
                    64 -> Unit // BIP341 SIGHASH_DEFAULT: no flag byte.
                    65 -> requireAllSighash(signature.last(), "$context Taproot signature")
                }
            }
        }
    }

    private data class ParsedInput(
        val scriptSig: ByteArray,
        val witness: List<ByteArray>
    )

    /**
     * Parse only the consensus transaction framing needed by this policy.
     * This deliberately does not infer prevout script types. Canonical
     * CompactSize encodings and every script/witness boundary are checked so a
     * truncated item cannot make the signature scan inspect different bytes
     * than the network transaction parser.
     */
    private fun parseTransaction(raw: ByteArray): List<ParsedInput> {
        val cursor = Cursor(raw)
        cursor.skip(4, "version")

        val hasWitness = cursor.peekByte("input count or witness marker") == 0
        if (hasWitness) {
            cursor.readByte("witness marker")
            val flags = cursor.readByte("witness flags")
            if (flags != 0x01) {
                throw SecurityException("Raw transaction has unsupported witness flags 0x${flags.toString(16)}")
            }
        }

        val inputCount = cursor.readCompactSize("input count")
        if (inputCount !in 1..MAX_VECTOR_ITEMS) {
            throw SecurityException("Raw transaction has an invalid input count")
        }
        val inputs = ArrayList<ParsedInput>(inputCount.toInt())
        repeat(inputCount.toInt()) { index ->
            cursor.skip(36, "input $index outpoint")
            val scriptLength = cursor.readCompactSize("input $index scriptSig length")
            val scriptSig = cursor.readBytes(scriptLength, "input $index scriptSig")
            cursor.skip(4, "input $index sequence")
            inputs += ParsedInput(scriptSig, emptyList())
        }

        val outputCount = cursor.readCompactSize("output count")
        if (outputCount > MAX_VECTOR_ITEMS) {
            throw SecurityException("Raw transaction has too many outputs")
        }
        repeat(outputCount.toInt()) { index ->
            cursor.skip(8, "output $index value")
            val scriptLength = cursor.readCompactSize("output $index script length")
            cursor.skipLength(scriptLength, "output $index script")
        }

        if (hasWitness) {
            var nonEmptyWitness = false
            inputs.indices.forEach { index ->
                val itemCount = cursor.readCompactSize("input $index witness item count")
                if (itemCount > MAX_VECTOR_ITEMS) {
                    throw SecurityException("Raw transaction input $index has too many witness items")
                }
                if (itemCount > 0) nonEmptyWitness = true
                val witness = ArrayList<ByteArray>(itemCount.toInt())
                repeat(itemCount.toInt()) { itemIndex ->
                    val itemLength = cursor.readCompactSize(
                        "input $index witness item $itemIndex length"
                    )
                    witness += cursor.readBytes(
                        itemLength,
                        "input $index witness item $itemIndex"
                    )
                }
                inputs[index] = inputs[index].copy(witness = witness)
            }
            if (!nonEmptyWitness) {
                throw SecurityException("Raw transaction has a superfluous witness marker")
            }
        }

        cursor.skip(4, "locktime")
        if (!cursor.atEnd()) {
            throw SecurityException("Raw transaction has trailing bytes")
        }
        return inputs
    }

    private fun plausibleTaprootSignatures(witness: List<ByteArray>): List<ByteArray> {
        if (witness.isEmpty()) return emptyList()

        val withoutAnnex = if (
            witness.size >= 2 && witness.last().firstOrNull() == 0x50.toByte()
        ) {
            witness.dropLast(1)
        } else {
            witness
        }

        val candidates = when {
            // A single 64/65-byte item is the ordinary key-path shape. It is
            // indistinguishable from some unusual witness-v0 scripts without
            // fetching the prevout, so this deliberately fails conservatively.
            withoutAnnex.size == 1 -> withoutAnnex

            // BIP341 script path: stack..., tapscript, control block. Do not
            // mistake the tapscript, control block, or annex for signatures.
            withoutAnnex.size >= 2 && isCurrentTaprootControlBlock(withoutAnnex.last()) ->
                withoutAnnex.dropLast(2)

            else -> emptyList()
        }

        return candidates.filter { it.size == 64 || it.size == 65 }
    }

    private fun isCurrentTaprootControlBlock(value: ByteArray): Boolean =
        value.size >= 33 && (value.size - 33) % 32 == 0 &&
            ((value[0].toInt() and 0xfe) == 0xc0)

    private fun requireAllSighash(flag: Byte, context: String) {
        val sighash = flag.toInt() and 0xff
        if (sighash != 0x01) {
            throw SecurityException(
                "$context uses disallowed sighash 0x${sighash.toString(16)}; " +
                    "raw broadcasts may use only SIGHASH_ALL or Taproot SIGHASH_DEFAULT"
            )
        }
    }

    /** Bitcoin Core's strict DER checks, with the final sighash byte included. */
    private fun isStrictDerSignatureWithHashType(signature: ByteArray): Boolean {
        if (signature.size !in 9..73) return false
        if ((signature[0].toInt() and 0xff) != 0x30) return false
        if ((signature[1].toInt() and 0xff) != signature.size - 3) return false

        val lenR = signature[3].toInt() and 0xff
        if (5 + lenR >= signature.size) return false
        val lenS = signature[5 + lenR].toInt() and 0xff
        if (lenR + lenS + 7 != signature.size) return false
        if ((signature[2].toInt() and 0xff) != 0x02 || lenR == 0) return false
        if ((signature[4].toInt() and 0x80) != 0) return false
        if (lenR > 1 && signature[4] == 0.toByte() && (signature[5].toInt() and 0x80) == 0) return false

        val sTypeIndex = lenR + 4
        val sValueIndex = lenR + 6
        if ((signature[sTypeIndex].toInt() and 0xff) != 0x02 || lenS == 0) return false
        if ((signature[sValueIndex].toInt() and 0x80) != 0) return false
        if (
            lenS > 1 && signature[sValueIndex] == 0.toByte() &&
            (signature[sValueIndex + 1].toInt() and 0x80) == 0
        ) return false
        return true
    }

    private fun parseScriptPushes(script: ByteArray, context: String): List<ByteArray> {
        val pushes = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < script.size) {
            val opcode = script[offset++].toInt() and 0xff
            val length = when (opcode) {
                in 0x01..0x4b -> opcode.toLong()
                0x4c -> readScriptLength(script, offset, 1, context).also { offset += 1 }
                0x4d -> readScriptLength(script, offset, 2, context).also { offset += 2 }
                0x4e -> readScriptLength(script, offset, 4, context).also { offset += 4 }
                else -> continue
            }
            if (length !in 0..Int.MAX_VALUE.toLong() || offset > script.size - length.toInt()) {
                throw SecurityException("$context contains a truncated script push")
            }
            pushes += script.copyOfRange(offset, offset + length.toInt())
            offset += length.toInt()
        }
        return pushes
    }

    private fun readScriptLength(
        script: ByteArray,
        offset: Int,
        bytes: Int,
        context: String
    ): Long {
        if (offset > script.size - bytes) {
            throw SecurityException("$context contains a truncated PUSHDATA length")
        }
        var value = 0L
        repeat(bytes) { index ->
            value = value or ((script[offset + index].toLong() and 0xffL) shl (index * 8))
        }
        return value
    }

    private class Cursor(private val bytes: ByteArray) {
        private var offset = 0

        fun atEnd(): Boolean = offset == bytes.size

        fun peekByte(label: String): Int {
            if (offset >= bytes.size) throw truncated(label)
            return bytes[offset].toInt() and 0xff
        }

        fun readByte(label: String): Int {
            val value = peekByte(label)
            offset++
            return value
        }

        fun skip(count: Int, label: String) {
            if (count < 0 || offset > bytes.size - count) throw truncated(label)
            offset += count
        }

        fun skipLength(count: Long, label: String) {
            checkedLength(count, label).also { skip(it, label) }
        }

        fun readBytes(count: Long, label: String): ByteArray {
            val size = checkedLength(count, label)
            if (offset > bytes.size - size) throw truncated(label)
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }

        fun readCompactSize(label: String): Long {
            val prefix = readByte(label)
            val value = when (prefix) {
                in 0x00..0xfc -> prefix.toLong()
                0xfd -> readLittleEndian(2, label).also {
                    if (it < 0xfd) throw SecurityException("Raw transaction has non-canonical $label")
                }
                0xfe -> readLittleEndian(4, label).also {
                    if (it <= 0xffff) throw SecurityException("Raw transaction has non-canonical $label")
                }
                else -> readLittleEndian(8, label).also {
                    if (it <= 0xffff_ffffL) {
                        throw SecurityException("Raw transaction has non-canonical $label")
                    }
                }
            }
            return value
        }

        private fun readLittleEndian(count: Int, label: String): Long {
            var value = 0L
            repeat(count) { index ->
                val byte = readByte(label)
                if (index == 7 && (byte and 0x80) != 0) {
                    throw SecurityException("Raw transaction $label is too large")
                }
                value = value or (byte.toLong() shl (index * 8))
            }
            return value
        }

        private fun checkedLength(count: Long, label: String): Int {
            if (count !in 0..Int.MAX_VALUE.toLong()) {
                throw SecurityException("Raw transaction $label is too large")
            }
            return count.toInt()
        }

        private fun truncated(label: String) =
            SecurityException("Raw transaction contains truncated $label")
    }

    private const val MAX_VECTOR_ITEMS = 100_000L
}
