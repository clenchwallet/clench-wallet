package net.clench.wallet.ui.components

import com.sparrowwallet.hummingbird.LegacyURDecoder
import com.sparrowwallet.hummingbird.UR

/**
 * Bounded, single-stream accumulator for legacy UR v1 frames.
 *
 * Hummingbird's legacy decoder stores every distinct frame and does not expose
 * stream bounds. This wrapper validates the sequence before handing it a frame,
 * rejects mixed/conflicting streams, and caps both frame count and encoded size.
 */
internal class LegacyUrSessionAccumulator(
    private val maxFrames: Int = BcUrFramePolicy.MAX_SEQUENCE_PARTS,
    private val maxEncodedChars: Int = BcUrFramePolicy.MAX_MESSAGE_BYTES * 2
) {
    data class Progress(
        val collectedFrames: Int,
        val totalFrames: Int,
        val decodedUr: UR? = null
    )

    private data class Frame(
        val type: String,
        val index: Int,
        val total: Int,
        val checksum: String?,
        val body: String
    )

    private val frames = mutableMapOf<Int, String>()
    private var decoder = LegacyURDecoder()
    private var type: String? = null
    private var checksum: String? = null
    private var totalFrames = 0
    private var encodedChars = 0

    fun receive(frameText: String): Progress {
        val normalized = frameText.trim().lowercase()
        val frame = parseFrame(normalized)
        require(frame.total <= maxFrames) { "Legacy UR stream contains too many frames" }

        if (totalFrames == 0) {
            type = frame.type
            checksum = frame.checksum
            totalFrames = frame.total
        } else {
            require(
                type == frame.type &&
                    checksum == frame.checksum &&
                    totalFrames == frame.total
            ) { "Legacy UR frame belongs to a different stream" }
        }

        val prior = frames[frame.index]
        require(prior == null || prior == normalized) {
            "Legacy UR frame conflicts with an earlier frame"
        }
        if (prior == null) {
            require(encodedChars <= maxEncodedChars - frame.body.length) {
                "Legacy UR stream exceeds the encoded safety limit"
            }
            frames[frame.index] = normalized
            encodedChars += frame.body.length
            decoder.receivePart(normalized)
        }

        val decoded = if (frames.size == totalFrames && decoder.isComplete) {
            decoder.decode().also { ur ->
                require(ur.cborBytes.size <= BcUrFramePolicy.MAX_MESSAGE_BYTES) {
                    "Legacy UR message exceeds the decoded safety limit"
                }
            }
        } else null

        return Progress(frames.size, totalFrames, decoded)
    }

    fun reset() {
        frames.clear()
        decoder = LegacyURDecoder()
        type = null
        checksum = null
        totalFrames = 0
        encodedChars = 0
    }

    private fun parseFrame(frame: String): Frame {
        require(frame.length <= BcUrFramePolicy.MAX_FRAME_CHARS) {
            "Legacy UR frame exceeds safety limit"
        }
        val components = frame.split('/')
        require(components.size in 2..4) { "Invalid legacy UR frame path" }
        require(TYPE.matches(components[0])) { "Invalid legacy UR type" }

        val type = components[0].removePrefix("ur:")
        val sequence = if (components.size == 4) {
            SEQUENCE.matchEntire(components[1])
                ?: throw IllegalArgumentException("Invalid legacy UR sequence metadata")
        } else null
        val index = sequence?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val total = sequence?.groupValues?.get(2)?.toIntOrNull() ?: 1
        require(total in 1..maxFrames && index in 1..total) {
            "Legacy UR sequence exceeds safety limit"
        }

        val checksum = when (components.size) {
            3 -> components[1]
            4 -> components[2]
            else -> null
        }
        checksum?.let {
            require(it.length in 1..128 && BC32_TEXT.matches(it)) {
                "Invalid legacy UR checksum"
            }
        }
        val body = components.last()
        require(body.isNotEmpty() && BC32_TEXT.matches(body)) {
            "Invalid legacy UR frame body"
        }
        return Frame(type, index, total, checksum, body)
    }

    private companion object {
        val TYPE = Regex("^ur:[a-z0-9-]{1,64}$")
        val SEQUENCE = Regex("^(\\d+)of(\\d+)$")
        val BC32_TEXT = Regex("^[a-z0-9]+$")
    }
}
