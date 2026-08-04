package net.clench.wallet.security

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.function.ThrowingRunnable

class ExternalSignaturePolicyTest {
    @Test
    fun `ECDSA partial signatures accept only SIGHASH ALL`() {
        for (flag in 0..255) {
            val psbt = psbt(inputFields = listOf(field(partialSigKey(2), ecdsaSignature(flag))))
            if (flag == 1) {
                ExternalSignaturePolicy.validatePsbtBase64(psbt, ecdsa, 1)
            } else {
                assertThrows<SecurityException>("flag 0x${flag.toString(16)} must fail") {
                    ExternalSignaturePolicy.validatePsbtBase64(psbt, ecdsa, 1)
                }
            }
        }
    }

    @Test
    fun `PSBT sighash metadata rejects NONE SINGLE and ANYONECANPAY`() {
        (0..255).filterNot { it == 1 }.forEach { flag ->
            val psbt = psbt(inputFields = listOf(field(byteArrayOf(0x03), uint32(flag))))
            assertThrows<SecurityException>("metadata flag 0x${flag.toString(16)} must fail") {
                ExternalSignaturePolicy.validatePsbtBase64(psbt, ecdsa, 1)
            }
        }
        ExternalSignaturePolicy.validatePsbtBase64(
            psbt(inputFields = listOf(field(byteArrayOf(0x03), uint32(1)))),
            ecdsa,
            1
        )
        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validatePsbtBase64(
                psbt(inputFields = listOf(field(byteArrayOf(0x03), uint32(0x01000001)))),
                ecdsa,
                1
            )
        }
    }

    @Test
    fun `Taproot metadata and signatures accept only DEFAULT or ALL`() {
        ExternalSignaturePolicy.validatePsbtBase64(
            psbt(inputFields = listOf(field(byteArrayOf(0x03), uint32(0)), field(byteArrayOf(0x13), ByteArray(64) { 7 }))),
            taproot,
            1
        )
        ExternalSignaturePolicy.validatePsbtBase64(
            psbt(inputFields = listOf(field(byteArrayOf(0x03), uint32(1)), field(byteArrayOf(0x13), ByteArray(65) { if (it == 64) 1 else 7 }))),
            taproot,
            1
        )

        (0..255).filterNot { it == 1 }.forEach { flag ->
            val signature = ByteArray(65) { if (it == 64) flag.toByte() else 7 }
            assertThrows<SecurityException>("Taproot flag 0x${flag.toString(16)} must fail") {
                ExternalSignaturePolicy.validatePsbtBase64(
                    psbt(inputFields = listOf(field(byteArrayOf(0x13), signature))),
                    taproot,
                    1
                )
            }
        }

        (0..255).filterNot { it == 0 || it == 1 }.forEach { flag ->
            assertThrows<SecurityException>("Taproot metadata flag 0x${flag.toString(16)} must fail") {
                ExternalSignaturePolicy.validatePsbtBase64(
                    psbt(inputFields = listOf(field(byteArrayOf(0x03), uint32(flag)))),
                    taproot,
                    1
                )
            }
        }
    }

    @Test
    fun `Taproot script signatures enforce policy and key shape`() {
        val scriptSigKey = byteArrayOf(0x14) + ByteArray(64) { it.toByte() }
        ExternalSignaturePolicy.validatePsbtBase64(
            psbt(inputFields = listOf(field(scriptSigKey, ByteArray(64) { 9 }))),
            taproot,
            1
        )

        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validatePsbtBase64(
                psbt(inputFields = listOf(field(scriptSigKey, ByteArray(65) { if (it == 64) 0x82.toByte() else 9 }))),
                taproot,
                1
            )
        }
        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validatePsbtBase64(
                psbt(inputFields = listOf(field(byteArrayOf(0x14, 1), ByteArray(64)))),
                taproot,
                1
            )
        }
    }

    @Test
    fun `final PSBT witness and scriptSig reject weak ECDSA signatures`() {
        val weak = ecdsaSignature(0x82)
        val finalWitness = field(byteArrayOf(0x08), witness(listOf(weak, ByteArray(33) { 2 })))
        val finalScriptSig = field(byteArrayOf(0x07), push(weak) + push(ByteArray(33) { 2 }))

        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validatePsbtBase64(psbt(inputFields = listOf(finalWitness)), ecdsa, 1)
        }
        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validatePsbtBase64(psbt(inputFields = listOf(finalScriptSig)), ecdsa, 1)
        }

        ExternalSignaturePolicy.validatePsbtBase64(
            psbt(inputFields = listOf(field(byteArrayOf(0x08), witness(listOf(ecdsaSignature(1), ByteArray(33) { 2 }))))),
            ecdsa,
            1
        )
    }

    @Test
    fun `final multisig witness validates every signature and ignores witness script`() {
        val scriptThatLooksLikeWeakSignature = ecdsaSignature(0x82)
        val validWitness = witness(
            listOf(byteArrayOf(), ecdsaSignature(1), ecdsaSignature(1), scriptThatLooksLikeWeakSignature)
        )
        ExternalSignaturePolicy.validatePsbtBase64(
            psbt(inputFields = listOf(field(byteArrayOf(0x08), validWitness))),
            ecdsaWitnessScript,
            1
        )

        val weakWitness = witness(
            listOf(byteArrayOf(), ecdsaSignature(1), ecdsaSignature(3), ByteArray(71) { 0x51 })
        )
        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validatePsbtBase64(
                psbt(inputFields = listOf(field(byteArrayOf(0x08), weakWitness))),
                ecdsaWitnessScript,
                1
            )
        }
    }

    @Test
    fun `finalized raw ECDSA inputs reject weak flags`() {
        ExternalSignaturePolicy.validateFinalizedInputs(
            listOf(
                ExternalSignaturePolicy.FinalizedInput(
                    ExternalSignaturePolicy.InputKind.ECDSA,
                    byteArrayOf(),
                    listOf(ecdsaSignature(1), ByteArray(33) { 2 })
                )
            )
        )
        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validateFinalizedInputs(
                listOf(
                    ExternalSignaturePolicy.FinalizedInput(
                        ExternalSignaturePolicy.InputKind.ECDSA,
                        byteArrayOf(),
                        listOf(ecdsaSignature(3), ByteArray(33) { 2 })
                    )
                )
            )
        }
    }

    @Test
    fun `finalized raw Taproot key and script paths enforce DEFAULT or ALL`() {
        ExternalSignaturePolicy.validateFinalizedInputs(
            listOf(
                ExternalSignaturePolicy.FinalizedInput(
                    ExternalSignaturePolicy.InputKind.TAPROOT,
                    byteArrayOf(),
                    // A single key-path signature beginning with the annex marker
                    // is still a signature. Annex detection requires 2+ items.
                    listOf(ByteArray(64) { if (it == 0) 0x50 else 1 })
                ),
                ExternalSignaturePolicy.FinalizedInput(
                    ExternalSignaturePolicy.InputKind.TAPROOT,
                    byteArrayOf(),
                    listOf(
                        ByteArray(65) { if (it == 64) 1 else 3 },
                        byteArrayOf(0x20) + ByteArray(32),
                        byteArrayOf(0xc0.toByte()) + ByteArray(32)
                    )
                )
            )
        )

        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validateFinalizedInputs(
                listOf(
                    ExternalSignaturePolicy.FinalizedInput(
                        ExternalSignaturePolicy.InputKind.TAPROOT,
                        byteArrayOf(),
                        listOf(ByteArray(65) { if (it == 64) 0x81.toByte() else 1 })
                    )
                )
            )
        }
    }

    @Test
    fun `signature-only merge preserves canonical metadata and drops returned metadata`() {
        val existing = field(partialSigKey(2), ecdsaSignature(1, rByte = 2))
        val added = field(partialSigKey(3), ecdsaSignature(1, rByte = 3))
        val canonicalInputMetadata = field(byteArrayOf(0x01), byteArrayOf(0x55))
        val canonicalOutputMetadata = field(byteArrayOf(0x02), byteArrayOf(0x66))
        val canonicalGlobalMetadata = field(byteArrayOf(0xfb.toByte()), byteArrayOf(0x77))
        val untrustedInputMetadata = field(byteArrayOf(0xfc.toByte(), 1), byteArrayOf(0x11))
        val untrustedOutputMetadata = field(byteArrayOf(0xfc.toByte(), 2), byteArrayOf(0x22))
        val untrustedGlobalMetadata = field(byteArrayOf(0xfc.toByte(), 3), byteArrayOf(0x33))

        val current = psbt(
            globalFields = listOf(canonicalGlobalMetadata),
            inputFields = listOf(canonicalInputMetadata, existing),
            outputFields = listOf(canonicalOutputMetadata)
        )
        val returned = psbt(
            globalFields = listOf(untrustedGlobalMetadata),
            inputFields = listOf(untrustedInputMetadata, field(byteArrayOf(0x03), uint32(1)), added),
            outputFields = listOf(untrustedOutputMetadata)
        )

        val merged = ExternalSignaturePolicy.mergeSignatureMaterial(current, returned, ecdsa, 1)
        val expected = psbt(
            globalFields = listOf(canonicalGlobalMetadata),
            inputFields = listOf(canonicalInputMetadata, existing, added),
            outputFields = listOf(canonicalOutputMetadata)
        )
        assertEquals(expected, merged)
    }

    @Test
    fun `multisig merge rejects conflicting signature for the same key`() {
        val key = partialSigKey(2)
        val current = psbt(inputFields = listOf(field(key, ecdsaSignature(1, rByte = 2))))
        val returned = psbt(inputFields = listOf(field(key, ecdsaSignature(1, rByte = 3))))

        assertThrows<SecurityException> {
            ExternalSignaturePolicy.mergeSignatureMaterial(current, returned, ecdsaWitnessScript, 1)
        }
    }

    @Test
    fun `signature merge rejects a different unsigned transaction`() {
        val current = psbt(inputFields = emptyList(), unsignedTx = byteArrayOf(1))
        val returned = psbt(
            inputFields = listOf(field(partialSigKey(2), ecdsaSignature(1))),
            unsignedTx = byteArrayOf(2)
        )

        assertThrows<SecurityException> {
            ExternalSignaturePolicy.mergeSignatureMaterial(current, returned, ecdsa, 1)
        }
    }

    @Test
    fun `finalization material is imported but sighash metadata is not`() {
        val current = psbt(inputFields = emptyList())
        val finalWitness = field(
            byteArrayOf(0x08),
            witness(listOf(ecdsaSignature(1), ByteArray(33) { 2 }))
        )
        val returned = psbt(
            inputFields = listOf(field(byteArrayOf(0x03), uint32(1)), finalWitness)
        )

        val merged = ExternalSignaturePolicy.mergeSignatureMaterial(current, returned, ecdsa, 1)
        assertEquals(psbt(inputFields = listOf(finalWitness)), merged)
    }

    @Test
    fun `malformed finalized fields fail closed`() {
        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validatePsbtBase64(
                psbt(inputFields = listOf(field(byteArrayOf(0x08), byteArrayOf(1, 5, 1)))),
                ecdsa,
                1
            )
        }
        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validateFinalizedInputs(
                listOf(
                    ExternalSignaturePolicy.FinalizedInput(
                        ExternalSignaturePolicy.InputKind.ECDSA,
                        byteArrayOf(0x4c),
                        emptyList()
                    )
                )
            )
        }
    }

    @Test
    fun `PSBT input type cannot mix ECDSA and Taproot signatures`() {
        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validatePsbtBase64(
                psbt(inputFields = listOf(field(byteArrayOf(0x13), ByteArray(64)))),
                ecdsa,
                1
            )
        }
        assertThrows<SecurityException> {
            ExternalSignaturePolicy.validatePsbtBase64(
                psbt(inputFields = listOf(field(partialSigKey(2), ecdsaSignature(1)))),
                taproot,
                1
            )
        }
    }

    private fun psbt(
        globalFields: List<Pair<ByteArray, ByteArray>> = emptyList(),
        inputFields: List<Pair<ByteArray, ByteArray>>,
        outputFields: List<Pair<ByteArray, ByteArray>> = emptyList(),
        unsignedTx: ByteArray = byteArrayOf(1)
    ): String {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
        writeMap(out, listOf(field(byteArrayOf(0x00), unsignedTx)) + globalFields)
        writeMap(out, inputFields)
        writeMap(out, outputFields)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun writeMap(out: ByteArrayOutputStream, fields: List<Pair<ByteArray, ByteArray>>) {
        fields.forEach { (key, value) ->
            out.write(compactSize(key.size))
            out.write(key)
            out.write(compactSize(value.size))
            out.write(value)
        }
        out.write(0)
    }

    private fun field(key: ByteArray, value: ByteArray) = key to value

    private fun partialSigKey(marker: Int): ByteArray =
        byteArrayOf(0x02) + ByteArray(33) { if (it == 0) 0x02 else marker.toByte() }

    private fun ecdsaSignature(flag: Int, rByte: Int = 1): ByteArray =
        byteArrayOf(0x30, 0x44, 0x02, 0x20) +
            ByteArray(32) { rByte.toByte() } +
            byteArrayOf(0x02, 0x20) +
            ByteArray(32) { 1 } +
            byteArrayOf(flag.toByte())

    private fun witness(items: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        out.write(compactSize(items.size))
        items.forEach { item ->
            out.write(compactSize(item.size))
            out.write(item)
        }
    }.toByteArray()

    private fun push(value: ByteArray): ByteArray {
        assertTrue(value.size < 0x4c)
        return byteArrayOf(value.size.toByte()) + value
    }

    private fun uint32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte()
    )

    private fun compactSize(value: Int): ByteArray = when {
        value < 253 -> byteArrayOf(value.toByte())
        value <= 0xffff -> byteArrayOf(253.toByte(), value.toByte(), (value ushr 8).toByte())
        else -> byteArrayOf(
            254.toByte(),
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte(),
            (value ushr 24).toByte()
        )
    }

    private val ecdsa = listOf(ExternalSignaturePolicy.InputKind.ECDSA)
    private val ecdsaWitnessScript = listOf(ExternalSignaturePolicy.InputKind.ECDSA_WITNESS_SCRIPT)
    private val taproot = listOf(ExternalSignaturePolicy.InputKind.TAPROOT)

    private inline fun <reified T : Throwable> assertThrows(
        message: String? = null,
        noinline block: () -> Unit
    ): T = if (message == null) {
        org.junit.Assert.assertThrows(T::class.java, ThrowingRunnable { block() })
    } else {
        org.junit.Assert.assertThrows(message, T::class.java, ThrowingRunnable { block() })
    }
}
