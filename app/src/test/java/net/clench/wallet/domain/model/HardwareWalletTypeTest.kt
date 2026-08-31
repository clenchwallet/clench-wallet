package net.clench.wallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `supported signer transports never advertise USB Bluetooth or virtual disk`() {
        HardwareWalletType.entries.forEach { device ->
            val transport = device.connectionMethod.lowercase()
            assertFalse("${device.displayName} advertised USB", transport.contains("usb"))
            assertFalse("${device.displayName} advertised Bluetooth", transport.contains("bluetooth"))
            assertFalse("${device.displayName} advertised BLE", transport.contains("ble"))
            assertFalse("${device.displayName} advertised Virtual Disk", transport.contains("virtual disk"))
        }
    }

    @Test
    fun `QR signer metadata distinguishes crypto PSBT UR from BBQr`() {
        val cryptoPsbtDevices = listOf(
            HardwareWalletType.SEEDSIGNER,
            HardwareWalletType.KEYSTONE,
            HardwareWalletType.ONEKEY_PRO,
            HardwareWalletType.KRUX,
            HardwareWalletType.SPECTER_DIY,
            HardwareWalletType.FOUNDATION_PASSPORT,
            HardwareWalletType.JADE
        )

        cryptoPsbtDevices.forEach { device ->
            assertEquals(PsbtQrFormat.CRYPTO_PSBT_UR, device.psbtQrFormat)
            assertTrue("${device.displayName} should advertise QR", device.supportsQr)
        }
        assertEquals(PsbtQrFormat.BBQR, HardwareWalletType.COLDCARD_Q.psbtQrFormat)
        assertTrue(HardwareWalletType.COLDCARD_Q.supportsQr)
        assertNull(HardwareWalletType.COLDCARD_MK4.psbtQrFormat)
        assertNull(HardwareWalletType.COLDCARD_MK5.psbtQrFormat)
        assertNull(HardwareWalletType.TAPSIGNER.psbtQrFormat)
    }

    @Test
    fun `new air gapped signers expose only their supported file paths`() {
        assertFalse(HardwareWalletType.ONEKEY_PRO.supportsFileTransfer)
        assertFalse(HardwareWalletType.ONEKEY_PRO.supportsSdCard)

        listOf(HardwareWalletType.KRUX, HardwareWalletType.SPECTER_DIY).forEach { device ->
            assertTrue("${device.displayName} should support file transfer", device.supportsFileTransfer)
            assertTrue("${device.displayName} should advertise microSD", device.supportsSdCard)
            assertFalse("${device.displayName} should not advertise NFC", device.supportsNfc)
        }
    }

    @Test
    fun `Coldcards without cameras retain NFC and removable file paths`() {
        listOf(HardwareWalletType.COLDCARD_MK4, HardwareWalletType.COLDCARD_MK5).forEach { device ->
            assertTrue(device.supportsNfc)
            assertTrue(device.supportsSdCard)
            assertTrue(device.connectionMethod.contains("File"))
        }
    }
}
