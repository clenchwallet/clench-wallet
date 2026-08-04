package net.clench.wallet.security

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextFreeRawSignaturePolicyTest {

    @Test
    fun `ECDSA SIGHASH ALL is allowed in scriptSig and witness`() {
        ContextFreeRawSignaturePolicy.validate(
            rawTransaction(
                Input(scriptSig = push(ecdsaSignature(0x01))),
                Input(witness = listOf(ecdsaSignature(0x01), byteArrayOf(0x51)))
            )
        )
    }

    @Test
    fun `recognizable weak ECDSA flags are rejected in scriptSig and witness`() {
        listOf(0x02, 0x03, 0x81, 0x82, 0x83).forEach { flag ->
            assertRejected(
                rawTransaction(Input(scriptSig = push(ecdsaSignature(flag)))),
                "0x${flag.toString(16)}"
            )
            assertRejected(
                rawTransaction(Input(witness = listOf(ecdsaSignature(flag), byteArrayOf(0x51)))),
                "0x${flag.toString(16)}"
            )
        }

        val weak = ecdsaSignature(0x82)
        assertRejected(
            rawTransaction(
                Input(scriptSig = byteArrayOf(0x4c, weak.size.toByte()) + weak)
            ),
            "0x82"
        )
    }

    @Test
    fun `Taproot key path DEFAULT and ALL are allowed while weak flags are rejected`() {
        ContextFreeRawSignaturePolicy.validate(
            rawTransaction(Input(witness = listOf(taprootSignature())))
        )
        ContextFreeRawSignaturePolicy.validate(
            rawTransaction(Input(witness = listOf(taprootSignature(0x01))))
        )

        listOf(0x00, 0x02, 0x03, 0x81, 0x82, 0x83).forEach { flag ->
            assertRejected(
                rawTransaction(Input(witness = listOf(taprootSignature(flag)))),
                "0x${flag.toString(16)}"
            )
        }
    }

    @Test
    fun `Taproot annex is excluded and weak key path signature remains rejected`() {
        val annex = ByteArray(65) { 0x82.toByte() }.also { it[0] = 0x50 }
        ContextFreeRawSignaturePolicy.validate(
            rawTransaction(Input(witness = listOf(taprootSignature(0x01), annex)))
        )

        assertRejected(
            rawTransaction(Input(witness = listOf(taprootSignature(0x81), annex))),
            "0x81"
        )
    }

    @Test
    fun `Taproot script and control block are excluded from signature candidates`() {
        val unusualTapscript = ByteArray(65) { 0x82.toByte() }.also { it[0] = 0x51 }
        val controlBlock = ByteArray(33) { 0x82.toByte() }.also { it[0] = 0xc0.toByte() }

        ContextFreeRawSignaturePolicy.validate(
            rawTransaction(
                Input(witness = listOf(taprootSignature(0x01), unusualTapscript, controlBlock))
            )
        )

        assertRejected(
            rawTransaction(
                Input(witness = listOf(taprootSignature(0x82), unusualTapscript, controlBlock))
            ),
            "0x82"
        )
    }

    @Test
    fun `mixed inputs reject the weak signature even when other inputs are safe`() {
        assertRejected(
            rawTransaction(
                Input(scriptSig = push(ecdsaSignature(0x01))),
                Input(witness = listOf(taprootSignature(0x03)))
            ),
            "input 1"
        )
    }

    @Test
    fun `non Taproot shaped 65 byte witness data is not treated as a signature`() {
        // A witness-v0 stack may contain a 65-byte pubkey/preimage followed by
        // its witness script. Without prevouts this is not a Taproot shape.
        val uncompressedPubkey = ByteArray(65) { 0x82.toByte() }.also { it[0] = 0x04 }
        ContextFreeRawSignaturePolicy.validate(
            rawTransaction(Input(witness = listOf(uncompressedPubkey, byteArrayOf(0x51))))
        )
    }

    @Test
    fun `ambiguous single 65 byte witness item fails conservatively`() {
        val ambiguous = ByteArray(65) { 0x41 }
        assertRejected(
            rawTransaction(Input(witness = listOf(ambiguous))),
            "0x41"
        )
    }

    @Test
    fun `truncated pushdata in scriptSig fails closed`() {
        assertRejected(
            rawTransaction(Input(scriptSig = byteArrayOf(0x4c))),
            "truncated PUSHDATA"
        )
        assertRejected(
            rawTransaction(Input(scriptSig = byteArrayOf(0x4c, 0x02, 0x01))),
            "truncated script push"
        )
    }

    @Test
    fun `truncated witness framing fails closed`() {
        val malformed = byteArrayOf(
            0x02, 0x00, 0x00, 0x00, // version
            0x00, 0x01, // witness marker and flag
            0x01 // input count
        ) + ByteArray(32) + byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // vout
            0x00, // scriptSig length
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), // sequence
            0x01, // output count
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // value
            0x00, // output script length
            0x01, // witness item count
            0x41, // claims 65 witness bytes
            0x01, 0x02 // truncated item
        )

        assertRejected(malformed, "truncated input 0 witness item 0")
    }

    private fun assertRejected(raw: ByteArray, expectedMessage: String) {
        val failure = runCatching { ContextFreeRawSignaturePolicy.validate(raw) }.exceptionOrNull()
        assertTrue("Expected SecurityException, got $failure", failure is SecurityException)
        assertTrue(
            "Expected '${failure?.message}' to contain '$expectedMessage'",
            failure?.message?.contains(expectedMessage) == true
        )
    }

    private data class Input(
        val scriptSig: ByteArray = byteArrayOf(),
        val witness: List<ByteArray> = emptyList()
    )

    private fun rawTransaction(vararg inputs: Input): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(le32(2))
        val hasWitness = inputs.any { it.witness.isNotEmpty() }
        if (hasWitness) {
            output.write(0x00)
            output.write(0x01)
        }
        writeCompactSize(output, inputs.size.toLong())
        inputs.forEachIndexed { index, input ->
            output.write(ByteArray(32))
            output.write(le32(index))
            writeCompactSize(output, input.scriptSig.size.toLong())
            output.write(input.scriptSig)
            output.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        }
        writeCompactSize(output, 1)
        output.write(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        writeCompactSize(output, 0)
        if (hasWitness) {
            inputs.forEach { input ->
                writeCompactSize(output, input.witness.size.toLong())
                input.witness.forEach { item ->
                    writeCompactSize(output, item.size.toLong())
                    output.write(item)
                }
            }
        }
        output.write(le32(0))
        return output.toByteArray()
    }

    private fun ecdsaSignature(flag: Int): ByteArray =
        byteArrayOf(0x30, 0x44, 0x02, 0x20) +
            ByteArray(32) { 0x11 } +
            byteArrayOf(0x02, 0x20) +
            ByteArray(32) { 0x22 } +
            flag.toByte()

    private fun taprootSignature(flag: Int? = null): ByteArray =
        ByteArray(64) { 0x33 } + (flag?.let { byteArrayOf(it.toByte()) } ?: byteArrayOf())

    private fun push(value: ByteArray): ByteArray {
        require(value.size <= 0x4b)
        return byteArrayOf(value.size.toByte()) + value
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte()
    )

    private fun writeCompactSize(output: ByteArrayOutputStream, value: Long) {
        require(value < 0xfd)
        output.write(value.toInt())
    }
}
