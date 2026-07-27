package net.clench.wallet.ui.components

import kotlin.random.Random as KotlinRandom
import net.clench.wallet.verification.VerificationPropertyHarness
import net.clench.wallet.verification.VerificationPropertyHarness.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BBQrHostilePropertyTest {
    @Test
    fun `random binary payloads survive shuffled BBQr transport`() {
        VerificationPropertyHarness.forAll(seed = 0x42425152L, cases = 256) { random, caseIndex ->
            val payload = random.bytes(random.nextInt(16_384) + 1)
            val chunkSize = listOf(64, 128, 256, 800)[random.nextInt(4)]
            val frames = BBQrEncoder.encodePsbt(payload, maxChunkChars = chunkSize)
                .shuffled(KotlinRandom(caseIndex))
            val accumulator = BBQrSessionAccumulator()
            var decoded: ByteArray? = null

            frames.forEach { frame ->
                decoded = accumulator.receive(frame).decodedPayload ?: decoded
            }

            assertArrayEquals(payload, decoded)
        }
    }

    @Test
    fun `conflicting duplicate BBQr frames fail closed`() {
        val frames = BBQrEncoder.encodePsbt(ByteArray(8_000) { it.toByte() }, maxChunkChars = 128)
        val accumulator = BBQrSessionAccumulator()
        accumulator.receive(frames.first())
        val conflicting = frames.first().dropLast(1) +
            if (frames.first().last() == 'A') "B" else "A"

        assertThrows(IllegalArgumentException::class.java) {
            accumulator.receive(conflicting)
        }
    }

    @Test
    fun `non spec indexes encodings and malformed chunks are rejected`() {
        assertNull(BBQrEncoder.parseBBQrFrame("B\$2P0101NBSWY3DP"))
        assertNull(BBQrEncoder.parseBBQrFrame("B\$XP0100NBSWY3DP"))
        assertNull(BBQrEncoder.parseBBQrFrame("B\$2P0200ABC"))
        assertNull(BBQrEncoder.parseBBQrFrame("B\$HP0100ABC"))
    }

    @Test
    fun `direct reassembly rejects excessive frame count before concatenation`() {
        val excessive = List(BBQrEncoder.MAX_FRAMES + 1) { "AA" }

        assertThrows(IllegalArgumentException::class.java) {
            BBQrEncoder.reassemble(excessive, 'H')
        }
    }

    @Test
    fun `arbitrary QR text never causes fatal BBQr parser failures`() {
        VerificationPropertyHarness.forAll(seed = 0x515246555A5AL) { random, _ ->
            val text = buildString(random.nextInt(4_096)) {
                repeat(random.nextInt(4_096)) {
                    append((random.nextInt(95) + 32).toChar())
                }
            }
            VerificationPropertyHarness.assertNoFatalParserFailure {
                BBQrEncoder.parseBBQrFrame(text)?.let {
                    BBQrSessionAccumulator().receive(text)
                }
            }
        }
    }
}
