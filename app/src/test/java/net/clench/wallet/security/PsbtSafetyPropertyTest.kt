package net.clench.wallet.security

import java.io.ByteArrayOutputStream
import java.util.Base64
import net.clench.wallet.verification.VerificationPropertyHarness
import net.clench.wallet.verification.VerificationPropertyHarness.bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PsbtSafetyPropertyTest {
    @Test
    fun `generated PSBT map envelopes round trip through the structural preflight`() {
        VerificationPropertyHarness.forAll(seed = 0x50534254L) { random, _ ->
            val extraMaps = random.nextInt(6)
            val maps = buildList {
                add(listOf(byteArrayOf(0x00) to random.bytes(random.nextInt(256) + 1)))
                repeat(extraMaps) { mapIndex ->
                    add(
                        List(random.nextInt(5)) { fieldIndex ->
                            byteArrayOf((mapIndex + 1).toByte(), fieldIndex.toByte()) to
                                random.bytes(random.nextInt(128))
                        }
                    )
                }
            }
            val fixture = psbt(maps)
            val inspected = PsbtSafety.inspectBytes(fixture)
            val base64WithWhitespace = Base64.getEncoder().encodeToString(fixture).chunked(37).joinToString("\n")

            assertEquals(maps.size, inspected.mapCount)
            assertEquals(maps.sumOf { it.size }, inspected.fieldCount)
            assertEquals(inspected, PsbtSafety.inspectBase64(base64WithWhitespace))
        }
    }

    @Test
    fun `duplicate keys in the same PSBT map are rejected`() {
        val duplicate = psbt(
            listOf(
                listOf(
                    byteArrayOf(0x00) to byteArrayOf(0x01),
                    byteArrayOf(0x00) to byteArrayOf(0x02)
                )
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            PsbtSafety.inspectBytes(duplicate)
        }
    }

    @Test
    fun `non canonical and truncated CompactSize fields are rejected`() {
        val nonCanonical = PSBT_MAGIC + byteArrayOf(
            0xfd.toByte(), 0x01, 0x00,
            0x00,
            0x00,
            0x00
        )
        val truncatedValue = PSBT_MAGIC + byteArrayOf(
            0x01, 0x00,
            0x05, 0x01, 0x02,
            0x00
        )

        assertThrows(IllegalArgumentException::class.java) {
            PsbtSafety.inspectBytes(nonCanonical)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PsbtSafety.inspectBytes(truncatedValue)
        }
    }

    @Test
    fun `mutated PSBT envelopes never trigger fatal parser failures`() {
        val baseline = psbt(
            listOf(
                listOf(byteArrayOf(0x00) to ByteArray(128) { it.toByte() }),
                listOf(byteArrayOf(0x01, 0x02) to ByteArray(72) { (it * 3).toByte() }),
                emptyList()
            )
        )

        VerificationPropertyHarness.forAll(seed = 0xF0227L) { random, _ ->
            val candidate = baseline.copyOf()
            repeat(random.nextInt(8) + 1) {
                val index = random.nextInt(candidate.size)
                candidate[index] = (candidate[index].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
            VerificationPropertyHarness.assertNoFatalParserFailure {
                PsbtSafety.inspectBytes(candidate)
            }
        }
    }

    private fun psbt(maps: List<List<Pair<ByteArray, ByteArray>>>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(PSBT_MAGIC)
        maps.forEach { fields ->
            fields.forEach { (key, value) ->
                output.write(compactSize(key.size))
                output.write(key)
                output.write(compactSize(value.size))
                output.write(value)
            }
            output.write(0)
        }
        return output.toByteArray()
    }

    private fun compactSize(value: Int): ByteArray = when {
        value < 253 -> byteArrayOf(value.toByte())
        value <= 0xffff -> byteArrayOf(0xfd.toByte(), value.toByte(), (value ushr 8).toByte())
        else -> byteArrayOf(
            0xfe.toByte(),
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte(),
            (value ushr 24).toByte()
        )
    }

    private companion object {
        val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
    }
}
