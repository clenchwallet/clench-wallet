package net.clench.wallet.ui.components

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import net.clench.wallet.security.InputLimits
import net.clench.wallet.security.PsbtSafety

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
    private val SHA256_TYPE = "bitcoin.org:sha256".toByteArray(Charsets.US_ASCII)
    private const val MAX_NDEF_RECORDS = 32
    private const val MAX_NDEF_BYTES = 6 * 1024 * 1024
    private const val MAX_TRANSACTION_BYTES = 2 * 1024 * 1024
    private const val MAX_TEXT_RECORD_BYTES = InputLimits.SECRET_TEXT_CHARS * 4

    fun unsignedPsbtMessage(psbtBase64: String): NdefMessage {
        PsbtSafety.inspectBase64(psbtBase64)
        val psbtBytes = Base64.getDecoder().decode(psbtBase64.filterNot(Char::isWhitespace))
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
        requireBoundedMessage(message)
        for (record in message.records) {
            val text = record.asBoundedTextOrUri()?.trim().orEmpty()
            if (text.isNotBlank()) return text
        }
        for (record in message.records) {
            if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE || record.tnf == NdefRecord.TNF_MIME_MEDIA) {
                require(record.payload.size <= MAX_TEXT_RECORD_BYTES) {
                    "NFC text export exceeds the import safety limit"
                }
                val text = runCatching { record.payload.toString(Charsets.UTF_8).trim() }
                    .getOrNull()
                    .orEmpty()
                require(text.length <= InputLimits.SECRET_TEXT_CHARS) {
                    "NFC text export exceeds the import safety limit"
                }
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
        requireBoundedMessage(message)
        val signingRecords = message.records.filter { record ->
            record.tnf == NdefRecord.TNF_EXTERNAL_TYPE &&
                (record.type.contentEquals(PSBT_TYPE) || record.type.contentEquals(TXN_TYPE))
        }
        require(signingRecords.size <= 1) {
            "NFC message contains multiple signing payloads"
        }
        val checksumRecords = message.records.filter { record ->
            record.tnf == NdefRecord.TNF_EXTERNAL_TYPE &&
                record.type.contentEquals(SHA256_TYPE)
        }
        require(checksumRecords.size <= 1) {
            "NFC message contains multiple integrity records"
        }
        require(checksumRecords.isEmpty() || signingRecords.size == 1) {
            "NFC integrity record has no binary signing payload"
        }
        signingRecords.singleOrNull()?.let { record ->
            val payload = record.payload
            require(payload.isNotEmpty()) { "NFC signing payload is empty" }
            if (record.type.contentEquals(PSBT_TYPE)) {
                require(payload.size <= InputLimits.PSBT_BYTES) {
                    "NFC PSBT exceeds the binary safety limit"
                }
                PsbtSafety.inspectBytes(payload)
            } else {
                require(payload.size <= MAX_TRANSACTION_BYTES) {
                    "NFC transaction exceeds the binary safety limit"
                }
            }
            checksumRecords.singleOrNull()?.payload?.let { checksum ->
                require(checksum.size == 32) { "NFC integrity record is not a SHA-256 digest" }
                val actual = MessageDigest.getInstance("SHA-256").digest(payload)
                require(MessageDigest.isEqual(checksum, actual)) {
                    "NFC signing payload failed its SHA-256 integrity check"
                }
            }
            return Base64.getEncoder().encodeToString(payload)
        }

        for (record in message.records) {
            val text = record.asBoundedTextOrUri()?.trim().orEmpty()
            if (text.isBlank()) continue
            parsePushTxUrl(text)?.let { return it }
            if (runCatching { PsbtSafety.inspectBase64(text) }.isSuccess) return text
            if (text.length <= InputLimits.RAW_TRANSACTION_CHARS &&
                text.length % 2 == 0 &&
                text.matches(Regex("^[0-9a-fA-F]+$"))
            ) {
                return text
            }
        }
        return null
    }

    private fun requireBoundedMessage(message: NdefMessage) {
        require(message.records.size <= MAX_NDEF_RECORDS) {
            "NFC message contains too many records"
        }
        var totalBytes = 0
        for (record in message.records) {
            for (field in listOf(record.type, record.id, record.payload)) {
                require(field.size <= MAX_NDEF_BYTES - totalBytes) {
                    "NFC message exceeds the payload safety limit"
                }
                totalBytes += field.size
            }
        }
    }

    private fun NdefRecord.asTextOrUri(): String? {
        return when {
            tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_TEXT) -> decodeTextPayload(payload)
            tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_URI) -> decodeUriPayload(payload)
            tnf == NdefRecord.TNF_ABSOLUTE_URI || tnf == NdefRecord.TNF_MIME_MEDIA -> payload.toString(Charsets.UTF_8)
            else -> null
        }
    }

    private fun NdefRecord.asBoundedTextOrUri(): String? {
        require(payload.size <= MAX_TEXT_RECORD_BYTES) {
            "NFC text record exceeds the import safety limit"
        }
        return asTextOrUri()?.also { text ->
            require(text.length <= InputLimits.SECRET_TEXT_CHARS) {
                "NFC text record exceeds the import safety limit"
            }
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
        if (value.length > InputLimits.RAW_TRANSACTION_CHARS) return null
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
        val decoded = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: return null
        if (decoded.isEmpty() || decoded.size > MAX_TRANSACTION_BYTES) return null
        return Base64.getEncoder().encodeToString(decoded)
    }
}
