package net.clench.wallet.security

import java.util.Base64

/**
 * Performs a bounded structural pass over a PSBT before handing it to BDK.
 *
 * BDK remains responsible for semantic PSBT and transaction validation. This
 * preflight rejects malformed framing, non-canonical CompactSize values,
 * duplicate keys, truncation, and oversized inputs at QR/NFC/file boundaries.
 */
object PsbtSafety {
    private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
    private const val MAX_BASE64_CHARS = 6 * 1024 * 1024
    private const val MAX_MAPS = 100_000
    private const val MAX_FIELDS = 100_000
    private const val MAX_KEY_BYTES = 128 * 1024

    data class Inspection(
        val byteCount: Int,
        val mapCount: Int,
        val fieldCount: Int
    )

    fun inspectBase64(encoded: String): Inspection {
        require(encoded.length <= MAX_BASE64_CHARS) { "PSBT exceeds the encoded safety limit" }
        val normalized = buildString(encoded.length) {
            encoded.forEach { character ->
                if (!character.isWhitespace()) append(character)
            }
        }
        require(normalized.isNotEmpty()) { "PSBT is empty" }
        val bytes = try {
            Base64.getDecoder().decode(normalized)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("PSBT is not valid Base64", e)
        }
        require(bytes.size <= InputLimits.PSBT_BYTES) { "PSBT exceeds the binary safety limit" }
        return inspectBytes(bytes)
    }

    fun inspectBytes(bytes: ByteArray): Inspection {
        require(bytes.size >= MAGIC.size + 2) { "PSBT is truncated" }
        require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "PSBT magic bytes are invalid"
        }

        val cursor = Cursor(bytes, MAGIC.size)
        var mapCount = 0
        var fieldCount = 0

        while (!cursor.atEnd()) {
            require(++mapCount <= MAX_MAPS) { "PSBT contains too many maps" }
            val keys = HashSet<ByteArrayKey>()
            var fieldsInMap = 0

            while (true) {
                val keyLength = cursor.readCompactSize("key")
                if (keyLength == 0L) break
                require(keyLength <= MAX_KEY_BYTES) { "PSBT key exceeds the safety limit" }
                require(++fieldCount <= MAX_FIELDS) { "PSBT contains too many fields" }
                fieldsInMap++

                val key = cursor.readBytes(keyLength, "key")
                require(keys.add(ByteArrayKey(key))) { "PSBT map contains a duplicate key" }

                val valueLength = cursor.readCompactSize("value")
                require(valueLength <= InputLimits.PSBT_BYTES) { "PSBT value exceeds the safety limit" }
                cursor.skip(valueLength, "value")
            }

            require(mapCount != 1 || fieldsInMap > 0) { "PSBT global map is empty" }
        }

        require(mapCount > 0) { "PSBT contains no maps" }
        return Inspection(bytes.size, mapCount, fieldCount)
    }

    private class Cursor(
        private val bytes: ByteArray,
        private var offset: Int
    ) {
        fun atEnd(): Boolean = offset == bytes.size

        fun readCompactSize(label: String): Long {
            val first = readUnsignedByte("$label length")
            return when (first) {
                in 0..252 -> first.toLong()
                253 -> {
                    val value = readLittleEndian(2, "$label length")
                    require(value >= 253) { "PSBT uses a non-canonical CompactSize for $label" }
                    value
                }
                254 -> {
                    val value = readLittleEndian(4, "$label length")
                    require(value > 0xffffL) { "PSBT uses a non-canonical CompactSize for $label" }
                    value
                }
                else -> {
                    val value = readLittleEndian(8, "$label length")
                    require(value > 0xffffffffL) { "PSBT uses a non-canonical CompactSize for $label" }
                    value
                }
            }
        }

        fun readBytes(length: Long, label: String): ByteArray {
            val intLength = checkedLength(length, label)
            require(offset <= bytes.size - intLength) { "PSBT is truncated while reading $label" }
            return bytes.copyOfRange(offset, offset + intLength).also { offset += intLength }
        }

        fun skip(length: Long, label: String) {
            val intLength = checkedLength(length, label)
            require(offset <= bytes.size - intLength) { "PSBT is truncated while reading $label" }
            offset += intLength
        }

        private fun readUnsignedByte(label: String): Int {
            require(offset < bytes.size) { "PSBT is truncated while reading $label" }
            return bytes[offset++].toInt() and 0xff
        }

        private fun readLittleEndian(byteCount: Int, label: String): Long {
            require(offset <= bytes.size - byteCount) { "PSBT is truncated while reading $label" }
            var value = 0L
            for (index in 0 until byteCount) {
                val part = bytes[offset++].toLong() and 0xffL
                if (index == 7) {
                    require(part and 0x80L == 0L) { "PSBT $label exceeds the supported size" }
                }
                value = value or (part shl (index * 8))
            }
            return value
        }

        private fun checkedLength(length: Long, label: String): Int {
            require(length in 0..Int.MAX_VALUE.toLong()) { "PSBT $label length is unsupported" }
            return length.toInt()
        }
    }

    private class ByteArrayKey(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is ByteArrayKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}
