package net.clench.wallet.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareWalletTypeTest {

    @Test
    fun `Tapsigner is NFC-only and uses Tap Protocol`() {
        assertTrue(HardwareWalletType.TAPSIGNER.supportsNfc)
        assertFalse(HardwareWalletType.TAPSIGNER.supportsQr)
        assertFalse(HardwareWalletType.TAPSIGNER.supportsSdCard)
        assertTrue(HardwareWalletType.TAPSIGNER.usesCoinkiteTapProtocol)
        assertTrue(HardwareWalletType.TAPSIGNER.isScreenlessSigner)
        assertFalse(HardwareWalletType.TAPSIGNER.usesColdcardNfcPayload)
    }

    @Test
    fun `Coldcards keep the NDEF NFC payload path`() {
        assertTrue(HardwareWalletType.COLDCARD_Q.usesColdcardNfcPayload)
        assertTrue(HardwareWalletType.COLDCARD_MK4.usesColdcardNfcPayload)
        assertTrue(HardwareWalletType.COLDCARD_MK5.usesColdcardNfcPayload)
        assertFalse(HardwareWalletType.COLDCARD_Q.usesCoinkiteTapProtocol)
    }
}
