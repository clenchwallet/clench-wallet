package net.clench.wallet.ui.components

import com.sparrowwallet.hummingbird.Bytewords
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.fountain.FountainEncoder
import org.junit.Assert.assertTrue
import org.junit.Test

class BcUrFramePolicyTest {

    @Test
    fun `accepts bounded encoder frame`() {
        val encoder = UREncoder(UR("bytes", ByteArray(1_024) { it.toByte() }), 100, 10, 0L)

        BcUrFramePolicy.requireSafeFrame(encoder.nextPart())
    }

    @Test
    fun `rejects oversized declared sequence before bytewords decode`() {
        val failure = runCatching {
            BcUrFramePolicy.requireSafeFrame(
                "ur:bytes/1-${BcUrFramePolicy.MAX_SEQUENCE_PARTS + 1}/not-bytewords"
            )
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("sequence exceeds") == true)
    }

    @Test
    fun `rejects oversized embedded message before fountain allocation`() {
        val part = FountainEncoder.Part(
            1L,
            1,
            BcUrFramePolicy.MAX_MESSAGE_BYTES + 1,
            0L,
            byteArrayOf(1)
        )
        val body = Bytewords.encode(part.toCborBytes(), Bytewords.Style.MINIMAL)

        val failure = runCatching {
            BcUrFramePolicy.requireSafeFrame("ur:bytes/1-1/$body")
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("message exceeds") == true)
    }

    @Test
    fun `rejects outer and embedded sequence mismatch`() {
        val part = FountainEncoder.Part(2L, 2, 2, 0L, byteArrayOf(1))
        val body = Bytewords.encode(part.toCborBytes(), Bytewords.Style.MINIMAL)

        val failure = runCatching {
            BcUrFramePolicy.requireSafeFrame("ur:bytes/1-2/$body")
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("does not match") == true)
    }

    @Test
    fun `decoder state has a hard upper bound`() {
        BcUrFramePolicy.requireStateCapacity(BcUrFramePolicy.MAX_PROCESSED_PARTS - 1)
        val failure = runCatching {
            BcUrFramePolicy.requireStateCapacity(BcUrFramePolicy.MAX_PROCESSED_PARTS)
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("state exceeds") == true)
    }
}
