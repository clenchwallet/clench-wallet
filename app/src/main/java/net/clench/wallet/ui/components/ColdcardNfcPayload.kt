package net.clench.wallet.ui.components

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.util.Base64
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * NDEF payload helpers for Coldcard Mk4/Mk5/Q NFC PSBT transfer.
 *
 * Coldcard exposes NFC as a Type 5 tag and uses external NDEF records:
 * - urn:nfc:ext:bitcoin.org:psbt for binary PSBT payloads
 * - urn:nfc:ext:bitcoin.org:txn for finalized transaction payloads
 * - urn:nfc:ext:bitcoin.org:sha256 for optional integrity metadata
 */
object ColdcardNfcPayload {
    private val PSBT_TYPE = "bitcoin.org:psbt".toByteArray(Charsets.US_ASCII)
    private val TXN_TYPE = "bitcoin.org:txn".toByteArray(Charsets.US_ASCII)

    fun unsignedPsbtMessage(psbtBase64: String): NdefMessage {
        val psbtBytes = Base64.decode(psbtBase64.trim(), Base64.DEFAULT)
        val checksum = MessageDigest.getInstance("SHA-256").digest(psbtBytes)
        return NdefMessage(
            arrayOf(
                NdefRecord.createTextRecord("en", "Unsigned PSBT"),
                NdefRecord.createExternal("bitcoin.org", "sha256", checksum),
                NdefRecord.createExternal("bitcoin.org", "psbt", psbtBytes)
            )
        )
    }

    /**
     * Returns the first textual/URI payload from an NFC message. Used for hardware
     * wallet onboarding where a device may share an xpub, descriptor, or JSON export.
     */
    fun extractTextPayload(message: NdefMessage): String? {
        for (record in message.records) {
            val text = record.asTextOrUri()?.trim().orEmpty()
            if (text.isNotBlank()) return text
        }
        for (record in message.records) {
            if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE || record.tnf == NdefRecord.TNF_MIME_MEDIA) {
                val text = runCatching { record.payload.toString(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    /**
     * Returns base64 PSBT/transaction bytes suitable for the existing validation path.
     * Text/URI PushTx payloads are accepted but still require Clench confirmation.
     */
    fun extractSigningPayload(message: NdefMessage): String? {
        for (record in message.records) {
            if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE) {
                when {
                    record.type.contentEquals(PSBT_TYPE) || record.type.contentEquals(TXN_TYPE) -> {
                        return Base64.encodeToString(record.payload, Base64.NO_WRAP)
                    }
                }
            }
        }

        for (record in message.records) {
            val text = record.asTextOrUri()?.trim().orEmpty()
            if (text.isBlank()) continue
            parsePushTxUrl(text)?.let { return it }
            if (text.startsWith("cHNid", ignoreCase = true)) return text
            if (text.length % 2 == 0 && text.matches(Regex("^[0-9a-fA-F]+$"))) return text
        }
        return null
    }

    private fun NdefRecord.asTextOrUri(): String? {
        return when {
            tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_TEXT) -> decodeTextPayload(payload)
            tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_URI) -> decodeUriPayload(payload)
            tnf == NdefRecord.TNF_ABSOLUTE_URI || tnf == NdefRecord.TNF_MIME_MEDIA -> payload.toString(Charsets.UTF_8)
            else -> null
        }
    }

    private fun decodeTextPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt()
        val languageCodeLength = status and 0x3F
        val textStart = 1 + languageCodeLength
        if (textStart >= payload.size) return null
        val charset = if ((status and 0x80) != 0) Charsets.UTF_16 else Charsets.UTF_8
        return String(payload, textStart, payload.size - textStart, charset)
    }

    private fun decodeUriPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val prefixes = arrayOf(
            "", "http://www.", "https://www.", "http://", "https://",
            "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
            "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:", "telnet://",
            "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://",
            "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
            "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:"
        )
        val prefix = prefixes.getOrElse(payload[0].toInt() and 0xFF) { "" }
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    private fun parsePushTxUrl(value: String): String? {
        val params = value.substringAfter('#', missingDelimiterValue = "")
            .ifBlank { value.substringAfter('?', missingDelimiterValue = "") }
        if (params.isBlank()) return null
        val transactionParam = params.split('&')
            .firstOrNull { it.startsWith("t=") }
            ?.substringAfter('=')
            ?: return null
        val base64Url = URLDecoder.decode(transactionParam, "UTF-8")
        val padded = base64Url.replace('-', '+').replace('_', '/').let { decoded ->
            decoded + "=".repeat((4 - decoded.length % 4) % 4)
        }
        return padded
    }
}
