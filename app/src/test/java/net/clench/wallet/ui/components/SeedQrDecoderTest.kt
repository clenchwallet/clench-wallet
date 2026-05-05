package net.clench.wallet.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SeedQrDecoderTest {

    private val wordlist: List<String> by lazy {
        File("src/main/assets/bip39_english.txt").readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    @Test
    fun `standard SeedQR decodes numeric word indexes`() {
        val seedQr = "0000".repeat(11) + "0003"

        val result = decodeStandardSeedQr(seedQr, wordlist)

        assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", result)
    }

    @Test
    fun `compact SeedQR decodes 128-bit entropy`() {
        val entropy = ByteArray(16)

        val result = decodeCompactSeedQr(entropy, wordlist)

        assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", result)
    }

    @Test
    fun `compact SeedQR decodes 256-bit entropy`() {
        val entropy = ByteArray(32)

        val result = decodeCompactSeedQr(entropy, wordlist)

        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
            result
        )
    }

    @Test
    fun `compact SeedQR rejects unsupported payload length`() {
        assertNull(decodeCompactSeedQr(ByteArray(15), wordlist))
    }
}
