package net.clench.wallet.viewmodel

import net.clench.wallet.ui.viewmodel.WifPrivateKeyParser
import org.bitcoindevkit.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

class WifPrivateKeyParserTest {

    @Test
    fun `extracts coldcard paper wallet WIF from text export`() {
        val text = """
            Coldcard Generated Paper Wallet

            Deposit address:
              13ZcQHhwgu2mrpn54JVLn7x9xgDtGdf14d

            Private key (WIF=Wallet Import Format):
              L3ZeFQJAgAfmPeNEZwyriuh1djs8Lq6fj9psjafBEjmY564SBBmg
        """.trimIndent()

        val parsed = WifPrivateKeyParser.extract(text.toCharArray(), Network.BITCOIN)

        assertEquals("L3ZeFQJAgAfmPeNEZwyriuh1djs8Lq6fj9psjafBEjmY564SBBmg", parsed.value)
        assertTrue(parsed.compressed)
    }

    @Test
    fun `parses uncompressed mainnet WIF`() {
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val wif = base58CheckEncode(byteArrayOf(0x80.toByte()) + keyBytes)

        val parsed = WifPrivateKeyParser.parse(wif, Network.BITCOIN)

        assertEquals(wif, parsed.value)
        assertFalse(parsed.compressed)
    }

    @Test
    fun `rejects wrong active network`() {
        val mainnetWif = "L3ZeFQJAgAfmPeNEZwyriuh1djs8Lq6fj9psjafBEjmY564SBBmg"

        try {
            WifPrivateKeyParser.parse(mainnetWif, Network.TESTNET)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("active testnet"))
            return
        }
        error("Expected wrong-network WIF to be rejected")
    }

    @Test
    fun `rejects checksum mismatch`() {
        val typo = "L3ZeFQJAgAfmPeNEZwyriuh1djs8Lq6fj9psjafBEjmY564SBBmh"

        try {
            WifPrivateKeyParser.parse(typo, Network.BITCOIN)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("checksum"))
            return
        }
        error("Expected checksum mismatch to be rejected")
    }

    @Test
    fun `parses testnet compressed WIF`() {
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val testnetWif = base58CheckEncode(byteArrayOf(0xEF.toByte()) + keyBytes + byteArrayOf(0x01))

        val parsed = WifPrivateKeyParser.parse(testnetWif, Network.TESTNET)

        assertEquals(testnetWif, parsed.value)
        assertTrue(parsed.compressed)
    }

    @Test
    fun `renders raw satscard private key as compressed WIF`() {
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val expected = base58CheckEncode(byteArrayOf(0x80.toByte()) + keyBytes + byteArrayOf(0x01))

        val rendered = WifPrivateKeyParser.fromRawPrivateKey(keyBytes, Network.BITCOIN)

        assertEquals(expected, rendered.value)
        assertTrue(rendered.compressed)
    }

    @Test
    fun `rejects invalid zero private key`() {
        try {
            WifPrivateKeyParser.fromRawPrivateKey(ByteArray(32), Network.BITCOIN)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("secp256k1"))
            return
        }
        error("Expected invalid private key to be rejected")
    }

    private fun base58CheckEncode(payload: ByteArray): String {
        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
        return base58Encode(payload + checksum)
    }

    private fun base58Encode(input: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = BigInteger(1, input)
        val encoded = StringBuilder()
        while (num > BigInteger.ZERO) {
            val divRem = num.divideAndRemainder(BigInteger.valueOf(58))
            num = divRem[0]
            encoded.append(alphabet[divRem[1].toInt()])
        }
        repeat(input.takeWhile { it == 0.toByte() }.count()) {
            encoded.append('1')
        }
        return encoded.reverse().toString()
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }
}
