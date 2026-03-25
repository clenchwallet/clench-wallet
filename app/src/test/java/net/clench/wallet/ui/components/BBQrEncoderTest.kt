package net.clench.wallet.ui.components

import org.junit.Assert.*
import org.junit.Test

class BBQrEncoderTest {

    @Test
    fun `base32 round-trip`() {
        val data = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0x7F, 0x80.toByte())
        val encoded = BBQrEncoder.base32Encode(data)
        val decoded = BBQrEncoder.base32Decode(encoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `base32 round-trip large random data`() {
        val data = ByteArray(1024) { (it * 37).toByte() }
        val encoded = BBQrEncoder.base32Encode(data)
        val decoded = BBQrEncoder.base32Decode(encoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `single frame for small PSBT`() {
        // Simulate a small ~200 byte PSBT
        val fakePsbt = ByteArray(200) { (it % 256).toByte() }
        val frames = BBQrEncoder.encodePsbt(fakePsbt)

        assertEquals(1, frames.size)
        assertTrue(frames[0].startsWith("B\$P"))
        // Should be ZLIB or Hex
        val encoding = frames[0][3]
        assertTrue(encoding == 'Z' || encoding == 'H')
        // Total frames = 01, index = 00
        assertEquals("01", frames[0].substring(4, 6))
        assertEquals("00", frames[0].substring(6, 8))
    }

    @Test
    fun `multi frame for large PSBT`() {
        // Use random-ish data that doesn't compress well to force multiple frames
        val random = java.util.Random(42)
        val fakePsbt = ByteArray(10000).also { random.nextBytes(it) }
        val frames = BBQrEncoder.encodePsbt(fakePsbt, maxChunkChars = 800)

        assertTrue("Should have multiple frames, got ${frames.size}", frames.size > 1)
        for ((i, frame) in frames.withIndex()) {
            assertTrue(frame.startsWith("B\$P"))
            val parsed = BBQrEncoder.parseBBQrFrame(frame)
            assertNotNull(parsed)
            assertEquals(frames.size, parsed!!.totalFrames)
            assertEquals(i, parsed.frameIndex)
        }
    }

    @Test
    fun `encode-decode round-trip`() {
        val fakePsbt = ByteArray(3000) { (it * 13 + 7).toByte() }
        val frames = BBQrEncoder.encodePsbt(fakePsbt)

        // Parse all frames and reassemble
        val parsedFrames = frames.map { BBQrEncoder.parseBBQrFrame(it)!! }
        val encoding = parsedFrames[0].encoding
        val orderedChunks = parsedFrames.sortedBy { it.frameIndex }.map { it.data }
        val decoded = BBQrEncoder.reassemble(orderedChunks, encoding)

        assertArrayEquals("Round-trip should produce identical bytes", fakePsbt, decoded)
    }

    @Test
    fun `encode-decode round-trip with shuffled frames`() {
        val fakePsbt = ByteArray(5000) { (it * 7).toByte() }
        val frames = BBQrEncoder.encodePsbt(fakePsbt, maxChunkChars = 500)

        // Shuffle frames (BBQr supports out-of-order scanning)
        val shuffled = frames.shuffled()
        val parsedFrames = shuffled.map { BBQrEncoder.parseBBQrFrame(it)!! }
        val encoding = parsedFrames[0].encoding
        val orderedChunks = parsedFrames.sortedBy { it.frameIndex }.map { it.data }
        val decoded = BBQrEncoder.reassemble(orderedChunks, encoding)

        assertArrayEquals(fakePsbt, decoded)
    }

    @Test
    fun `isBBQr detection`() {
        assertTrue(BBQrEncoder.isBBQr("B\$PZ0100ABCDEF"))
        assertTrue(BBQrEncoder.isBBQr("B\$PH0100ABCDEF"))
        assertFalse(BBQrEncoder.isBBQr("ur:crypto-psbt/..."))
        assertFalse(BBQrEncoder.isBBQr("hello"))
        assertFalse(BBQrEncoder.isBBQr("B\$"))
    }

    @Test
    fun `frame header format`() {
        val frames = BBQrEncoder.encodePsbt(ByteArray(100) { 0x42 })
        val frame = frames[0]
        // B$ prefix
        assertEquals('B', frame[0])
        assertEquals('$', frame[1])
        // P for PSBT
        assertEquals('P', frame[2])
        // Z or H for encoding
        assertTrue(frame[3] == 'Z' || frame[3] == 'H')
    }
}
