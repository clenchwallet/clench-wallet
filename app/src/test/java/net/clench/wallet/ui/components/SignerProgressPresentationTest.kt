package net.clench.wallet.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import net.clench.wallet.domain.model.HardwareWalletType

class SignerProgressPresentationTest {
    @Test
    fun `unsigned workflow only marks review complete`() {
        val steps = SignerProgressPresentation.steps(
            reviewAcknowledged = true,
            hasCollectedSignature = false,
            readyToBroadcast = false
        )

        assertTrue(steps[0].complete)
        assertFalse(steps[1].complete)
        assertFalse(steps[2].complete)
        assertFalse(steps[3].complete)
        assertEquals("Waiting for signer", SignerProgressPresentation.signatureStatus(0, false))
    }

    @Test
    fun `ready workflow reports policy completion`() {
        val steps = SignerProgressPresentation.steps(true, true, true)
        assertTrue(steps.all { it.complete })
        assertEquals("2 signer returns; policy complete", SignerProgressPresentation.signatureStatus(2, true))
    }

    @Test
    fun `device guidance distinguishes screenless signer guardrail`() {
        assertTrue(
            SignerProgressPresentation.transferDetail(HardwareWalletType.TAPSIGNER)
                .contains("screenless")
        )
        assertTrue(
            SignerProgressPresentation.transferDetail(HardwareWalletType.COLDCARD_Q)
                .contains("BBQr")
        )
    }

    @Test
    fun `new air gapped signer guidance names each QR and file flow`() {
        assertTrue(
            SignerProgressPresentation.transferDetail(HardwareWalletType.ONEKEY_PRO)
                .contains("animated BC-UR")
        )
        assertTrue(
            SignerProgressPresentation.transferDetail(HardwareWalletType.KRUX)
                .contains("microSD")
        )
        assertTrue(
            SignerProgressPresentation.transferDetail(HardwareWalletType.SPECTER_DIY)
                .contains("Specter DIY")
        )
    }
}
