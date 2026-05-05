package net.clench.wallet.ui.components

import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * BBQr encoder/decoder for Coldcard Q/Mk4 hardware wallets.
 *
 * BBQr frame format:
 *   B$ <encoding> <file_type> <total_base36_2> <index_base36_2> <data_chunk>
 *
 * File types: P=PSBT, T=Transaction
 * Encodings: Z=raw DEFLATE+Base32, H=Hex, 2=Base32
 *
 * Base32 alphabet: RFC 4648 without padding (A-Z2-7)
 *
 * @see <a href="https://github.com/coinkite/BBQr">BBQr Specification</a>
 */
object BBQrEncoder {

    private const val HEADER_PREFIX = "B\$"
    private const val FILE_TYPE_PSBT = 'P'
    private const val ENCODING_ZLIB_BASE32 = 'Z'
    private const val ENCODING_HEX = 'H'
    private const val HEADER_LEN = 8 // "B$" + encoding + fileType + 2-char total(base36) + 2-char index(base36)

    // Base36 digits for total/index fields
    private const val BASE36_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray()

    // Reverse lookup for decoding
    private val BASE32_DECODE = IntArray(128) { -1 }.also { arr ->
        BASE32_ALPHABET.forEachIndexed { i, c -> arr[c.code] = i }
    }

    /**
     * Encode PSBT bytes into BBQr frames for Coldcard Q.
     * Returns a list of frame strings, each ready to be rendered as a QR code.
     *
     * Clench intentionally emits uncompressed Base32 (encoding '2') by default.
     * BBQr Z compression requires raw DEFLATE with a fixed 10-bit window; Java's
     * Deflater does not expose that window-size control, and Coldcard Q may scan
     * the frame header but reject/fail to finish a non-conforming Z payload.
     *
     * @param psbtBytes Raw PSBT bytes
     * @param maxChunkChars Max data chars per frame (excluding 8-char header). Default 800.
     * @param useCompression Only true for callers that can guarantee BBQr-compatible wbits=10 output.
     */
    fun encodePsbt(
        psbtBytes: ByteArray,
        maxChunkChars: Int = 800,
        useCompression: Boolean = false
    ): List<String> {
        val rawBase32Data = base32Encode(psbtBytes)
        val compressed = if (useCompression) zlibCompress(psbtBytes) else ByteArray(0)
        val compressedBase32Data = if (useCompression) base32Encode(compressed) else ""

        val useZlib = useCompression && compressed.size < psbtBytes.size
        val encoding = if (useZlib) ENCODING_ZLIB_BASE32 else '2'
        val encodedData = if (useZlib) compressedBase32Data else rawBase32Data
        // BBQr header format: B$ + encoding + filetype + total(base36,2) + index(base36,2)

        // Single-frame optimization: if it all fits in one QR (≤2500 chars total)
        val singleFrameLimit = 2500 - HEADER_LEN
        if (encodedData.length <= singleFrameLimit) {
            val header = "${HEADER_PREFIX}${encoding}${FILE_TYPE_PSBT}${toBase36(1)}${toBase36(0)}"
            return listOf(header + encodedData)
        }

        // Split into chunks. BBQr reference decoder decodes each chunk separately,
        // so non-final Base32 chunks must be split on 8-character boundaries and
        // hex chunks on 2-character boundaries.
        val splitMod = if (encoding == ENCODING_HEX) 2 else 8
        val chunkCapacity = maxChunkChars - (maxChunkChars % splitMod)
        require(chunkCapacity > 0) { "maxChunkChars must be at least $splitMod for BBQr encoding $encoding" }
        val rawFrames = (encodedData.length + chunkCapacity - 1) / chunkCapacity

        // Fix 3: Replace silent coerceIn with a descriptive error if frames exceed max base36 (ZZ = 1295)
        require(rawFrames <= 1295) {
            "PSBT too large for BBQr: requires $rawFrames frames (max 1295). " +
            "Try reducing the number of inputs or use SD card transfer for Coldcard Mk4."
        }
        val totalFrames = rawFrames

        val frames = mutableListOf<String>()
        for (i in 0 until totalFrames) {
            val start = i * chunkCapacity
            val end = minOf(start + chunkCapacity, encodedData.length)
            if (start >= encodedData.length) break
            val chunk = encodedData.substring(start, end)
            val header = "${HEADER_PREFIX}${encoding}${FILE_TYPE_PSBT}${toBase36(totalFrames)}${toBase36(i)}"
            frames.add(header + chunk)
        }
        return frames
    }

    /**
     * Encode an integer as 2-digit base36 (00-ZZ).
     */
    private fun toBase36(value: Int): String {
        val high = value / 36
        val low = value % 36
        return "${BASE36_CHARS[high]}${BASE36_CHARS[low]}"
    }

    /**
     * Decode 2-digit base36 to integer.
     */
    private fun fromBase36(s: String): Int? {
        if (s.length != 2) return null
        val high = BASE36_CHARS.indexOf(s[0].uppercaseChar())
        val low = BASE36_CHARS.indexOf(s[1].uppercaseChar())
        if (high < 0 || low < 0) return null
        return high * 36 + low
    }

    /**
     * Parse a single BBQr frame header.
     * Returns null if the string is not a valid BBQr frame.
     * Header: B$ + encoding + fileType + total(base36,2) + index(base36,2)
     */
    fun parseBBQrFrame(frame: String): BBQrFrame? {
        if (frame.length < HEADER_LEN) return null
        if (!frame.startsWith(HEADER_PREFIX)) return null

        val encoding = frame[2]
        val fileType = frame[3]
        val totalFrames = fromBase36(frame.substring(4, 6)) ?: return null
        val frameIndex = fromBase36(frame.substring(6, 8)) ?: return null
        val data = frame.substring(8)

        return BBQrFrame(fileType, encoding, totalFrames, frameIndex, data)
    }

    /**
     * Reassemble collected BBQr frame data chunks into the original payload.
     * Frames must be provided in order (sorted by index).
     *
     * @param orderedChunks Data chunks in frame-index order
     * @param encoding The encoding character from the header ('Z', 'H', or '2')
     * @return Decoded raw bytes
     */
    fun reassemble(orderedChunks: List<String>, encoding: Char): ByteArray {
        val combined = orderedChunks.joinToString("")
        return when (encoding) {
            ENCODING_ZLIB_BASE32 -> {
                val compressed = base32Decode(combined)
                zlibDecompress(compressed)
            }
            ENCODING_HEX -> {
                hexDecode(combined)
            }
            '2' -> {
                // Base32, uncompressed
                base32Decode(combined)
            }
            else -> throw IllegalArgumentException("Unknown BBQr encoding: $encoding")
        }
    }

    /**
     * Check if a QR frame string is a BBQr frame.
     */
    fun isBBQr(text: String): Boolean = text.length >= HEADER_LEN && text.startsWith(HEADER_PREFIX)

    // --- Internal encoding/decoding ---

    internal fun base32Encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(BASE32_ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    internal fun base32Decode(encoded: String): ByteArray {
        if (encoded.isEmpty()) return ByteArray(0)
        val output = ByteArray(encoded.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var outIndex = 0
        for (c in encoded) {
            val value = if (c.code < 128) BASE32_DECODE[c.code] else -1
            if (value < 0) throw IllegalArgumentException("Invalid BBQr base32 character: $c")
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output[outIndex++] = ((buffer shr bitsLeft) and 0xFF).toByte()
            }
        }
        return if (outIndex == output.size) output else output.copyOf(outIndex)
    }

    private fun hexDecode(hex: String): ByteArray {
        val len = hex.length / 2
        val result = ByteArray(len)
        for (i in 0 until len) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun zlibCompress(data: ByteArray): ByteArray {
        // BBQr uses raw DEFLATE (Python zlib wbits=-10), not a zlib-wrapped stream.
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val buffer = ByteArray(data.size + 256)
            val compressedSize = deflater.deflate(buffer)
            return buffer.copyOf(compressedSize)
        } finally {
            deflater.end()
        }
    }

    private fun zlibDecompress(data: ByteArray): ByteArray {
        // BBQr uses raw DEFLATE (Python zlib wbits=-10), not a zlib-wrapped stream.
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val output = ByteArray(data.size * 4) // initial estimate
            var totalRead = 0
            var buf = output
            while (!inflater.finished()) {
                val count = inflater.inflate(buf, totalRead, buf.size - totalRead)
                if (count == 0 && inflater.needsInput()) break
                totalRead += count
                if (totalRead == buf.size && !inflater.finished()) {
                    buf = buf.copyOf(buf.size * 2)
                }
            }
            return buf.copyOf(totalRead)
        } finally {
            inflater.end()
        }
    }

    data class BBQrFrame(
        val fileType: Char,
        val encoding: Char,
        val totalFrames: Int,
        val frameIndex: Int,
        val data: String
    )
}
