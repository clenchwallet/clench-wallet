package net.clench.wallet.ui.components

import com.sparrowwallet.hummingbird.Bytewords
import com.sparrowwallet.hummingbird.fountain.FountainEncoder

/** Bounds hostile BC-UR metadata before Hummingbird allocates fountain-decoder state. */
internal object BcUrFramePolicy {
    const val MAX_SEQUENCE_PARTS = 2_048
    const val MAX_PROCESSED_PARTS = 8_192
    const val MAX_MESSAGE_BYTES = 6 * 1024 * 1024
    const val MAX_FRAME_CHARS = 64 * 1024
    private val SEQUENCE_COMPONENT = Regex("^(\\d+)-(\\d+)$")

    fun requireSafeFrame(frame: String) {
        require(frame.length <= MAX_FRAME_CHARS) { "BC-UR frame exceeds safety limit" }
        val components = frame.lowercase().removePrefix("ur:").split('/')
        require(components.size == 2 || components.size == 3) { "Invalid BC-UR frame path" }
        if (components.size == 2) return // Single-part UR; frame-size bound applies.

        val sequence = SEQUENCE_COMPONENT.matchEntire(components[1])
            ?: throw IllegalArgumentException("Invalid BC-UR sequence metadata")
        val outerSeqNum = sequence.groupValues[1].toLongOrNull()
            ?: throw IllegalArgumentException("Invalid BC-UR sequence number")
        val outerSeqLen = sequence.groupValues[2].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid BC-UR sequence length")
        require(outerSeqNum >= 1L) { "Invalid BC-UR sequence number" }
        require(outerSeqLen in 1..MAX_SEQUENCE_PARTS) { "BC-UR sequence exceeds safety limit" }

        // The textual body is bounded before Bytewords decoding, and the CBOR part is decoded
        // here before FountainDecoder.receivePart() can allocate a seqLen-sized index set.
        val cbor = Bytewords.decode(components[2], Bytewords.Style.MINIMAL)
        require(cbor.size <= MAX_FRAME_CHARS / 2) { "BC-UR frame body exceeds safety limit" }
        val part = FountainEncoder.Part.fromCborBytes(cbor)
        require(part.seqNum == outerSeqNum && part.seqLen == outerSeqLen) {
            "BC-UR sequence metadata does not match frame body"
        }
        require(part.messageLen in 1..MAX_MESSAGE_BYTES) { "BC-UR message exceeds safety limit" }
        require(part.data.isNotEmpty() && part.data.size <= MAX_FRAME_CHARS / 2) {
            "BC-UR fragment exceeds safety limit"
        }
        val paddedMessageBytes = part.data.size.toLong() * part.seqLen.toLong()
        require(part.messageLen.toLong() <= paddedMessageBytes) { "Invalid BC-UR message length" }
        require(paddedMessageBytes <= MAX_MESSAGE_BYTES.toLong() + MAX_SEQUENCE_PARTS) {
            "BC-UR padded message exceeds safety limit"
        }
    }

    fun requireStateCapacity(processedParts: Int) {
        require(processedParts in 0 until MAX_PROCESSED_PARTS) {
            "BC-UR decoder state exceeds safety limit"
        }
    }
}
