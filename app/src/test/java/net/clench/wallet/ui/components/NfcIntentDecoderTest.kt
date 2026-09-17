package net.clench.wallet.ui.components

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.BadParcelableException
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class NfcIntentDecoderTest {
    private fun intent(message: NdefMessage): Intent = mockk {
        every { action } returns NfcAdapter.ACTION_NDEF_DISCOVERED
        every { getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) } returns arrayOf(message)
        every { getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) } returns null
    }
    private fun message(vararg records: NdefRecord): NdefMessage = mockk {
        every { this@mockk.records } returns records
    }
    private fun external(payload: ByteArray): NdefRecord = mockk {
        every { tnf } returns NdefRecord.TNF_EXTERNAL_TYPE
        every { type } returns "bitcoin.org:psbt".toByteArray()
        every { id } returns byteArrayOf()
        every { this@mockk.payload } returns payload
    }

    @Test fun `duplicate empty and truncated signing payloads are contained`() {
        val record = external(byteArrayOf(1))
        for (message in listOf(message(record, record), message(external(byteArrayOf())), message(record))) {
            assertEquals(NfcIntentDecoder.Result.Rejected, NfcIntentDecoder.decode(intent(message)))
        }
    }

    @Test fun `malformed parcel and late tag decode are contained before dispatch`() {
        val incoming = intent(message())
        every { incoming.getParcelableArrayExtra(any()) } throws BadParcelableException("fixture")
        assertEquals(NfcIntentDecoder.Result.Rejected, NfcIntentDecoder.decode(incoming))
        every { incoming.getParcelableArrayExtra(any()) } returns null
        every { incoming.getParcelableExtra<Tag>(any()) } throws ClassCastException("fixture")
        assertEquals(NfcIntentDecoder.Result.Rejected, NfcIntentDecoder.decode(incoming))
    }

    @Test fun `unsupported action is ignored without touching extras`() {
        val incoming = mockk<Intent> { every { action } returns "unrelated" }
        assertEquals(NfcIntentDecoder.Result.Ignored, NfcIntentDecoder.decode(incoming))
    }

    @Test fun `valid PSBT remains accepted after rejected message`() {
        val bytes = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte(), 1, 0, 1, 0, 0)
        assertEquals(NfcIntentDecoder.Result.Rejected, NfcIntentDecoder.decode(intent(message(external(byteArrayOf())))))
        val decoded = NfcIntentDecoder.decode(intent(message(external(bytes))))
        assertTrue(decoded is NfcIntentDecoder.Result.Decoded)
        assertEquals(java.util.Base64.getEncoder().encodeToString(bytes),
            (decoded as NfcIntentDecoder.Result.Decoded).signingPayload)
    }

    @Test fun `fatal errors are not converted into invalid input`() {
        val incoming = intent(message())
        every { incoming.getParcelableArrayExtra(any()) } throws LinkageError("fixture")
        assertThrows(LinkageError::class.java) { NfcIntentDecoder.decode(incoming) }
    }
}
