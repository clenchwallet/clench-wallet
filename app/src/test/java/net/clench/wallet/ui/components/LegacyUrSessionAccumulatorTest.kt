package net.clench.wallet.ui.components

import com.sparrowwallet.hummingbird.LegacyUREncoder
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.RegistryType
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyUrSessionAccumulatorTest {

    @Test
    fun `legacy UR bytes PSBT follows scanner handoff to base64 out of order`() {
        val psbt = validPsbt()
        val frames = LegacyUREncoder(
            UR.fromBytes(RegistryType.BYTES.toString(), psbt),
            20
        ).encode()
        val accumulator = LegacyUrSessionAccumulator()

        assertTrue(frames.size > 1)
        var scannerResult: String? = null
        frames.reversed().forEach { frame ->
            accumulator.receive(frame).decodedUr?.let { ur ->
                scannerResult = HardwareWalletQrPayloadDecoder.decodeUrPayload(ur)
            }
        }

        assertEquals(Base64.getEncoder().encodeToString(psbt), scannerResult)
    }

    @Test
    fun `reassembles bounded legacy UR frames out of order`() {
        val expected = UR("bytes", ByteArray(512) { it.toByte() })
        val frames = LegacyUREncoder(expected, 40).encode()
        val accumulator = LegacyUrSessionAccumulator()

        assertTrue(frames.size > 1)
        var decoded: UR? = null
        frames.reversed().forEach { frame ->
            decoded = accumulator.receive(frame).decodedUr ?: decoded
        }

        assertNotNull(decoded)
        assertArrayEquals(expected.cborBytes, decoded!!.cborBytes)
    }

    @Test
    fun `accepts duplicate frame but rejects conflicting replacement`() {
        val frames = LegacyUREncoder(UR("bytes", ByteArray(256) { it.toByte() }), 40).encode()
        val accumulator = LegacyUrSessionAccumulator()

        val first = accumulator.receive(frames.first())
        val duplicate = accumulator.receive(frames.first())
        assertEquals(first.collectedFrames, duplicate.collectedFrames)

        val replacement = frames.first().dropLast(1) +
            if (frames.first().last() == 'q') "p" else "q"
        val failure = runCatching { accumulator.receive(replacement) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("conflicts") == true)
    }

    @Test
    fun `rejects mixed legacy UR streams`() {
        val first = LegacyUREncoder(UR("bytes", ByteArray(256) { 1 }), 40).encode()
        val second = LegacyUREncoder(UR("bytes", ByteArray(256) { 2 }), 40).encode()
        val accumulator = LegacyUrSessionAccumulator()

        accumulator.receive(first.first())
        val failure = runCatching { accumulator.receive(second.first()) }.exceptionOrNull()

        assertTrue(failure?.message?.contains("different stream") == true)
    }

    @Test
    fun `rejects oversized legacy UR sequence before decoder state grows`() {
        val frame = LegacyUREncoder(UR("bytes", ByteArray(256)), 40).encode().first()
        val oversized = frame.replaceFirst(
            Regex("/\\d+of\\d+/"),
            "/1of${BcUrFramePolicy.MAX_SEQUENCE_PARTS + 1}/"
        )

        val failure = runCatching {
            LegacyUrSessionAccumulator().receive(oversized)
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("sequence exceeds") == true)
    }

    private fun validPsbt(): ByteArray {
        val transaction = hexToBytes(
            "01000000" +
                "01" +
                "00".repeat(32) +
                "ffffffff" +
                "00" +
                "ffffffff" +
                "01" +
                "0000000000000000" +
                "00" +
                "00000000"
        )
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
            write(1) // global key length
            write(0) // unsigned transaction key type
            write(transaction.size)
            write(transaction)
            write(0) // global separator
            write(0) // input-map separator
            write(0) // output-map separator
        }.toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
