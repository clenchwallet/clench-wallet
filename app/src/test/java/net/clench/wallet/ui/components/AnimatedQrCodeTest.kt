package net.clench.wallet.ui.components

import net.clench.wallet.domain.model.HardwareWalletType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedQrCodeTest {

    @Test
    fun `generic UR PSBT can use single static frame`() {
        val psbtBytes = ByteArray(300) { it.toByte() }
        val frames = psbtBytesToUrFrames(psbtBytes)

        assertEquals(1, frames.size)
        assertTrue(frames.single().startsWith("UR:CRYPTO-PSBT"))
        assertEquals(frames.single().uppercase(), frames.single())
    }

    @Test
    fun `SeedSigner density forces animated uppercase UR frames`() {
        val psbtBytes = ByteArray(300) { it.toByte() }
        val genericFrame = psbtBytesToUrFrames(psbtBytes).single()
        val seedSignerFrames = lowDensityFrames(psbtBytes)

        assertTrue(seedSignerFrames.size > 1)
        assertTrue(seedSignerFrames.all { it.startsWith("UR:CRYPTO-PSBT") })
        assertTrue(seedSignerFrames.all { it == it.uppercase() })
        assertTrue(seedSignerFrames.maxOf { it.length } < genericFrame.length)
    }

    @Test
    fun `Passport and SeedSigner use animated UR path`() {
        assertTrue(HardwareWalletType.SEEDSIGNER.requiresAnimatedPsbtUr())
        assertTrue(HardwareWalletType.FOUNDATION_PASSPORT.requiresAnimatedPsbtUr())
        assertTrue(!HardwareWalletType.KEYSTONE.requiresAnimatedPsbtUr())
        assertTrue(!HardwareWalletType.JADE.requiresAnimatedPsbtUr())
        assertTrue(!HardwareWalletType.TAPSIGNER.requiresAnimatedPsbtUr())
    }

    @Test
    fun `Passport animated UR frames are denser than SeedSigner`() {
        val psbtBytes = ByteArray(600) { it.toByte() }
        val seedSignerFrames = psbtBytesToUrFrames(
            psbtBytes = psbtBytes,
            maxFragmentLen = HardwareWalletType.SEEDSIGNER.psbtUrFragmentLen(),
            allowSingleFrame = false
        )
        val passportFrames = psbtBytesToUrFrames(
            psbtBytes = psbtBytes,
            maxFragmentLen = HardwareWalletType.FOUNDATION_PASSPORT.psbtUrFragmentLen(),
            allowSingleFrame = false
        )

        assertTrue(passportFrames.size < seedSignerFrames.size)
        assertTrue(passportFrames.maxOf { it.length } > seedSignerFrames.maxOf { it.length })
    }

    @Test
    fun `Passport QR frame delay is slower than generic BC-UR`() {
        assertEquals(125L, HardwareWalletType.SEEDSIGNER.psbtQrFrameDelayMs())
        assertEquals(250L, HardwareWalletType.FOUNDATION_PASSPORT.psbtQrFrameDelayMs())
        assertEquals(125L, HardwareWalletType.KEYSTONE.psbtQrFrameDelayMs())
        assertEquals(125L, HardwareWalletType.JADE.psbtQrFrameDelayMs())
        assertEquals(125L, HardwareWalletType.TAPSIGNER.psbtQrFrameDelayMs())
    }

    private fun lowDensityFrames(psbtBytes: ByteArray): List<String> {
        return psbtBytesToUrFrames(
            psbtBytes = psbtBytes,
            maxFragmentLen = HardwareWalletType.SEEDSIGNER.psbtUrFragmentLen(),
            allowSingleFrame = false
        )
    }
}
