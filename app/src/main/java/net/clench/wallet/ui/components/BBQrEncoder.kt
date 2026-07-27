package net.clench.wallet.ui.components

import net.clench.wallet.security.InputLimits
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
    internal const val MAX_FRAMES = 1295
    internal const val MAX_DECODED_BYTES = 6 * 1024 * 1024

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
     */
    fun encodePsbt(
        psbtBytes: ByteArray,
        maxChunkChars: Int = 800
    ): List<String> {
        require(psbtBytes.isNotEmpty()) { "PSBT is empty" }
        require(psbtBytes.size <= InputLimits.PSBT_BYTES) {
            "PSBT exceeds the BBQr binary safety limit"
        }
        val rawBase32Data = base32Encode(psbtBytes)
        val encoding = '2'
        val encodedData = rawBase32Data
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
        require(rawFrames <= MAX_FRAMES) {
            "PSBT too large for BBQr: requires $rawFrames frames (max $MAX_FRAMES). " +
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
        if (encoding !in setOf(ENCODING_ZLIB_BASE32, ENCODING_HEX, '2')) return null
        if (fileType !in 'A'..'Z') return null
        if (totalFrames !in 1..MAX_FRAMES || frameIndex !in 0 until totalFrames) return null
        if (data.isEmpty()) return null
        if (encoding == ENCODING_HEX && data.any { it.digitToIntOrNull(16) == null }) return null
        if (encoding != ENCODING_HEX && data.any { it.code >= BASE32_DECODE.size || BASE32_DECODE[it.code] < 0 }) return null
        if (encoding == ENCODING_HEX && data.length % 2 != 0) return null
        if (frameIndex < totalFrames - 1) {
            val splitMod = if (encoding == ENCODING_HEX) 2 else 8
            if (data.length % splitMod != 0) return null
        }

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
        require(orderedChunks.isNotEmpty()) { "BBQr payload has no frames" }
        require(orderedChunks.size <= MAX_FRAMES) { "BBQr payload contains too many frames" }
        var encodedLength = 0
        orderedChunks.forEachIndexed { index, chunk ->
            require(chunk.isNotEmpty()) { "BBQr frame $index is empty" }
            require(encodedLength <= MAX_DECODED_BYTES * 2 - chunk.length) {
                "BBQr payload exceeds the encoded safety limit"
            }
            encodedLength += chunk.length
        }
        val combined = orderedChunks.joinToString("")
        return when (encoding) {
            ENCODING_ZLIB_BASE32 -> {
                val compressed = base32Decode(combined)
                require(base32Encode(compressed) == combined) { "BBQr Base32 data is non-canonical" }
                zlibDecompress(compressed, MAX_DECODED_BYTES)
            }
            ENCODING_HEX -> {
                hexDecode(combined).also {
                    require(it.size <= MAX_DECODED_BYTES) { "BBQr payload exceeds the decoded safety limit" }
                }
            }
            '2' -> {
                // Base32, uncompressed
                base32Decode(combined).also {
                    require(base32Encode(it) == combined) { "BBQr Base32 data is non-canonical" }
                    require(it.size <= MAX_DECODED_BYTES) { "BBQr payload exceeds the decoded safety limit" }
                }
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
        require(hex.length % 2 == 0) { "BBQr hex payload must contain complete bytes" }
        val len = hex.length / 2
        val result = ByteArray(len)
        for (i in 0 until len) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun zlibDecompress(data: ByteArray, maxOutputBytes: Int): ByteArray {
        // BBQr uses raw DEFLATE (Python zlib wbits=-10), not a zlib-wrapped stream.
        require(data.isNotEmpty()) { "BBQr compressed payload is empty" }
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val output = ByteArray(minOf(maxOf(data.size * 4, 1_024), maxOutputBytes))
            var totalRead = 0
            var buf = output
            while (!inflater.finished()) {
                val count = inflater.inflate(buf, totalRead, buf.size - totalRead)
                if (count == 0) {
                    if (inflater.needsDictionary()) throw IllegalArgumentException("BBQr compressed payload requires a dictionary")
                    if (inflater.needsInput()) throw IllegalArgumentException("BBQr compressed payload is truncated")
                    if (totalRead == buf.size) {
                        require(buf.size < maxOutputBytes) { "BBQr payload exceeds the decoded safety limit" }
                    } else {
                        throw IllegalArgumentException("BBQr compressed payload made no progress")
                    }
                }
                totalRead += count
                if (totalRead == buf.size && !inflater.finished()) {
                    require(buf.size < maxOutputBytes) { "BBQr payload exceeds the decoded safety limit" }
                    buf = buf.copyOf(minOf(buf.size * 2, maxOutputBytes))
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

/**
 * Strict accumulator for a single BBQr stream. It rejects mixed sessions and
 * conflicting duplicate frames instead of silently replacing collected data.
 */
internal class BBQrSessionAccumulator(
    private val maxFrames: Int = BBQrEncoder.MAX_FRAMES,
    private val maxEncodedChars: Int = BBQrEncoder.MAX_DECODED_BYTES * 2
) {
    data class Progress(
        val collectedFrames: Int,
        val totalFrames: Int,
        val fileType: Char,
        val decodedPayload: ByteArray? = null
    )

    private val chunks = mutableMapOf<Int, String>()
    private var totalFrames = 0
    private var encoding = '\u0000'
    private var fileType = '\u0000'
    private var encodedChars = 0

    fun receive(frameText: String): Progress {
        val frame = requireNotNull(BBQrEncoder.parseBBQrFrame(frameText)) { "Invalid BBQr frame" }
        require(frame.totalFrames <= maxFrames) { "BBQr stream contains too many frames" }

        if (totalFrames == 0) {
            totalFrames = frame.totalFrames
            encoding = frame.encoding
            fileType = frame.fileType
        } else {
            require(
                totalFrames == frame.totalFrames &&
                    encoding == frame.encoding &&
                    fileType == frame.fileType
            ) { "BBQr frame belongs to a different stream" }
        }

        val prior = chunks[frame.frameIndex]
        require(prior == null || prior == frame.data) { "BBQr frame conflicts with an earlier frame" }
        if (prior == null) {
            require(encodedChars <= maxEncodedChars - frame.data.length) {
                "BBQr stream exceeds the encoded safety limit"
            }
            chunks[frame.frameIndex] = frame.data
            encodedChars += frame.data.length
        }

        val payload = if (chunks.size == totalFrames) {
            val ordered = (0 until totalFrames).map { index ->
                requireNotNull(chunks[index]) { "BBQr stream is missing frame $index" }
            }
            BBQrEncoder.reassemble(ordered, encoding)
        } else null

        return Progress(chunks.size, totalFrames, fileType, payload)
    }

    fun reset() {
        chunks.clear()
        totalFrames = 0
        encoding = '\u0000'
        fileType = '\u0000'
        encodedChars = 0
    }
}
