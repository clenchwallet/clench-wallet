package net.clench.wallet.ui.components

import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.CryptoECKey
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.MultiKey
import com.sparrowwallet.hummingbird.registry.RegistryType
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.URHDKey
import com.sparrowwallet.hummingbird.registry.URKeypath
import com.sparrowwallet.hummingbird.registry.UROutputDescriptor
import com.sparrowwallet.hummingbird.registry.URPSBT
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.PairPathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.PathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.WildcardPathComponent
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareWalletQrPayloadDecoderTest {

    @Test
    fun `bytes UR normalizes binary PSBT to base64`() {
        val psbt = validPsbt()
        val ur = UR.fromBytes(RegistryType.BYTES.toString(), psbt)

        assertEquals(
            Base64.getEncoder().encodeToString(psbt),
            HardwareWalletQrPayloadDecoder.decodeUrPayload(ur)
        )
    }

    @Test
    fun `bytes UR normalizes binary transaction to base64`() {
        val transaction = validTransaction()
        val ur = UR.fromBytes(RegistryType.BYTES.toString(), transaction)

        assertEquals(
            Base64.getEncoder().encodeToString(transaction),
            HardwareWalletQrPayloadDecoder.decodeUrPayload(ur)
        )
    }

    @Test
    fun `bytes UR preserves strict UTF-8 text`() {
        val ur = UR.fromBytes(
            RegistryType.BYTES.toString(),
            "  wpkh([AABBCCDD/84'/0'/0']xpub6Example/0/*)\n".toByteArray()
        )

        assertEquals(
            "wpkh([AABBCCDD/84'/0'/0']xpub6Example/0/*)",
            HardwareWalletQrPayloadDecoder.decodeUrPayload(ur)
        )
    }

    @Test
    fun `bytes UR rejects unrecognized non-UTF8 binary`() {
        val ur = UR.fromBytes(
            RegistryType.BYTES.toString(),
            byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0xfd.toByte())
        )

        assertNull(HardwareWalletQrPayloadDecoder.decodeUrPayload(ur))
    }

    @Test
    fun `bytes UR rejects control-byte payload that is not a transaction`() {
        val ur = UR.fromBytes(
            RegistryType.BYTES.toString(),
            byteArrayOf(0x01, 0x02, 0x03, 0x04)
        )

        assertNull(HardwareWalletQrPayloadDecoder.decodeUrPayload(ur))
    }

    @Test
    fun `deprecated PSBT registry type decodes to base64`() {
        val psbt = validPsbt()

        assertEquals(
            Base64.getEncoder().encodeToString(psbt),
            HardwareWalletQrPayloadDecoder.decodeUrPayload(URPSBT(psbt).toUR())
        )
    }

    @Test
    fun `deprecated PSBT registry type rejects malformed PSBT`() {
        assertNull(
            HardwareWalletQrPayloadDecoder.decodeUrPayload(
                URPSBT(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())).toUR()
            )
        )
    }

    @Test
    fun `static Base43 PSBT normalizes to base64`() {
        val psbt = validPsbt()

        assertEquals(
            Base64.getEncoder().encodeToString(psbt),
            HardwareWalletQrPayloadDecoder.normalizeStaticPayload(base43Encode(psbt))
        )
    }

    @Test
    fun `static Base43 transaction normalizes to base64`() {
        val transaction = validTransaction()

        assertEquals(
            Base64.getEncoder().encodeToString(transaction),
            HardwareWalletQrPayloadDecoder.normalizeStaticPayload(base43Encode(transaction))
        )
    }

    @Test
    fun `static payload normalizer preserves malformed and unrecognized Base43`() {
        assertEquals(
            "not-a-descriptor",
            HardwareWalletQrPayloadDecoder.normalizeStaticPayload("  not-a-descriptor  ")
        )
        assertEquals("0000", HardwareWalletQrPayloadDecoder.normalizeStaticPayload("0000"))

        val truncatedTransaction = base43Encode(validTransaction().dropLast(1).toByteArray())
        assertEquals(
            truncatedTransaction,
            HardwareWalletQrPayloadDecoder.normalizeStaticPayload(truncatedTransaction)
        )
    }

    @Test
    fun `static Base43 decoding is bounded`() {
        val oversized = "0".repeat(4_297)

        assertEquals(oversized, HardwareWalletQrPayloadDecoder.normalizeStaticPayload(oversized))
    }

    @Test
    fun `single-key crypto output preserves nested SegWit descriptor expressions`() {
        val output = CryptoOutput(
            listOf(ScriptExpression.SCRIPT_HASH, ScriptExpression.WITNESS_PUBLIC_KEY_HASH),
            testKey(1)
        )

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNotNull(decoded)
        assertTrue(decoded!!.startsWith("sh(wpkh([AABBCC01/48'/0'/0'/2']xpub"))
        assertTrue(decoded.endsWith("/0/*))"))
    }

    @Test
    fun `single-key crypto output preserves Taproot descriptor expression`() {
        val output = CryptoOutput(listOf(ScriptExpression.TAPROOT), testKey(1))

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNotNull(decoded)
        assertTrue(decoded!!.startsWith("tr([AABBCC01/48'/0'/0'/2']xpub"))
        assertTrue(decoded.endsWith("/0/*)"))
    }

    @Test
    fun `single-key crypto output rejects unsupported descriptor expression`() {
        val output = CryptoOutput(listOf(ScriptExpression.COMBO), testKey(1))

        assertNull(HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR()))
    }

    @Test
    fun `crypto output multisig decodes to full sortedmulti descriptor`() {
        val output = CryptoOutput(
            listOf(ScriptExpression.WITNESS_SCRIPT_HASH, ScriptExpression.SORTED_MULTISIG),
            MultiKey(
                2,
                emptyList<CryptoECKey>(),
                listOf(testKey(1), testKey(2), testKey(3))
            )
        )

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNotNull(decoded)
        decoded!!
        assertTrue(decoded.startsWith("wsh(sortedmulti(2,"))
        assertTrue(decoded.contains("[AABBCC01/48'/0'/0'/2']xpub"))
        assertTrue(decoded.contains("[AABBCC02/48'/0'/0'/2']xpub"))
        assertTrue(decoded.contains("[AABBCC03/48'/0'/0'/2']xpub"))
        assertEquals(3, Regex("/0/\\*").findAll(decoded).count())
        assertFalse(decoded.matches(Regex("^\\[[^]]+]xpub.*")))
    }

    @Test
    fun `crypto output multisig uses external branch from paired child path`() {
        val output = CryptoOutput(
            listOf(
                ScriptExpression.SCRIPT_HASH,
                ScriptExpression.WITNESS_SCRIPT_HASH,
                ScriptExpression.SORTED_MULTISIG
            ),
            MultiKey(
                2,
                emptyList<CryptoECKey>(),
                listOf(testKey(1, pairedChildren()), testKey(2, pairedChildren()))
            )
        )

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNotNull(decoded)
        decoded!!
        assertTrue(decoded.startsWith("sh(wsh(sortedmulti(2,"))
        assertEquals(2, Regex("/0/\\*").findAll(decoded).count())
        assertFalse(decoded.contains("/1/*"))
    }

    @Test
    fun `ur output descriptor expands all multisig placeholders`() {
        val output = UROutputDescriptor(
            "wsh(sortedmulti(2,@0/**,@1/**,@2/**))",
            listOf(testKey(1), testKey(2), testKey(3))
        )

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNotNull(decoded)
        decoded!!
        assertTrue(decoded.startsWith("wsh(sortedmulti(2,"))
        assertTrue(decoded.contains("[AABBCC01/48'/0'/0'/2']xpub"))
        assertTrue(decoded.contains("[AABBCC02/48'/0'/0'/2']xpub"))
        assertTrue(decoded.contains("[AABBCC03/48'/0'/0'/2']xpub"))
        assertEquals(3, Regex("/0/\\*").findAll(decoded).count())
    }

    @Test
    fun `ur output descriptor with unresolved multisig placeholder does not return first key`() {
        val output = UROutputDescriptor(
            "wsh(sortedmulti(2,@0/**,@1/**,@2/**))",
            listOf(testKey(1), testKey(2))
        )

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNull(decoded)
    }

    private fun testKey(index: Int, children: URKeypath? = null): CryptoHDKey {
        val publicKey = ByteArray(33) { i -> (index + i).toByte() }.also { it[0] = 0x02 }
        val chainCode = ByteArray(32) { i -> (index * 3 + i).toByte() }
        val fingerprint = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), index.toByte())
        val origin = URKeypath(
            listOf<PathComponent>(
                IndexPathComponent(48, true),
                IndexPathComponent(0, true),
                IndexPathComponent(0, true),
                IndexPathComponent(2, true)
            ),
            fingerprint
        )
        return URHDKey(
            false,
            publicKey,
            chainCode,
            null,
            origin,
            children,
            fingerprint
        )
    }

    private fun pairedChildren(): URKeypath {
        return URKeypath(
            listOf<PathComponent>(
                PairPathComponent(
                    IndexPathComponent(0, false),
                    IndexPathComponent(1, false)
                ),
                WildcardPathComponent(false)
            ),
            null
        )
    }

    private fun validPsbt(): ByteArray {
        val transaction = validTransaction()
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

    private fun validTransaction(): ByteArray {
        return hexToBytes(
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
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun base43Encode(bytes: ByteArray): String {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\$*+-./:"
        var value = BigInteger(1, bytes)
        val encoded = StringBuilder()
        while (value > BigInteger.ZERO) {
            val quotientAndRemainder = value.divideAndRemainder(BigInteger.valueOf(43))
            value = quotientAndRemainder[0]
            encoded.append(alphabet[quotientAndRemainder[1].toInt()])
        }
        bytes.takeWhile { it == 0.toByte() }.forEach { _ -> encoded.append('0') }
        return encoded.reverse().toString()
    }
}
