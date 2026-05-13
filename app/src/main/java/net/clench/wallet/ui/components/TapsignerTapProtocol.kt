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
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters

enum class CoinkiteTapCardKind {
    TAPSIGNER,
    SATSCARD,
    UNKNOWN
}

data class CoinkiteTapCardStatus(
    val isTapsigner: Boolean,
    val version: String?,
    val birthHeight: Long?,
    val derivationPath: List<Long>?,
    val numberOfBackups: Long?,
    val authDelaySeconds: Long?,
    val cardPubkeyHex: String?,
    val cardNonceHex: String?,
    val address: String?,
    val slots: List<Long>?,
    val isTestnet: Boolean?,
    val isTampered: Boolean?
) {
    val isSatscard: Boolean
        get() = !isTapsigner && (address != null || !slots.isNullOrEmpty())

    val kind: CoinkiteTapCardKind
        get() = when {
            isTapsigner -> CoinkiteTapCardKind.TAPSIGNER
            isSatscard -> CoinkiteTapCardKind.SATSCARD
            else -> CoinkiteTapCardKind.UNKNOWN
        }

    val activeSlot: Long?
        get() = slots?.firstOrNull()

    val slotCount: Long?
        get() = slots?.getOrNull(1) ?: slots?.size?.toLong()

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

    val defaultTapsignerAccountPath: String
        get() = "m/84'/${if (isTestnet == true) 1 else 0}'/0'"

    fun summary(): String {
        val parts = mutableListOf<String>()
        version?.let { parts += "firmware $it" }
        when (kind) {
            CoinkiteTapCardKind.TAPSIGNER -> {
                displayPath?.let { parts += "path $it" }
                numberOfBackups?.let { parts += "$it backup${if (it == 1L) "" else "s"}" }
            }
            CoinkiteTapCardKind.SATSCARD -> {
                address?.let { parts += "address $it" }
                slotCount?.let { parts += "$it slot${if (it == 1L) "" else "s"}" }
                isTestnet?.takeIf { it }?.let { parts += "testnet" }
            }
            CoinkiteTapCardKind.UNKNOWN -> {}
        }
        isTampered?.takeIf { it }?.let { parts += "tamper warning" }
        authDelaySeconds?.takeIf { it > 0 }?.let { parts += "auth delay ${it}s" }
        val name = when (kind) {
            CoinkiteTapCardKind.TAPSIGNER -> "TAPSIGNER"
            CoinkiteTapCardKind.SATSCARD -> "SATSCARD"
            CoinkiteTapCardKind.UNKNOWN -> "Coinkite card"
        }
        return if (parts.isEmpty()) "$name detected" else "$name detected: ${parts.joinToString(", ")}"
    }

    private companion object {
        const val HARDENED_FLAG = 0x80000000L
    }
}

typealias TapsignerStatus = CoinkiteTapCardStatus

data class SatscardUnsealResult(
    val slot: Long,
    val privateKey: ByteArray,
    val publicKeyHex: String?,
    val address: String,
    val isTestnet: Boolean,
    val summary: String
)

data class SatscardFundingSlotResult(
    val slot: Long,
    val address: String,
    val isTestnet: Boolean,
    val newlySetup: Boolean,
    val summary: String
)

data class SatscardReadResult(
    val signature: ByteArray,
    val pubkey: ByteArray,
    val cardNonce: ByteArray
)

data class CoinkiteCertsResult(
    val certChain: List<ByteArray>
)

data class CoinkiteCheckResult(
    val authSignature: ByteArray,
    val cardNonce: ByteArray
)

data class CoinkiteWaitResult(
    val authDelaySeconds: Long?
)

class CoinkiteTapCardException(
    val code: Long?,
    val cardError: String
) : IllegalStateException(
    buildString {
        append("Coinkite Tap card error")
        code?.let { append(" $it") }
        append(": ")
        append(cardError)
    }
)

data class TapsignerXpubResponse(
    val xpub: ByteArray,
    val cardNonce: ByteArray?
)

data class TapsignerNewResult(
    val slot: Long,
    val cardNonce: ByteArray
)

data class TapsignerDeriveResult(
    val chainCode: ByteArray,
    val masterPubkey: ByteArray,
    val pubkey: ByteArray,
    val cardNonce: ByteArray
)

data class TapsignerBackupResponse(
    val data: ByteArray,
    val cardNonce: ByteArray
)

data class TapsignerAccountXpubResult(
    val xpub: String,
    val originWrappedXpub: String,
    val masterFingerprint: String,
    val derivationPath: String,
    val summary: String
)

data class TapsignerBackupResult(
    val data: ByteArray,
    val numberOfBackups: Long?,
    val summary: String
)

object TapsignerTapProtocol {
    private const val HARDENED_FLAG = 0x80000000L

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

    fun readCommand(nonce: ByteArray): ByteArray = apdu(
        cla = 0x00,
        ins = 0xCB,
        p1 = 0x00,
        p2 = 0x00,
        data = cborMap(
            "cmd" to "read",
            "nonce" to nonce
        )
    )

    fun certsCommand(): ByteArray = apdu(
        cla = 0x00,
        ins = 0xCB,
        p1 = 0x00,
        p2 = 0x00,
        data = cborMap("cmd" to "certs")
    )

    fun checkCommand(nonce: ByteArray): ByteArray = apdu(
        cla = 0x00,
        ins = 0xCB,
        p1 = 0x00,
        p2 = 0x00,
        data = cborMap(
            "cmd" to "check",
            "nonce" to nonce
        )
    )

    fun waitCommand(): ByteArray = apdu(
        cla = 0x00,
        ins = 0xCB,
        p1 = 0x00,
        p2 = 0x00,
        data = cborMap("cmd" to "wait")
    )

    fun dumpCommand(slot: Long): ByteArray = apdu(
        cla = 0x00,
        ins = 0xCB,
        p1 = 0x00,
        p2 = 0x00,
        data = cborMap(
            "cmd" to "dump",
            "slot" to slot
        )
    )

    fun authenticatedUnsealCommand(
        slot: Long,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): Pair<ByteArray, ByteArray> {
        val auth = authenticatedCommand("unseal", cardPubkey, cardNonce, cvc)
        val command = apdu(
            cla = 0x00,
            ins = 0xCB,
            p1 = 0x00,
            p2 = 0x00,
            data = cborMap(
                "cmd" to "unseal",
                "slot" to slot,
                "epubkey" to auth.ephemeralPublicKey,
                "xcvc" to auth.encryptedCvc
            )
        )
        return command to auth.sessionKey
    }

    fun authenticatedXpubCommand(
        master: Boolean,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): ByteArray {
        val auth = authenticatedCommand("xpub", cardPubkey, cardNonce, cvc)
        return apdu(
            cla = 0x00,
            ins = 0xCB,
            p1 = 0x00,
            p2 = 0x00,
            data = cborMap(
                "cmd" to "xpub",
                "master" to master,
                "epubkey" to auth.ephemeralPublicKey,
                "xcvc" to auth.encryptedCvc
            )
        )
    }

    fun authenticatedNewTapsignerCommand(
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray,
        chainCode: ByteArray
    ): ByteArray {
        require(chainCode.size == 32) { "TAPSIGNER chain code must be 32 bytes" }
        return authenticatedNewSlotCommand(
            slot = 0L,
            cardPubkey = cardPubkey,
            cardNonce = cardNonce,
            cvc = cvc,
            chainCode = chainCode
        )
    }

    fun authenticatedNewSatscardCommand(
        slot: Long,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray,
        chainCode: ByteArray
    ): ByteArray {
        require(slot >= 0L) { "SATSCARD slot must be non-negative" }
        require(chainCode.size == 32) { "SATSCARD chain code must be 32 bytes" }
        return authenticatedNewSlotCommand(
            slot = slot,
            cardPubkey = cardPubkey,
            cardNonce = cardNonce,
            cvc = cvc,
            chainCode = chainCode
        )
    }

    private fun authenticatedNewSlotCommand(
        slot: Long,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray,
        chainCode: ByteArray
    ): ByteArray {
        val auth = authenticatedCommand("new", cardPubkey, cardNonce, cvc)
        return apdu(
            cla = 0x00,
            ins = 0xCB,
            p1 = 0x00,
            p2 = 0x00,
            data = cborMap(
                "cmd" to "new",
                "slot" to slot,
                "chain_code" to chainCode,
                "epubkey" to auth.ephemeralPublicKey,
                "xcvc" to auth.encryptedCvc
            )
        )
    }

    fun authenticatedDeriveCommand(
        path: List<Long>,
        nonce: ByteArray,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): ByteArray {
        require(path.size <= 8) { "TAPSIGNER derivation path is too deep" }
        require(path.all { it and HARDENED_FLAG != 0L }) { "TAPSIGNER derive supports hardened path components only" }
        require(nonce.size == 16) { "TAPSIGNER derive nonce must be 16 bytes" }
        val auth = authenticatedCommand("derive", cardPubkey, cardNonce, cvc)
        return apdu(
            cla = 0x00,
            ins = 0xCB,
            p1 = 0x00,
            p2 = 0x00,
            data = cborMap(
                "cmd" to "derive",
                "path" to path,
                "nonce" to nonce,
                "epubkey" to auth.ephemeralPublicKey,
                "xcvc" to auth.encryptedCvc
            )
        )
    }

    fun authenticatedBackupCommand(
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): ByteArray {
        val auth = authenticatedCommand("backup", cardPubkey, cardNonce, cvc)
        return apdu(
            cla = 0x00,
            ins = 0xCB,
            p1 = 0x00,
            p2 = 0x00,
            data = cborMap(
                "cmd" to "backup",
                "epubkey" to auth.ephemeralPublicKey,
                "xcvc" to auth.encryptedCvc
            )
        )
    }

    fun parseStatusResponse(response: ByteArray): CoinkiteTapCardStatus {
        val body = responseBodyOrThrow(response)
        if (body.isEmpty()) error("Coinkite Tap card returned success without a CBOR status body")
        val dataItem = CborDecoder.decode(body).firstOrNull() as? Map
            ?: error("Coinkite Tap card response was not a CBOR map")
        dataItem.string("error")?.let { errorText ->
            throw CoinkiteTapCardException(dataItem.long("code"), errorText)
        }
        return CoinkiteTapCardStatus(
            isTapsigner = dataItem.boolean("tapsigner") == true,
            version = dataItem.string("ver"),
            birthHeight = dataItem.long("birth"),
            derivationPath = dataItem.longArray("path"),
            numberOfBackups = dataItem.long("num_backups"),
            authDelaySeconds = dataItem.long("auth_delay"),
            cardPubkeyHex = dataItem.bytes("pubkey")?.toHex(),
            cardNonceHex = dataItem.bytes("card_nonce")?.toHex(),
            address = dataItem.string("addr"),
            slots = dataItem.longArray("slots"),
            isTestnet = dataItem.boolean("testnet"),
            isTampered = dataItem.boolean("tampered")
        )
    }

    fun parseReadResponse(response: ByteArray): SatscardReadResult {
        val dataItem = responseMapOrThrow(response)
        val signature = dataItem.bytes("sig") ?: error("Coinkite read response did not include a signature")
        val pubkey = dataItem.bytes("pubkey") ?: error("Coinkite read response did not include a public key")
        val cardNonce = dataItem.bytes("card_nonce") ?: error("Coinkite read response did not include a card nonce")
        if (signature.size != 64) error("Coinkite read response had an invalid signature length")
        if (pubkey.size != 33) error("Coinkite read response had an invalid public key length")
        if (cardNonce.size != 16) error("Coinkite read response had an invalid card nonce length")
        return SatscardReadResult(signature, pubkey, cardNonce)
    }

    fun parseCertsResponse(response: ByteArray): CoinkiteCertsResult {
        val dataItem = responseMapOrThrow(response)
        val certChain = dataItem.bytesArray("cert_chain") ?: error("Coinkite certs response did not include cert_chain")
        if (certChain.any { it.size != 65 }) error("Coinkite cert chain contained an invalid signature")
        return CoinkiteCertsResult(certChain)
    }

    fun parseCheckResponse(response: ByteArray): CoinkiteCheckResult {
        val dataItem = responseMapOrThrow(response)
        val authSig = dataItem.bytes("auth_sig") ?: error("Coinkite check response did not include auth_sig")
        val cardNonce = dataItem.bytes("card_nonce") ?: error("Coinkite check response did not include card_nonce")
        if (authSig.size != 64) error("Coinkite check response had an invalid signature length")
        if (cardNonce.size != 16) error("Coinkite check response had an invalid card nonce length")
        return CoinkiteCheckResult(authSig, cardNonce)
    }

    fun parseWaitResponse(response: ByteArray): CoinkiteWaitResult {
        val dataItem = responseMapOrThrow(response)
        if (dataItem.boolean("success") != true) error("Coinkite Tap card wait command did not succeed")
        return CoinkiteWaitResult(authDelaySeconds = dataItem.long("auth_delay"))
    }

    fun parseTapsignerXpubResponse(response: ByteArray): TapsignerXpubResponse {
        val dataItem = responseMapOrThrow(response)
        val xpub = dataItem.bytes("xpub") ?: error("TAPSIGNER xpub response did not include xpub")
        if (xpub.size != 78) error("TAPSIGNER returned an invalid xpub length")
        val cardNonce = dataItem.bytes("card_nonce")
        cardNonce?.let { if (it.size != 16) error("TAPSIGNER xpub response had an invalid card nonce length") }
        return TapsignerXpubResponse(xpub, cardNonce)
    }

    fun parseTapsignerNewResponse(response: ByteArray): TapsignerNewResult {
        val dataItem = responseMapOrThrow(response)
        val slot = dataItem.long("slot") ?: error("TAPSIGNER initialize response did not include slot")
        val cardNonce = dataItem.bytes("card_nonce") ?: error("TAPSIGNER initialize response did not include card nonce")
        if (cardNonce.size != 16) error("TAPSIGNER initialize response had an invalid card nonce length")
        return TapsignerNewResult(slot, cardNonce)
    }

    fun parseTapsignerDeriveResponse(response: ByteArray): TapsignerDeriveResult {
        val dataItem = responseMapOrThrow(response)
        val signature = dataItem.bytes("sig") ?: error("TAPSIGNER derive response did not include a signature")
        val chainCode = dataItem.bytes("chain_code") ?: error("TAPSIGNER derive response did not include chain code")
        val masterPubkey = dataItem.bytes("master_pubkey") ?: error("TAPSIGNER derive response did not include master pubkey")
        val pubkey = dataItem.bytes("pubkey") ?: error("TAPSIGNER derive response did not include derived pubkey")
        val cardNonce = dataItem.bytes("card_nonce") ?: error("TAPSIGNER derive response did not include card nonce")
        if (signature.size != 64) error("TAPSIGNER derive response had an invalid signature length")
        if (chainCode.size != 32) error("TAPSIGNER derive response had an invalid chain code length")
        if (masterPubkey.size != 33) error("TAPSIGNER derive response had an invalid master pubkey length")
        if (pubkey.size != 33) error("TAPSIGNER derive response had an invalid derived pubkey length")
        if (cardNonce.size != 16) error("TAPSIGNER derive response had an invalid card nonce length")
        return TapsignerDeriveResult(chainCode, masterPubkey, pubkey, cardNonce)
    }

    fun parseTapsignerBackupResponse(response: ByteArray): TapsignerBackupResponse {
        val dataItem = responseMapOrThrow(response)
        val data = dataItem.bytes("data") ?: error("TAPSIGNER backup response did not include backup data")
        val cardNonce = dataItem.bytes("card_nonce") ?: error("TAPSIGNER backup response did not include card nonce")
        if (data.isEmpty()) error("TAPSIGNER backup response returned empty backup data")
        if (cardNonce.size != 16) error("TAPSIGNER backup response had an invalid card nonce length")
        return TapsignerBackupResponse(data, cardNonce)
    }

    fun parseSatscardUnsealResponse(
        response: ByteArray,
        sessionKey: ByteArray,
        verifiedSlot: VerifiedSatscardSlot
    ): SatscardUnsealResult {
        val dataItem = responseMapOrThrow(response)
        val slot = dataItem.long("slot") ?: error("SATSCARD unseal response did not include a slot")
        if (slot != verifiedSlot.slot) error("SATSCARD unsealed a different slot than requested")
        val encryptedPrivkey = dataItem.bytes("privkey")
            ?: error("SATSCARD unseal response did not include a private key")
        if (encryptedPrivkey.size != 32) error("SATSCARD returned an invalid private key length")
        val privateKey = xorBytes(encryptedPrivkey, sessionKey.copyOfRange(0, encryptedPrivkey.size))
        val responsePubkey = dataItem.bytes("pubkey")
        responsePubkey?.let { if (it.size != 33) error("SATSCARD returned an invalid public key length") }
        CoinkiteTapCardVerifier.verifyUnsealedPrivateKey(privateKey, responsePubkey, verifiedSlot)
        val pubkeyHex = responsePubkey?.toHex()
        return SatscardUnsealResult(
            slot = slot,
            privateKey = privateKey,
            publicKeyHex = pubkeyHex,
            address = verifiedSlot.address,
            isTestnet = verifiedSlot.isTestnet,
            summary = "SATSCARD slot ${satscardDisplaySlot(slot)} unsealed"
        )
    }

    fun parseDumpResponse(response: ByteArray): SatscardSlotState {
        val dataItem = responseMapOrThrow(response)
        return SatscardSlotState(
            slot = dataItem.long("slot"),
            used = dataItem.boolean("used"),
            sealed = dataItem.boolean("sealed"),
            address = dataItem.string("addr"),
            pubkey = dataItem.bytes("pubkey"),
            cardNonce = dataItem.bytes("card_nonce")
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
        if (response.size < 2) error("Coinkite Tap card NFC response was too short")
        if (!isSuccessResponse(response)) {
            val sw = response.takeLast(2).joinToString("") { "%02X".format(it) }
            error("Coinkite Tap card NFC command failed with status word 0x$sw")
        }
        return responseBody(response)
    }

    private fun responseMapOrThrow(response: ByteArray): Map {
        val body = responseBodyOrThrow(response)
        if (body.isEmpty()) error("Coinkite Tap card returned success without a CBOR body")
        val dataItem = CborDecoder.decode(body).firstOrNull() as? Map
            ?: error("Coinkite Tap card response was not a CBOR map")
        dataItem.string("error")?.let { errorText ->
            throw CoinkiteTapCardException(dataItem.long("code"), errorText)
        }
        return dataItem
    }

    private fun cborMap(vararg entries: Pair<String, Any>): ByteArray {
        val map = CborBuilder().addMap()
        entries.forEach { (key, value) ->
            when (value) {
                is String -> map.put(key, value)
                is Long -> map.put(key, value)
                is Int -> map.put(key, value.toLong())
                is Boolean -> map.put(key, value)
                is ByteArray -> map.put(key, value)
                is List<*> -> {
                    val array = map.putArray(key)
                    value.forEach { item ->
                        when (item) {
                            is Long -> array.add(item)
                            is Int -> array.add(item.toLong())
                            else -> error("Unsupported CBOR array value for $key")
                        }
                    }
                    array.end()
                }
                else -> error("Unsupported CBOR value for $key")
            }
        }
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

    private fun Map.bytesArray(key: String): List<ByteArray>? {
        val array = value(key) as? Array ?: return null
        return array.dataItems.mapNotNull { (it as? ByteString)?.bytes }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class AuthenticatedCommand(
        val sessionKey: ByteArray,
        val ephemeralPublicKey: ByteArray,
        val encryptedCvc: ByteArray
    )

    private fun authenticatedCommand(
        commandName: String,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): AuthenticatedCommand {
        require(cvc.size in 6..32) { "Coinkite card PIN/spend code must be 6 to 32 characters" }
        require(cardPubkey.size == 33) { "Coinkite card pubkey was invalid" }
        require(cardNonce.size == 16) { "Coinkite card nonce was invalid" }

        val params = SECNamedCurves.getByName("secp256k1")
        val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
        val random = SecureRandom()
        val privateScalar = generatePrivateScalar(domain.n, random)
        val ephemeralPublicKey = domain.g.multiply(privateScalar).normalize().getEncoded(true)
        val sharedPoint = domain.curve.decodePoint(cardPubkey).multiply(privateScalar).normalize()
        if (sharedPoint.isInfinity) error("SATSCARD ECDH failed")
        val sessionKey = sha256(sharedPoint.getEncoded(true))
        val nonceDigest = sha256(cardNonce + commandName.toByteArray(Charsets.US_ASCII))
        val mask = xorBytes(sessionKey, nonceDigest)
        val cvcBytes = ByteArray(cvc.size) { index ->
            val code = cvc[index].code
            require(code in 0x21..0x7E) { "Coinkite card PIN/spend code must contain printable ASCII characters" }
            code.toByte()
        }
        val encryptedCvc = xorBytes(cvcBytes, mask.copyOfRange(0, cvcBytes.size))

        return AuthenticatedCommand(sessionKey, ephemeralPublicKey, encryptedCvc)
    }

    private fun generatePrivateScalar(n: BigInteger, random: SecureRandom): BigInteger {
        while (true) {
            val candidate = BigInteger(n.bitLength(), random)
            if (candidate > BigInteger.ZERO && candidate < n) return candidate
        }
    }

    private fun xorBytes(left: ByteArray, right: ByteArray): ByteArray {
        require(left.size == right.size) { "XOR inputs must have equal length" }
        return ByteArray(left.size) { index -> (left[index].toInt() xor right[index].toInt()).toByte() }
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }
}

data class SatscardSlotState(
    val slot: Long?,
    val used: Boolean?,
    val sealed: Boolean?,
    val address: String?,
    val pubkey: ByteArray?,
    val cardNonce: ByteArray?
)

internal fun satscardDisplaySlot(protocolSlot: Long): Long = protocolSlot + 1

object CoinkiteTapCardNfcReader {
    fun readStatus(tag: Tag): CoinkiteTapCardStatus {
        val isoDep = IsoDep.get(tag) ?: error("Coinkite Tap Protocol cards require ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 5000
            val selectResponse = isoDep.transceive(TapsignerTapProtocol.selectAppletCommand())
            if (!TapsignerTapProtocol.isSuccessResponse(selectResponse)) {
                val sw = selectResponse.takeLast(2).joinToString("") { "%02X".format(it) }
                error("Coinkite Tap card applet select failed with status word 0x$sw")
            }
            val selectBody = TapsignerTapProtocol.responseBody(selectResponse)
            val response = if (selectBody.isNotEmpty()) {
                selectResponse
            } else {
                isoDep.transceive(TapsignerTapProtocol.statusCommand())
            }
            val status = TapsignerTapProtocol.parseStatusResponse(response)
            if (status.kind == CoinkiteTapCardKind.UNKNOWN) {
                error("Coinkite NFC card is not reporting TAPSIGNER or SATSCARD mode")
            }
            return status
        } finally {
            isoDep.close()
        }
    }
}

object SatscardNfcReader {
    fun readCurrentSlot(tag: Tag, expectedTestnet: Boolean): SatscardFundingSlotResult {
        val isoDep = IsoDep.get(tag) ?: error("SATSCARD requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 10000
            var status = selectOrReadStatus(isoDep)
            validateSatscardStatus(status, expectedTestnet)
            if ((status.authDelaySeconds ?: 0L) > 0L) {
                clearCoinkiteAuthDelay(isoDep, status.authDelaySeconds!!, "SATSCARD")
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
                validateSatscardStatus(status, expectedTestnet)
            }
            return verifyActiveFundingSlot(isoDep, status, newlySetup = false)
        } finally {
            isoDep.close()
        }
    }

    fun setupCurrentSlot(tag: Tag, cvc: CharArray, expectedTestnet: Boolean): SatscardFundingSlotResult {
        val isoDep = IsoDep.get(tag) ?: error("SATSCARD requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 10000
            var status = selectOrReadStatus(isoDep)
            validateSatscardStatus(status, expectedTestnet)
            if ((status.authDelaySeconds ?: 0L) > 0L) {
                clearCoinkiteAuthDelay(isoDep, status.authDelaySeconds!!, "SATSCARD")
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
                validateSatscardStatus(status, expectedTestnet)
            }
            val slot = status.activeSlot
                ?: error("SATSCARD did not report an active slot")
            val displaySlot = satscardDisplaySlot(slot)
            if (!status.address.isNullOrBlank()) {
                error("SATSCARD slot $displaySlot is already set up. Read the active slot to fund its existing address.")
            }
            val cardPubkey = status.cardPubkeyHex?.hexToBytes()
                ?: error("SATSCARD status did not include card pubkey")
            var latestCardNonce = status.cardNonceHex?.hexToBytes()
                ?: error("SATSCARD status did not include card nonce")

            val dump = TapsignerTapProtocol.parseDumpResponse(
                isoDep.transceive(TapsignerTapProtocol.dumpCommand(slot))
            )
            if (dump.slot != null && dump.slot != slot) error("SATSCARD dump response returned a different slot")
            if (dump.used == true || !dump.address.isNullOrBlank()) {
                error("SATSCARD slot $displaySlot is already set up. Read the active slot to fund its existing address.")
            }
            latestCardNonce = dump.cardNonce ?: latestCardNonce

            val certs = TapsignerTapProtocol.parseCertsResponse(
                isoDep.transceive(TapsignerTapProtocol.certsCommand())
            )
            val checkNonce = randomNonce()
            val check = TapsignerTapProtocol.parseCheckResponse(
                isoDep.transceive(TapsignerTapProtocol.checkCommand(checkNonce))
            )
            CoinkiteTapCardVerifier.verifyCertificateChain(
                cardPubkey = cardPubkey,
                cardNonce = latestCardNonce,
                checkNonce = checkNonce,
                authSignature = check.authSignature,
                certChain = certs.certChain,
                sealedSlotPubkey = null,
                cardVersion = status.version
            )
            latestCardNonce = check.cardNonce

            val chainCode = randomChainCode()
            try {
                val newResult = TapsignerTapProtocol.parseTapsignerNewResponse(
                    isoDep.transceive(
                        TapsignerTapProtocol.authenticatedNewSatscardCommand(
                            slot = slot,
                            cardPubkey = cardPubkey,
                            cardNonce = latestCardNonce,
                            cvc = cvc,
                            chainCode = chainCode
                        )
                    )
                )
                if (newResult.slot != slot) error("SATSCARD set up a different slot than requested")
            } finally {
                chainCode.fill(0)
            }

            val refreshedStatus = TapsignerTapProtocol.parseStatusResponse(
                isoDep.transceive(TapsignerTapProtocol.statusCommand())
            )
            validateSatscardStatus(refreshedStatus, expectedTestnet)
            return verifyActiveFundingSlot(isoDep, refreshedStatus, newlySetup = true)
        } finally {
            isoDep.close()
            cvc.fill('0')
        }
    }

    fun unsealCurrentSlot(tag: Tag, cvc: CharArray, expectedTestnet: Boolean): SatscardUnsealResult {
        val isoDep = IsoDep.get(tag) ?: error("SATSCARD requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 10000
            val selectResponse = isoDep.transceive(TapsignerTapProtocol.selectAppletCommand())
            if (!TapsignerTapProtocol.isSuccessResponse(selectResponse)) {
                val sw = selectResponse.takeLast(2).joinToString("") { "%02X".format(it) }
                error("Coinkite Tap card applet select failed with status word 0x$sw")
            }
            val statusResponse = if (TapsignerTapProtocol.responseBody(selectResponse).isNotEmpty()) {
                selectResponse
            } else {
                isoDep.transceive(TapsignerTapProtocol.statusCommand())
            }
            var status = TapsignerTapProtocol.parseStatusResponse(statusResponse)
            if (!status.isSatscard) error("Coinkite NFC card is not reporting SATSCARD mode")
            if (status.isTampered == true) error("SATSCARD tamper warning is set")
            val cardIsTestnet = status.isTestnet == true
            if (cardIsTestnet != expectedTestnet) {
                val cardNetwork = if (cardIsTestnet) "testnet" else "mainnet"
                val walletNetwork = if (expectedTestnet) "testnet" else "mainnet"
                error("SATSCARD is $cardNetwork but this wallet is $walletNetwork")
            }
            if ((status.authDelaySeconds ?: 0L) > 0L) {
                clearCoinkiteAuthDelay(isoDep, status.authDelaySeconds!!, "SATSCARD")
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
                if (!status.isSatscard) error("Coinkite NFC card is not reporting SATSCARD mode")
                if (status.isTampered == true) error("SATSCARD tamper warning is set")
            }
            val slot = status.activeSlot
                ?: error("SATSCARD did not report an active slot")
            val displaySlot = satscardDisplaySlot(slot)
            val cardPubkey = status.cardPubkeyHex?.hexToBytes()
                ?: error("SATSCARD status did not include card pubkey")
            var latestCardNonce = status.cardNonceHex?.hexToBytes()
                ?: error("SATSCARD status did not include card nonce")

            val dump = TapsignerTapProtocol.parseDumpResponse(
                isoDep.transceive(TapsignerTapProtocol.dumpCommand(slot))
            )
            if (dump.slot != null && dump.slot != slot) error("SATSCARD dump response returned a different slot")
            if (dump.used == false) error("SATSCARD slot $displaySlot has not been used yet")
            if (dump.sealed == false) error("SATSCARD slot $displaySlot is already unsealed")
            latestCardNonce = dump.cardNonce ?: error("SATSCARD dump response did not include card nonce")

            val readNonce = randomNonce()
            val read = TapsignerTapProtocol.parseReadResponse(
                isoDep.transceive(TapsignerTapProtocol.readCommand(readNonce))
            )
            val verifiedSlot = CoinkiteTapCardVerifier.verifySatscardRead(
                status = status,
                cardNonce = latestCardNonce,
                readNonce = readNonce,
                read = read
            )
            latestCardNonce = read.cardNonce

            val certs = TapsignerTapProtocol.parseCertsResponse(
                isoDep.transceive(TapsignerTapProtocol.certsCommand())
            )
            val checkNonce = randomNonce()
            val check = TapsignerTapProtocol.parseCheckResponse(
                isoDep.transceive(TapsignerTapProtocol.checkCommand(checkNonce))
            )
            CoinkiteTapCardVerifier.verifyCertificateChain(
                cardPubkey = cardPubkey,
                cardNonce = latestCardNonce,
                checkNonce = checkNonce,
                authSignature = check.authSignature,
                certChain = certs.certChain,
                sealedSlotPubkey = verifiedSlot.pubkey,
                cardVersion = status.version
            )
            latestCardNonce = check.cardNonce

            val (command, sessionKey) = TapsignerTapProtocol.authenticatedUnsealCommand(
                slot = slot,
                cardPubkey = cardPubkey,
                cardNonce = latestCardNonce,
                cvc = cvc
            )
            return TapsignerTapProtocol.parseSatscardUnsealResponse(
                response = isoDep.transceive(command),
                sessionKey = sessionKey,
                verifiedSlot = verifiedSlot
            )
        } finally {
            isoDep.close()
            cvc.fill('0')
        }
    }

    private fun randomNonce(): ByteArray {
        return ByteArray(16).also { SecureRandom().nextBytes(it) }
    }

    private fun randomChainCode(): ByteArray {
        return ByteArray(32).also { SecureRandom().nextBytes(it) }
    }

    private fun selectOrReadStatus(isoDep: IsoDep): CoinkiteTapCardStatus {
        val selectResponse = isoDep.transceive(TapsignerTapProtocol.selectAppletCommand())
        if (!TapsignerTapProtocol.isSuccessResponse(selectResponse)) {
            val sw = selectResponse.takeLast(2).joinToString("") { "%02X".format(it) }
            error("Coinkite Tap card applet select failed with status word 0x$sw")
        }
        val statusResponse = if (TapsignerTapProtocol.responseBody(selectResponse).isNotEmpty()) {
            selectResponse
        } else {
            isoDep.transceive(TapsignerTapProtocol.statusCommand())
        }
        return TapsignerTapProtocol.parseStatusResponse(statusResponse)
    }

    private fun validateSatscardStatus(status: CoinkiteTapCardStatus, expectedTestnet: Boolean) {
        if (!status.isSatscard) error("Coinkite NFC card is not reporting SATSCARD mode")
        if (status.isTampered == true) error("SATSCARD tamper warning is set")
        val cardIsTestnet = status.isTestnet == true
        if (cardIsTestnet != expectedTestnet) {
            val cardNetwork = if (cardIsTestnet) "testnet" else "mainnet"
            val walletNetwork = if (expectedTestnet) "testnet" else "mainnet"
            error("SATSCARD is $cardNetwork but this wallet is $walletNetwork")
        }
    }

    private fun verifyActiveFundingSlot(
        isoDep: IsoDep,
        status: CoinkiteTapCardStatus,
        newlySetup: Boolean
    ): SatscardFundingSlotResult {
        val slot = status.activeSlot
            ?: error("SATSCARD did not report an active slot")
        val displaySlot = satscardDisplaySlot(slot)
        val cardPubkey = status.cardPubkeyHex?.hexToBytes()
            ?: error("SATSCARD status did not include card pubkey")
        var latestCardNonce = status.cardNonceHex?.hexToBytes()
            ?: error("SATSCARD status did not include card nonce")

        val dump = TapsignerTapProtocol.parseDumpResponse(
            isoDep.transceive(TapsignerTapProtocol.dumpCommand(slot))
        )
        if (dump.slot != null && dump.slot != slot) error("SATSCARD dump response returned a different slot")
        if (dump.used == false && !newlySetup) {
            error("SATSCARD active slot $displaySlot has not been set up yet. Enter the spend code and set up the slot before funding it.")
        }
        if (dump.sealed == false) error("SATSCARD active slot $displaySlot is already unsealed")
        latestCardNonce = dump.cardNonce ?: latestCardNonce

        val statusForAddress = status.copy(address = status.address ?: dump.address)
        if (statusForAddress.address.isNullOrBlank()) {
            error("SATSCARD active slot $displaySlot did not report a deposit address")
        }
        val readNonce = randomNonce()
        val read = TapsignerTapProtocol.parseReadResponse(
            isoDep.transceive(TapsignerTapProtocol.readCommand(readNonce))
        )
        val verifiedSlot = CoinkiteTapCardVerifier.verifySatscardRead(
            status = statusForAddress,
            cardNonce = latestCardNonce,
            readNonce = readNonce,
            read = read
        )
        latestCardNonce = read.cardNonce

        val certs = TapsignerTapProtocol.parseCertsResponse(
            isoDep.transceive(TapsignerTapProtocol.certsCommand())
        )
        val checkNonce = randomNonce()
        val check = TapsignerTapProtocol.parseCheckResponse(
            isoDep.transceive(TapsignerTapProtocol.checkCommand(checkNonce))
        )
        CoinkiteTapCardVerifier.verifyCertificateChain(
            cardPubkey = cardPubkey,
            cardNonce = latestCardNonce,
            checkNonce = checkNonce,
            authSignature = check.authSignature,
            certChain = certs.certChain,
            sealedSlotPubkey = verifiedSlot.pubkey,
            cardVersion = status.version
        )

        return SatscardFundingSlotResult(
            slot = verifiedSlot.slot,
            address = verifiedSlot.address,
            isTestnet = verifiedSlot.isTestnet,
            newlySetup = newlySetup,
            summary = if (newlySetup) {
                "SATSCARD slot ${satscardDisplaySlot(verifiedSlot.slot)} is set up and ready to fund"
            } else {
                "SATSCARD slot ${satscardDisplaySlot(verifiedSlot.slot)} address verified"
            }
        )
    }
}

object TapsignerNfcReader {
    private const val HARDENED_FLAG = 0x80000000L

    fun readStatus(tag: Tag): CoinkiteTapCardStatus {
        val status = CoinkiteTapCardNfcReader.readStatus(tag)
        if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
        return status
    }

    fun readAccountXpub(tag: Tag, cvc: CharArray): TapsignerAccountXpubResult {
        val isoDep = IsoDep.get(tag) ?: error("TAPSIGNER requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 10000
            var status = selectOrReadStatus(isoDep)
            if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
            if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
            if (status.derivationPath == null) {
                error("This TAPSIGNER has not been set up yet. Initialize it with a TAPSIGNER-compatible wallet first, then return to Clench to import its xpub.")
            }
            if ((status.authDelaySeconds ?: 0L) > 0L) {
                clearCoinkiteAuthDelay(isoDep, status.authDelaySeconds!!, "TAPSIGNER")
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
                if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
                if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
                if (status.derivationPath == null) {
                    error("This TAPSIGNER has not been set up yet. Initialize it with a TAPSIGNER-compatible wallet first, then return to Clench to import its xpub.")
                }
            }
            val targetPath = singleSigAccountPath(status.isTestnet == true)
            val targetPathDisplay = formatDerivationPath(targetPath)
            val cardPubkey = verifiedCardPubkey(status)
            var latestCardNonce = verifyTapsignerCard(isoDep, status, cardPubkey)
            if (status.derivationPath != targetPath) {
                val deriveNonce = randomNonce()
                val derive = TapsignerTapProtocol.parseTapsignerDeriveResponse(
                    isoDep.transceive(
                        TapsignerTapProtocol.authenticatedDeriveCommand(
                            path = targetPath,
                            nonce = deriveNonce,
                            cardPubkey = cardPubkey,
                            cardNonce = latestCardNonce,
                            cvc = cvc
                        )
                    )
                )
                latestCardNonce = derive.cardNonce
            }
            return readVerifiedAccountXpub(
                isoDep = isoDep,
                cardPubkey = cardPubkey,
                initialCardNonce = latestCardNonce,
                cvc = cvc,
                reportedPath = targetPathDisplay,
                summaryPrefix = "TAPSIGNER single-sig xpub imported"
            )
        } finally {
            isoDep.close()
            cvc.fill('0')
        }
    }

    fun readMultisigAccountXpub(
        tag: Tag,
        cvc: CharArray,
        isTestnet: Boolean,
        setPathIfNeeded: Boolean,
        initializeIfNeeded: Boolean
    ): TapsignerAccountXpubResult {
        val targetPath = multisigAccountPath(isTestnet)
        val targetPathDisplay = formatDerivationPath(targetPath)
        val isoDep = IsoDep.get(tag) ?: error("TAPSIGNER requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 10000
            var status = selectOrReadStatus(isoDep)
            if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
            if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
            if ((status.authDelaySeconds ?: 0L) > 0L) {
                clearCoinkiteAuthDelay(isoDep, status.authDelaySeconds!!, "TAPSIGNER")
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
                if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
                if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
            }

            val cardPubkey = verifiedCardPubkey(status)
            var latestCardNonce = verifyTapsignerCard(isoDep, status, cardPubkey)

            if (status.derivationPath == null) {
                if (!initializeIfNeeded) {
                    error("This TAPSIGNER has not been set up yet. Use Set up as multisig cosigner to initialize it at $targetPathDisplay.")
                }
                val chainCode = randomChainCode()
                val newResult = try {
                    TapsignerTapProtocol.parseTapsignerNewResponse(
                        isoDep.transceive(
                            TapsignerTapProtocol.authenticatedNewTapsignerCommand(
                                cardPubkey = cardPubkey,
                                cardNonce = latestCardNonce,
                                cvc = cvc,
                                chainCode = chainCode
                            )
                        )
                    )
                } finally {
                    chainCode.fill(0)
                }
                if (newResult.slot != 0L) error("TAPSIGNER initialized an unexpected slot")
                latestCardNonce = newResult.cardNonce
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
            }

            if (status.derivationPath != targetPath) {
                val currentPath = status.displayPath ?: "unknown"
                if (!setPathIfNeeded) {
                    error("This TAPSIGNER is currently set to $currentPath. Multisig import needs $targetPathDisplay. Use Set up as multisig cosigner if you want Clench to set that path.")
                }
                val deriveNonce = randomNonce()
                val derive = TapsignerTapProtocol.parseTapsignerDeriveResponse(
                    isoDep.transceive(
                        TapsignerTapProtocol.authenticatedDeriveCommand(
                            path = targetPath,
                            nonce = deriveNonce,
                            cardPubkey = cardPubkey,
                            cardNonce = latestCardNonce,
                            cvc = cvc
                        )
                    )
                )
                latestCardNonce = derive.cardNonce
            }

            return readVerifiedAccountXpub(
                isoDep = isoDep,
                cardPubkey = cardPubkey,
                initialCardNonce = latestCardNonce,
                cvc = cvc,
                reportedPath = targetPathDisplay,
                summaryPrefix = "TAPSIGNER multisig cosigner imported"
            )
        } finally {
            isoDep.close()
            cvc.fill('0')
        }
    }

    fun initializeAndReadAccountXpub(tag: Tag, cvc: CharArray): TapsignerAccountXpubResult {
        val isoDep = IsoDep.get(tag) ?: error("TAPSIGNER requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 10000
            var status = selectOrReadStatus(isoDep)
            if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
            if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
            if (status.derivationPath != null) {
                error("This TAPSIGNER is already initialized. Use NFC import instead.")
            }
            if ((status.authDelaySeconds ?: 0L) > 0L) {
                clearCoinkiteAuthDelay(isoDep, status.authDelaySeconds!!, "TAPSIGNER")
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
                if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
                if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
                if (status.derivationPath != null) {
                    error("This TAPSIGNER is already initialized. Use NFC import instead.")
                }
            }
            val cardPubkey = verifiedCardPubkey(status)
            val verifiedCardNonce = verifyTapsignerCard(isoDep, status, cardPubkey)
            val chainCode = randomChainCode()
            val newResult = try {
                TapsignerTapProtocol.parseTapsignerNewResponse(
                    isoDep.transceive(
                        TapsignerTapProtocol.authenticatedNewTapsignerCommand(
                            cardPubkey = cardPubkey,
                            cardNonce = verifiedCardNonce,
                            cvc = cvc,
                            chainCode = chainCode
                        )
                    )
                )
            } finally {
                chainCode.fill(0)
            }
            if (newResult.slot != 0L) error("TAPSIGNER initialized an unexpected slot")
            val targetPath = singleSigAccountPath(status.isTestnet == true)
            val targetPathDisplay = formatDerivationPath(targetPath)
            val deriveNonce = randomNonce()
            val derive = TapsignerTapProtocol.parseTapsignerDeriveResponse(
                isoDep.transceive(
                    TapsignerTapProtocol.authenticatedDeriveCommand(
                        path = targetPath,
                        nonce = deriveNonce,
                        cardPubkey = cardPubkey,
                        cardNonce = newResult.cardNonce,
                        cvc = cvc
                    )
                )
            )
            return readVerifiedAccountXpub(
                isoDep = isoDep,
                cardPubkey = cardPubkey,
                initialCardNonce = derive.cardNonce,
                cvc = cvc,
                reportedPath = targetPathDisplay,
                summaryPrefix = "TAPSIGNER initialized and xpub imported"
            )
        } finally {
            isoDep.close()
            cvc.fill('0')
        }
    }

    fun createBackup(tag: Tag, cvc: CharArray): TapsignerBackupResult {
        val isoDep = IsoDep.get(tag) ?: error("TAPSIGNER requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 10000
            var status = selectOrReadStatus(isoDep)
            if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
            if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
            if (status.derivationPath == null) {
                error("Initialize this TAPSIGNER before creating a backup.")
            }
            if ((status.authDelaySeconds ?: 0L) > 0L) {
                clearCoinkiteAuthDelay(isoDep, status.authDelaySeconds!!, "TAPSIGNER")
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
                if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
                if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
                if (status.derivationPath == null) {
                    error("Initialize this TAPSIGNER before creating a backup.")
                }
            }
            val cardPubkey = verifiedCardPubkey(status)
            val verifiedCardNonce = verifyTapsignerCard(isoDep, status, cardPubkey)
            val backup = try {
                TapsignerTapProtocol.parseTapsignerBackupResponse(
                    isoDep.transceive(
                        TapsignerTapProtocol.authenticatedBackupCommand(
                            cardPubkey = cardPubkey,
                            cardNonce = verifiedCardNonce,
                            cvc = cvc
                        )
                    )
                )
            } catch (e: CoinkiteTapCardException) {
                if (e.code == 406L) {
                    error("This TAPSIGNER is not initialized yet, so it cannot create a backup.")
                }
                throw e
            }
            status = TapsignerTapProtocol.parseStatusResponse(
                isoDep.transceive(TapsignerTapProtocol.statusCommand())
            )
            val count = status.numberOfBackups
            val countText = count?.let { "$it backup${if (it == 1L) "" else "s"} recorded" }
                ?: "backup count unavailable"
            return TapsignerBackupResult(
                data = backup.data,
                numberOfBackups = count,
                summary = "TAPSIGNER encrypted backup created; $countText"
            )
        } finally {
            isoDep.close()
            cvc.fill('0')
        }
    }

    private fun readVerifiedAccountXpub(
        isoDep: IsoDep,
        cardPubkey: ByteArray,
        initialCardNonce: ByteArray,
        cvc: CharArray,
        reportedPath: String?,
        summaryPrefix: String
    ): TapsignerAccountXpubResult {
        var latestCardNonce = initialCardNonce
            val master = try {
                TapsignerTapProtocol.parseTapsignerXpubResponse(
                    isoDep.transceive(
                        TapsignerTapProtocol.authenticatedXpubCommand(
                            master = true,
                            cardPubkey = cardPubkey,
                            cardNonce = latestCardNonce,
                            cvc = cvc
                        )
                    )
                )
            } catch (e: CoinkiteTapCardException) {
                if (e.code == 406L) {
                    error("This TAPSIGNER is not ready to export an xpub. It may not be initialized yet; set it up with a TAPSIGNER-compatible wallet first.")
                }
                throw e
            }
            val masterPubkey = master.xpub.copyOfRange(45, 78)
            val fingerprint = CoinkiteTapCardVerifier.fingerprintHexFromPublicKey(masterPubkey)
            latestCardNonce = master.cardNonce ?: readStatusNonce(isoDep)

            val account = try {
                TapsignerTapProtocol.parseTapsignerXpubResponse(
                    isoDep.transceive(
                        TapsignerTapProtocol.authenticatedXpubCommand(
                            master = false,
                            cardPubkey = cardPubkey,
                            cardNonce = latestCardNonce,
                            cvc = cvc
                        )
                    )
                )
            } catch (e: CoinkiteTapCardException) {
                if (e.code == 406L) {
                    error("This TAPSIGNER is not ready to export an account xpub. Confirm it has been initialized and has a derivation path set.")
                }
                throw e
            }
            val xpub = account.xpub.base58CheckEncode()
            val status = selectOrReadStatus(isoDep)
            val refreshedPath = status.displayPath ?: reportedPath ?: status.defaultTapsignerAccountPath
            val originPath = refreshedPath.removePrefix("m/")
            val originWrapped = "[$fingerprint/$originPath]$xpub"
            return TapsignerAccountXpubResult(
                xpub = xpub,
                originWrappedXpub = originWrapped,
                masterFingerprint = fingerprint,
                derivationPath = refreshedPath,
                summary = "$summaryPrefix: path $refreshedPath, fingerprint ${fingerprint.uppercase(Locale.US)}"
            )
    }

    private fun verifiedCardPubkey(status: CoinkiteTapCardStatus): ByteArray {
        return status.cardPubkeyHex?.hexToBytes() ?: error("TAPSIGNER status did not include card pubkey")
    }

    private fun verifyTapsignerCard(
        isoDep: IsoDep,
        status: CoinkiteTapCardStatus,
        cardPubkey: ByteArray
    ): ByteArray {
        val cardNonce = status.cardNonceHex?.hexToBytes() ?: error("TAPSIGNER status did not include card nonce")
        val certs = TapsignerTapProtocol.parseCertsResponse(
            isoDep.transceive(TapsignerTapProtocol.certsCommand())
        )
        val checkNonce = randomNonce()
        val check = TapsignerTapProtocol.parseCheckResponse(
            isoDep.transceive(TapsignerTapProtocol.checkCommand(checkNonce))
        )
        CoinkiteTapCardVerifier.verifyCertificateChain(
            cardPubkey = cardPubkey,
            cardNonce = cardNonce,
            checkNonce = checkNonce,
            authSignature = check.authSignature,
            certChain = certs.certChain,
            sealedSlotPubkey = null,
            cardVersion = status.version
        )
        return check.cardNonce
    }

    private fun selectOrReadStatus(isoDep: IsoDep): CoinkiteTapCardStatus {
        val selectResponse = isoDep.transceive(TapsignerTapProtocol.selectAppletCommand())
        if (!TapsignerTapProtocol.isSuccessResponse(selectResponse)) {
            val sw = selectResponse.takeLast(2).joinToString("") { "%02X".format(it) }
            error("Coinkite Tap card applet select failed with status word 0x$sw")
        }
        val statusResponse = if (TapsignerTapProtocol.responseBody(selectResponse).isNotEmpty()) {
            selectResponse
        } else {
            isoDep.transceive(TapsignerTapProtocol.statusCommand())
        }
        return TapsignerTapProtocol.parseStatusResponse(statusResponse)
    }

    private fun readStatusNonce(isoDep: IsoDep): ByteArray {
        val status = TapsignerTapProtocol.parseStatusResponse(
            isoDep.transceive(TapsignerTapProtocol.statusCommand())
        )
        return status.cardNonceHex?.hexToBytes() ?: error("TAPSIGNER status did not include card nonce")
    }

    private fun randomNonce(): ByteArray {
        return ByteArray(16).also { SecureRandom().nextBytes(it) }
    }

    private fun randomChainCode(): ByteArray {
        return ByteArray(32).also { SecureRandom().nextBytes(it) }
    }

    internal fun singleSigAccountPath(isTestnet: Boolean): List<Long> {
        return listOf(84L, if (isTestnet) 1L else 0L, 0L).map { it or HARDENED_FLAG }
    }

    internal fun multisigAccountPath(isTestnet: Boolean): List<Long> {
        return listOf(48L, if (isTestnet) 1L else 0L, 0L, 2L).map { it or HARDENED_FLAG }
    }

    internal fun formatDerivationPath(path: List<Long>): String {
        return if (path.isEmpty()) {
            "m"
        } else {
            path.joinToString(separator = "/", prefix = "m/") { value ->
                val hardened = value and HARDENED_FLAG != 0L
                val index = if (hardened) value and HARDENED_FLAG.inv() else value
                if (hardened) "$index'" else index.toString()
            }
        }
    }

    private fun ByteArray.base58CheckEncode(): String {
        val checksum = doubleSha256(this).copyOfRange(0, 4)
        return (this + checksum).base58Encode()
    }

    private fun ByteArray.base58Encode(): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var value = BigInteger(1, this)
        val base = BigInteger.valueOf(58)
        val output = StringBuilder()
        while (value > BigInteger.ZERO) {
            val parts = value.divideAndRemainder(base)
            output.append(alphabet[parts[1].toInt()])
            value = parts[0]
        }
        for (byte in this) {
            if (byte == 0.toByte()) output.append('1') else break
        }
        return output.reverse().toString()
    }

    private fun doubleSha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(data))
    }
}

private fun clearCoinkiteAuthDelay(isoDep: IsoDep, initialDelaySeconds: Long, cardName: String) {
    var remaining = initialDelaySeconds
    if (remaining <= 0L) return
    if (remaining > 60L) {
        error("$cardName requires a ${remaining}s retry delay before another code attempt")
    }

    var attempts = 0
    while (remaining > 0L) {
        attempts += 1
        if (attempts > 65) error("$cardName retry delay did not clear")
        val wait = TapsignerTapProtocol.parseWaitResponse(
            isoDep.transceive(TapsignerTapProtocol.waitCommand())
        )
        remaining = wait.authDelaySeconds ?: (remaining - 1L)
    }
}
