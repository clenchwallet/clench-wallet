package net.clench.wallet.ui.components

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.BadParcelableException

/** Decode the whole untrusted intent before emitting either its tag or signing payload. */
internal object NfcIntentDecoder {
    sealed interface Result {
        data object Ignored : Result
        data object Rejected : Result
        data class Decoded(val tag: Tag?, val signingPayload: String?) : Result
    }

    @Suppress("DEPRECATION")
    fun decode(intent: Intent): Result {
        if (intent.action !in setOf(NfcAdapter.ACTION_NDEF_DISCOVERED,
                NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_TAG_DISCOVERED)) {
            return Result.Ignored
        }
        return try {
            val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            require(messages == null || (messages.size == 1 && messages[0] is NdefMessage))
            val payload = (messages?.singleOrNull() as? NdefMessage)?.let {
                ColdcardNfcPayload.extractSigningPayload(it)
            }
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            Result.Decoded(tag, payload)
        } catch (_: BadParcelableException) {
            Result.Rejected
        } catch (_: IllegalArgumentException) {
            Result.Rejected
        } catch (_: ClassCastException) {
            Result.Rejected
        } catch (_: IndexOutOfBoundsException) {
            Result.Rejected
        }
    }
}
