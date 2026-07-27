package net.clench.wallet.ui.components

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import io.mockk.every
import io.mockk.mockk
import java.security.MessageDigest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ColdcardNfcHostileTest {
    private val structuralPsbt = byteArrayOf(
        0x70, 0x73, 0x62, 0x74, 0xff.toByte(),
        0x01, 0x00, 0x01, 0x00, 0x00
    )

    @Test
    fun `single PSBT with matching checksum is accepted`() {
        val checksum = MessageDigest.getInstance("SHA-256").digest(structuralPsbt)
        val message = message(
            external("bitcoin.org:sha256", checksum),
            external("bitcoin.org:psbt", structuralPsbt)
        )

        assertNotNull(ColdcardNfcPayload.extractSigningPayload(message))
    }

    @Test
    fun `conflicting signing records and checksums fail closed`() {
        val duplicatePayloads = message(
            external("bitcoin.org:psbt", structuralPsbt),
            external("bitcoin.org:txn", byteArrayOf(0x01))
        )
        val wrongChecksum = message(
            external("bitcoin.org:sha256", ByteArray(32) { 0x55 }),
            external("bitcoin.org:psbt", structuralPsbt)
        )

        assertThrows(IllegalArgumentException::class.java) {
            ColdcardNfcPayload.extractSigningPayload(duplicatePayloads)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ColdcardNfcPayload.extractSigningPayload(wrongChecksum)
        }
    }

    @Test
    fun `oversized and excessive NDEF records fail before decoding`() {
        val oversized = message(
            external("bitcoin.org:txn", ByteArray(6 * 1024 * 1024 + 1))
        )
        val excessive = message(
            *Array(33) { external("example:test", byteArrayOf(0x01)) }
        )

        assertThrows(IllegalArgumentException::class.java) {
            ColdcardNfcPayload.extractSigningPayload(oversized)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ColdcardNfcPayload.extractSigningPayload(excessive)
        }
    }

    private fun message(vararg records: NdefRecord): NdefMessage =
        mockk {
            every { this@mockk.records } returns records
        }

    private fun external(type: String, payloadBytes: ByteArray): NdefRecord =
        mockk {
            every { tnf } returns NdefRecord.TNF_EXTERNAL_TYPE
            every { this@mockk.type } returns type.toByteArray(Charsets.US_ASCII)
            every { id } returns ByteArray(0)
            every { payload } returns payloadBytes
        }
}
