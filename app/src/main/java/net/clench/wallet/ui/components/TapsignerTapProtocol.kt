package net.clench.wallet.ui.components

import android.nfc.Tag
import android.nfc.tech.IsoDep
import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.Array
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.Map
import co.nstant.`in`.cbor.model.Number
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.UnicodeString
import java.io.ByteArrayOutputStream

data class TapsignerStatus(
    val isTapsigner: Boolean,
    val version: String?,
    val birthHeight: Long?,
    val derivationPath: List<Long>?,
    val numberOfBackups: Long?,
    val authDelaySeconds: Long?,
    val cardPubkeyHex: String?,
    val cardNonceHex: String?
) {
    val displayPath: String?
        get() = derivationPath?.let { path ->
            if (path.isEmpty()) {
                "m"
            } else {
                path.joinToString(separator = "/", prefix = "m/") { value ->
                    val hardened = value and HARDENED_FLAG != 0L
                    val index = if (hardened) value and HARDENED_FLAG.inv() else value
                    if (hardened) "$index'" else index.toString()
                }
            }
        }

    fun summary(): String {
        val parts = mutableListOf<String>()
        version?.let { parts += "firmware $it" }
        displayPath?.let { parts += "path $it" }
        numberOfBackups?.let { parts += "$it backup${if (it == 1L) "" else "s"}" }
        authDelaySeconds?.takeIf { it > 0 }?.let { parts += "auth delay ${it}s" }
        return if (parts.isEmpty()) "Tapsigner detected" else "Tapsigner detected: ${parts.joinToString(", ")}"
    }

    private companion object {
        const val HARDENED_FLAG = 0x80000000L
    }
}

object TapsignerTapProtocol {
    private val appletId = byteArrayOf(
        0xF0.toByte(),
        0x43,
        0x6F,
        0x69,
        0x6E,
        0x6B,
        0x69,
        0x74,
        0x65,
        0x43,
        0x41,
        0x52,
        0x44,
        0x76,
        0x31
    )

    fun selectAppletCommand(): ByteArray = apdu(
        cla = 0x00,
        ins = 0xA4,
        p1 = 0x04,
        p2 = 0x00,
        data = appletId
    )

    fun statusCommand(): ByteArray = apdu(
        cla = 0x00,
        ins = 0xCB,
        p1 = 0x00,
        p2 = 0x00,
        data = cborMap("cmd" to "status")
    )

    fun parseStatusResponse(response: ByteArray): TapsignerStatus {
        val body = responseBodyOrThrow(response)
        if (body.isEmpty()) error("Tapsigner returned success without a CBOR status body")
        val dataItem = CborDecoder.decode(body).firstOrNull() as? Map
            ?: error("Tapsigner response was not a CBOR map")
        dataItem.string("error")?.let { errorText ->
            val code = dataItem.long("code")?.let { " ($it)" }.orEmpty()
            error("Tapsigner returned error$code: $errorText")
        }
        return TapsignerStatus(
            isTapsigner = dataItem.boolean("tapsigner") == true,
            version = dataItem.string("ver"),
            birthHeight = dataItem.long("birth"),
            derivationPath = dataItem.longArray("path"),
            numberOfBackups = dataItem.long("num_backups"),
            authDelaySeconds = dataItem.long("auth_delay"),
            cardPubkeyHex = dataItem.bytes("pubkey")?.toHex(),
            cardNonceHex = dataItem.bytes("card_nonce")?.toHex()
        )
    }

    fun isSuccessResponse(response: ByteArray): Boolean {
        return response.size >= 2 &&
            response[response.lastIndex - 1].toInt() and 0xFF == 0x90 &&
            response[response.lastIndex].toInt() and 0xFF == 0x00
    }

    fun responseBody(response: ByteArray): ByteArray {
        return if (response.size >= 2) response.copyOfRange(0, response.size - 2) else ByteArray(0)
    }

    private fun responseBodyOrThrow(response: ByteArray): ByteArray {
        if (response.size < 2) error("Tapsigner NFC response was too short")
        if (!isSuccessResponse(response)) {
            val sw = response.takeLast(2).joinToString("") { "%02X".format(it) }
            error("Tapsigner NFC command failed with status word 0x$sw")
        }
        return responseBody(response)
    }

    private fun cborMap(vararg entries: Pair<String, String>): ByteArray {
        val map = CborBuilder().addMap()
        entries.forEach { (key, value) -> map.put(key, value) }
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(map.end().build())
        return out.toByteArray()
    }

    private fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ByteArray {
        require(data.size <= 255) { "Short APDU data cannot exceed 255 bytes" }
        return byteArrayOf(
            cla.toByte(),
            ins.toByte(),
            p1.toByte(),
            p2.toByte(),
            data.size.toByte()
        ) + data
    }

    private fun Map.value(key: String): DataItem? = get(UnicodeString(key))

    private fun Map.string(key: String): String? = (value(key) as? UnicodeString)?.string

    private fun Map.boolean(key: String): Boolean? {
        return when (value(key)) {
            SimpleValue.TRUE -> true
            SimpleValue.FALSE -> false
            else -> null
        }
    }

    private fun Map.long(key: String): Long? = (value(key) as? Number)?.value?.toLong()

    private fun Map.longArray(key: String): List<Long>? {
        val array = value(key) as? Array ?: return null
        return array.dataItems.mapNotNull { (it as? Number)?.value?.toLong() }
    }

    private fun Map.bytes(key: String): ByteArray? = (value(key) as? ByteString)?.bytes

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

object TapsignerNfcReader {
    fun readStatus(tag: Tag): TapsignerStatus {
        val isoDep = IsoDep.get(tag) ?: error("Tapsigner requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 5000
            val selectResponse = isoDep.transceive(TapsignerTapProtocol.selectAppletCommand())
            if (!TapsignerTapProtocol.isSuccessResponse(selectResponse)) {
                val sw = selectResponse.takeLast(2).joinToString("") { "%02X".format(it) }
                error("Tapsigner applet select failed with status word 0x$sw")
            }
            val selectBody = TapsignerTapProtocol.responseBody(selectResponse)
            val response = if (selectBody.isNotEmpty()) {
                selectResponse
            } else {
                isoDep.transceive(TapsignerTapProtocol.statusCommand())
            }
            val status = TapsignerTapProtocol.parseStatusResponse(response)
            if (!status.isTapsigner) error("Coinkite NFC card is not reporting Tapsigner mode")
            return status
        } finally {
            isoDep.close()
        }
    }
}
