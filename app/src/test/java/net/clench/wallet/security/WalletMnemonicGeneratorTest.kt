package net.clench.wallet.security

import io.mockk.mockk
import org.bitcoindevkit.Mnemonic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.ProviderException

class WalletMnemonicGeneratorTest {

    @Test
    fun `12 words pass exactly 128 deterministic entropy bits to BDK`() {
        assertDeterministicEntropy(wordCount = 12, expectedBytes = 16)
    }

    @Test
    fun `24 words pass exactly 256 deterministic entropy bits to BDK`() {
        assertDeterministicEntropy(wordCount = 24, expectedBytes = 32)
    }

    @Test
    fun `temporary entropy is wiped when BDK construction throws`() {
        var sourceBuffer: ByteArray? = null
        val generator = WalletMnemonicGenerator(
            entropySource = WalletEntropySource { destination ->
                destination.fill(0x5a)
                sourceBuffer = destination
            },
            mnemonicFactory = WalletMnemonicFactory {
                throw IllegalStateException("native mnemonic construction failed")
            }
        )

        assertThrows(IllegalStateException::class.java) {
            generator.generate(12)
        }

        assertArrayEquals(ByteArray(16), sourceBuffer)
    }

    @Test
    fun `entropy provider failure is propagated without invoking BDK or fallback`() {
        var sourceBuffer: ByteArray? = null
        var factoryCalls = 0
        val generator = WalletMnemonicGenerator(
            entropySource = WalletEntropySource { destination ->
                destination.fill(0x7f)
                sourceBuffer = destination
                throw ProviderException("OS entropy unavailable")
            },
            mnemonicFactory = WalletMnemonicFactory {
                factoryCalls += 1
                mockk()
            }
        )

        assertThrows(ProviderException::class.java) {
            generator.generate(24)
        }

        assertEquals(0, factoryCalls)
        assertArrayEquals(ByteArray(32), sourceBuffer)
    }

    @Test
    fun `unsupported word count fails before requesting entropy`() {
        var sourceCalls = 0
        var factoryCalls = 0
        val generator = WalletMnemonicGenerator(
            entropySource = WalletEntropySource { sourceCalls += 1 },
            mnemonicFactory = WalletMnemonicFactory {
                factoryCalls += 1
                mockk()
            }
        )

        assertThrows(IllegalArgumentException::class.java) {
            generator.generate(18)
        }

        assertEquals(0, sourceCalls)
        assertEquals(0, factoryCalls)
    }

    private fun assertDeterministicEntropy(wordCount: Int, expectedBytes: Int) {
        var sourceBuffer: ByteArray? = null
        var bdkInput: ByteArray? = null
        val expectedMnemonic = mockk<Mnemonic>()
        val expectedEntropy = ByteArray(expectedBytes) { index -> (index * 7 + 3).toByte() }
        val generator = WalletMnemonicGenerator(
            entropySource = WalletEntropySource { destination ->
                expectedEntropy.copyInto(destination)
                sourceBuffer = destination
            },
            mnemonicFactory = WalletMnemonicFactory { entropy ->
                bdkInput = entropy.copyOf()
                expectedMnemonic
            }
        )

        val actual = generator.generate(wordCount)

        assertSame(expectedMnemonic, actual)
        assertArrayEquals(expectedEntropy, bdkInput)
        assertArrayEquals(ByteArray(expectedBytes), sourceBuffer)
    }
}
