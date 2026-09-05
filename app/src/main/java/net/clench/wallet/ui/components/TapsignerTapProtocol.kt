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
import co.nstant.`in`.cbor.model.Special
import co.nstant.`in`.cbor.model.UnicodeString
import java.io.ByteArrayInputStream
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
    val signature: ByteArray,
    val chainCode: ByteArray,
    val masterPubkey: ByteArray,
    val pubkey: ByteArray,
    val cardNonce: ByteArray
)

data class TapsignerSignProof(
    val slot: Long,
    val signature: ByteArray,
    val pubkey: ByteArray,
    val cardNonce: ByteArray
)

data class TapsignerBackupResponse(
    val data: ByteArray,
    val cardNonce: ByteArray
)

data class TapsignerAccountXpubResult(
    val xpub: String,
    val cardReturnedXpub: String,
    val originWrappedXpub: String,
    val masterFingerprint: String,
    val derivationPath: String,
    val isTestnet: Boolean,
    val summary: String
)

data class TapsignerBackupResult(
    val data: ByteArray,
    val numberOfBackups: Long?,
    val summary: String
)

data class TapsignerPsbtSignResult(
    val signedPsbtBase64: String,
    val signedInputCount: Int,
    val summary: String
)

object TapsignerTapProtocol {
    private const val HARDENED_FLAG = 0x80000000L
    internal const val MAX_RESPONSE_BYTES = 32 * 1024
    private const val MAX_CERT_CHAIN_ITEMS = 8
    private const val MAX_ERROR_CHARS = 512
    private const val MAX_CBOR_DEPTH = 16
    private const val MAX_CBOR_CONTAINER_ITEMS = 1_024

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

    fun readCommand(nonce: ByteArray): ByteArray {
        require(nonce.size == 16) { "SATSCARD read nonce must be 16 bytes" }
        return apdu(
            cla = 0x00,
            ins = 0xCB,
            p1 = 0x00,
            p2 = 0x00,
            data = cborMap(
                "cmd" to "read",
                "nonce" to nonce
            )
        )
    }

    fun certsCommand(): ByteArray = apdu(
        cla = 0x00,
        ins = 0xCB,
        p1 = 0x00,
        p2 = 0x00,
        data = cborMap("cmd" to "certs")
    )

    fun checkCommand(nonce: ByteArray): ByteArray {
        require(nonce.size == 16) { "Coinkite check nonce must be 16 bytes" }
        return apdu(
            cla = 0x00,
            ins = 0xCB,
            p1 = 0x00,
            p2 = 0x00,
            data = cborMap(
                "cmd" to "check",
                "nonce" to nonce
            )
        )
    }

    fun waitCommand(): ByteArray = apdu(
        cla = 0x00,
        ins = 0xCB,
        p1 = 0x00,
        p2 = 0x00,
        data = cborMap("cmd" to "wait")
    )

    fun dumpCommand(slot: Long): ByteArray {
        require(slot in 0..9) { "SATSCARD dump slot must be between 0 and 9" }
        return apdu(
            cla = 0x00,
            ins = 0xCB,
            p1 = 0x00,
            p2 = 0x00,
            data = cborMap(
                "cmd" to "dump",
                "slot" to slot
            )
        )
    }

    fun authenticatedUnsealCommand(
        slot: Long,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): Pair<ByteArray, ByteArray> {
        val auth = authenticatedCommand("unseal", cardPubkey, cardNonce, cvc)
        val command = try {
            apdu(
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
        } finally {
            auth.ephemeralPublicKey.fill(0)
            auth.encryptedCvc.fill(0)
        }
        return command to auth.sessionKey
    }

    fun authenticatedXpubCommand(
        master: Boolean,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): ByteArray {
        val auth = authenticatedCommand("xpub", cardPubkey, cardNonce, cvc)
        return try {
            apdu(
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
        } finally {
            auth.clear()
        }
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
        require(slot in 0..9) { "SATSCARD slot must be between 0 and 9" }
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
        return try {
            apdu(
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
        } finally {
            auth.clear()
        }
    }

    fun authenticatedDeriveCommand(
        path: List<Long>,
        nonce: ByteArray,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): ByteArray {
        require(path.size <= 8) { "TAPSIGNER derivation path is too deep" }
        require(path.all { it in HARDENED_FLAG..0xffff_ffffL }) {
            "TAPSIGNER derive supports 32-bit hardened path components only"
        }
        require(nonce.size == 16) { "TAPSIGNER derive nonce must be 16 bytes" }
        val auth = authenticatedCommand("derive", cardPubkey, cardNonce, cvc)
        return try {
            apdu(
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
        } finally {
            auth.clear()
        }
    }

    /**
     * Ask the current TAPSIGNER key to sign an app-secret challenge. The
     * challenge is XOR-encrypted with this command's fresh ECDH session key,
     * as required by Coinkite's `sign` protocol, before it enters the APDU.
     * The ECDH and encrypted-CVC working material is destroyed before return.
     */
    fun authenticatedTapsignerProofCommand(
        challenge: ByteArray,
        subpath: List<Long> = emptyList(),
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): ByteArray = authenticatedTapsignerSignCommand(
        digest = challenge,
        subpath = subpath,
        cardPubkey = cardPubkey,
        cardNonce = cardNonce,
        cvc = cvc
    )

    /**
     * Build the authenticated Tap Protocol `sign` request for one exact
     * Bitcoin sighash. The digest is encrypted with the fresh ECDH session
     * key; the returned card signature is over the original digest directly.
     */
    fun authenticatedTapsignerSignCommand(
        digest: ByteArray,
        subpath: List<Long>,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): ByteArray {
        require(digest.size == 32) { "TAPSIGNER signing digest must be 32 bytes" }
        require(subpath.size <= 2) { "TAPSIGNER signing subpath can contain at most two components" }
        require(subpath.all { it in 0 until HARDENED_FLAG }) {
            "TAPSIGNER signing subpath must be unhardened"
        }
        val auth = authenticatedCommand("sign", cardPubkey, cardNonce, cvc)
        val encryptedDigest = xorBytes(digest, auth.sessionKey)
        return try {
            apdu(
                cla = 0x00,
                ins = 0xCB,
                p1 = 0x00,
                p2 = 0x00,
                data = cborMap(
                    "cmd" to "sign",
                    "slot" to 0L,
                    "subpath" to subpath,
                    "digest" to encryptedDigest,
                    "epubkey" to auth.ephemeralPublicKey,
                    "xcvc" to auth.encryptedCvc
                )
            )
        } finally {
            encryptedDigest.fill(0)
            auth.clear()
        }
    }

    fun authenticatedBackupCommand(
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray
    ): ByteArray {
        val auth = authenticatedCommand("backup", cardPubkey, cardNonce, cvc)
        return try {
            apdu(
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
        } finally {
            auth.clear()
        }
    }

    fun parseStatusResponse(response: ByteArray): CoinkiteTapCardStatus {
        val body = responseBodyOrThrow(response)
        if (body.isEmpty()) error("Coinkite Tap card returned success without a CBOR status body")
        val dataItem = decodeSingleResponseMap(body)
        dataItem.string("error")?.let { errorText ->
            if (errorText.length > MAX_ERROR_CHARS) {
                error("Coinkite Tap card returned an excessive error message")
            }
            throw CoinkiteTapCardException(dataItem.long("code"), errorText)
        }
        val path = dataItem.longArray("path")
        val slots = dataItem.longArray("slots")
        val cardPubkey = dataItem.bytes("pubkey")
        val cardNonce = dataItem.bytes("card_nonce")
        val version = dataItem.string("ver")
        val address = dataItem.string("addr")
        val birthHeight = dataItem.long("birth")
        val numberOfBackups = dataItem.long("num_backups")
        val authDelaySeconds = dataItem.long("auth_delay")
        val isTapsigner = dataItem.boolean("tapsigner") == true
        if (path != null && (path.size > 8 || path.any { it !in 0..0xffff_ffffL })) {
            error("Coinkite Tap card returned an invalid derivation path")
        }
        if (slots != null && (
                slots.size != 2 ||
                    slots[0] !in 0..9 ||
                    slots[1] !in 1..10 ||
                    slots[0] >= slots[1]
                )
        ) {
            error("Coinkite Tap card returned invalid slot metadata")
        }
        if (cardPubkey != null && cardPubkey.size != 33) error("Coinkite Tap card returned an invalid public key length")
        if (cardNonce != null && cardNonce.size != 16) error("Coinkite Tap card returned an invalid card nonce length")
        if (version != null && version.length > 64) error("Coinkite Tap card returned an excessive version string")
        if (address != null && address.length > 128) error("Coinkite Tap card returned an excessive address string")
        if (birthHeight != null && birthHeight !in 0..10_000_000) {
            error("Coinkite Tap card returned an invalid birth height")
        }
        if (numberOfBackups != null && numberOfBackups !in 0..127) {
            error("Coinkite Tap card returned an invalid backup count")
        }
        if (authDelaySeconds != null && authDelaySeconds !in 0..86_400) {
            error("Coinkite Tap card returned an invalid authentication delay")
        }
        val reportsSatscard = address != null || slots != null
        if (isTapsigner && reportsSatscard) {
            error("Coinkite Tap card returned conflicting TAPSIGNER/SATSCARD status")
        }
        if (!isTapsigner && reportsSatscard && (path != null || numberOfBackups != null)) {
            error("Coinkite Tap card returned conflicting SATSCARD/TAPSIGNER metadata")
        }
        if ((isTapsigner || reportsSatscard) && (cardPubkey == null || cardNonce == null)) {
            error("Coinkite Tap card status omitted its identity key or nonce")
        }
        return CoinkiteTapCardStatus(
            isTapsigner = isTapsigner,
            version = version,
            birthHeight = birthHeight,
            derivationPath = path,
            numberOfBackups = numberOfBackups,
            authDelaySeconds = authDelaySeconds,
            cardPubkeyHex = cardPubkey?.toHex(),
            cardNonceHex = cardNonce?.toHex(),
            address = address,
            slots = slots,
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
        if (certChain.isEmpty() || certChain.size > MAX_CERT_CHAIN_ITEMS) {
            error("Coinkite cert chain contained an invalid number of entries")
        }
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
        val delay = dataItem.long("auth_delay")
        if (delay != null && delay !in 0..86_400) {
            error("Coinkite Tap card wait response contained an invalid authentication delay")
        }
        return CoinkiteWaitResult(authDelaySeconds = delay)
    }

    fun parseTapsignerXpubResponse(response: ByteArray): TapsignerXpubResponse {
        val dataItem = responseMapOrThrow(response)
        val xpub = dataItem.bytes("xpub") ?: error("TAPSIGNER xpub response did not include xpub")
        if (xpub.size != 78) error("TAPSIGNER returned an invalid xpub length")
        val cardNonce = dataItem.bytes("card_nonce")
        cardNonce?.let { if (it.size != 16) error("TAPSIGNER xpub response had an invalid card nonce length") }
        return TapsignerXpubResponse(xpub, cardNonce)
    }

    fun requireMasterXpubChainCode(xpub: ByteArray, expectedChainCode: ByteArray) {
        if (xpub.size != 78) error("TAPSIGNER returned an invalid master xpub length")
        if (expectedChainCode.size != 32) error("Expected TAPSIGNER chain code must be 32 bytes")
        val returnedChainCode = xpub.copyOfRange(13, 45)
        try {
            if (!MessageDigest.isEqual(returnedChainCode, expectedChainCode)) {
                error("TAPSIGNER master xpub did not preserve the wallet-provided chain code")
            }
        } finally {
            returnedChainCode.fill(0)
        }
    }

    fun parseTapsignerNewResponse(response: ByteArray): TapsignerNewResult {
        val dataItem = responseMapOrThrow(response)
        val slot = dataItem.long("slot") ?: error("TAPSIGNER initialize response did not include slot")
        val cardNonce = dataItem.bytes("card_nonce") ?: error("TAPSIGNER initialize response did not include card nonce")
        if (slot !in 0..9) error("TAPSIGNER initialize response contained an invalid slot")
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
        return TapsignerDeriveResult(signature, chainCode, masterPubkey, pubkey, cardNonce)
    }

    fun parseTapsignerSignProof(response: ByteArray): TapsignerSignProof {
        val dataItem = responseMapOrThrow(response)
        val slot = dataItem.long("slot") ?: error("TAPSIGNER sign response did not include a slot")
        val signature = dataItem.bytes("sig") ?: error("TAPSIGNER sign response did not include a signature")
        val pubkey = dataItem.bytes("pubkey") ?: error("TAPSIGNER sign response did not include a public key")
        val cardNonce = dataItem.bytes("card_nonce") ?: error("TAPSIGNER sign response did not include a card nonce")
        if (slot !in 0..9) error("TAPSIGNER sign response contained an invalid slot")
        if (signature.size != 64) error("TAPSIGNER sign response had an invalid signature length")
        if (pubkey.size != 33) error("TAPSIGNER sign response had an invalid public key length")
        if (cardNonce.size != 16) error("TAPSIGNER sign response had an invalid card nonce length")
        return TapsignerSignProof(slot, signature, pubkey, cardNonce)
    }

    /**
     * Bind a serialized BIP32 xpub returned by a later authenticated command to
     * the account public key and chain code already bound by the encrypted
     * child-key proof.
     */
    fun requireTapsignerXpubBinding(
        xpub: ByteArray,
        expectedChainCode: ByteArray,
        expectedPubkey: ByteArray
    ) {
        if (xpub.size != 78) error("TAPSIGNER returned an invalid xpub length")
        if (expectedChainCode.size != 32) error("Expected TAPSIGNER chain code must be 32 bytes")
        if (expectedPubkey.size != 33) error("Expected TAPSIGNER public key must be 33 bytes")
        val returnedChainCode = xpub.copyOfRange(13, 45)
        val returnedPubkey = xpub.copyOfRange(45, 78)
        try {
            if (!MessageDigest.isEqual(returnedChainCode, expectedChainCode) ||
                !MessageDigest.isEqual(returnedPubkey, expectedPubkey)
            ) {
                error("TAPSIGNER xpub did not match the child-key-verified account")
            }
        } finally {
            returnedChainCode.fill(0)
            returnedPubkey.fill(0)
        }
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
        val keyMask = sessionKey.copyOfRange(0, encryptedPrivkey.size)
        val privateKey = try {
            xorBytes(encryptedPrivkey, keyMask)
        } finally {
            keyMask.fill(0)
        }
        var handedOff = false
        try {
            val responsePubkey = dataItem.bytes("pubkey")
            responsePubkey?.let { if (it.size != 33) error("SATSCARD returned an invalid public key length") }
            CoinkiteTapCardVerifier.verifyUnsealedPrivateKey(privateKey, responsePubkey, verifiedSlot)
            val pubkeyHex = responsePubkey?.toHex()
            val result = SatscardUnsealResult(
                slot = slot,
                privateKey = privateKey,
                publicKeyHex = pubkeyHex,
                address = verifiedSlot.address,
                isTestnet = verifiedSlot.isTestnet,
                summary = "SATSCARD slot ${satscardDisplaySlot(slot)} unsealed"
            )
            handedOff = true
            return result
        } finally {
            if (!handedOff) privateKey.fill(0)
        }
    }

    fun parseDumpResponse(response: ByteArray): SatscardSlotState {
        val dataItem = responseMapOrThrow(response)
        val slot = dataItem.long("slot")
        val address = dataItem.string("addr")
        val pubkey = dataItem.bytes("pubkey")
        val cardNonce = dataItem.bytes("card_nonce")
        if (slot != null && slot !in 0..9) error("SATSCARD dump response contained an invalid slot")
        if (address != null && address.length > 128) error("SATSCARD dump response contained an excessive address")
        if (pubkey != null && pubkey.size != 33) error("SATSCARD dump response contained an invalid public key")
        if (cardNonce != null && cardNonce.size != 16) error("SATSCARD dump response contained an invalid card nonce")
        return SatscardSlotState(
            slot = slot,
            used = dataItem.boolean("used"),
            sealed = dataItem.boolean("sealed"),
            address = address,
            pubkey = pubkey,
            cardNonce = cardNonce
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
        if (response.size > MAX_RESPONSE_BYTES) error("Coinkite Tap card NFC response exceeded the safety limit")
        if (!isSuccessResponse(response)) {
            val sw = response.takeLast(2).joinToString("") { "%02X".format(it) }
            error("Coinkite Tap card NFC command failed with status word 0x$sw")
        }
        return responseBody(response)
    }

    private fun responseMapOrThrow(response: ByteArray): Map {
        val body = responseBodyOrThrow(response)
        if (body.isEmpty()) error("Coinkite Tap card returned success without a CBOR body")
        val dataItem = decodeSingleResponseMap(body)
        dataItem.string("error")?.let { errorText ->
            if (errorText.length > MAX_ERROR_CHARS) {
                error("Coinkite Tap card returned an excessive error message")
            }
            throw CoinkiteTapCardException(dataItem.long("code"), errorText)
        }
        return dataItem
    }

    private fun decodeSingleResponseMap(body: ByteArray): Map {
        try {
            validateCborEnvelope(body)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException(
                failure.message ?: "Coinkite Tap card CBOR response is malformed",
                failure
            )
        }
        val decoder = CborDecoder(ByteArrayInputStream(body)).apply {
            setRejectDuplicateKeys(true)
            setMaxPreallocationSize(1_024)
        }
        val items = decoder.decode()
        if (items.size != 1) error("Coinkite Tap card response contained multiple CBOR values")
        return items.single() as? Map
            ?: error("Coinkite Tap card response was not a CBOR map")
    }

    /**
     * Performs a non-allocating, depth-bounded CBOR framing pass before the
     * library decoder. Tap cards may use either definite or indefinite CBOR,
     * so the preflight validates breaks and chunks while preventing a bounded
     * NFC response from becoming a recursive decoder stack attack.
     */
    private fun validateCborEnvelope(body: ByteArray) {
        val cursor = CborEnvelopeCursor(body)
        cursor.readItem(depth = 0)
        require(cursor.atEnd()) { "Coinkite Tap card response contains trailing CBOR data" }
    }

    private class CborEnvelopeCursor(
        private val bytes: ByteArray
    ) {
        private var offset = 0
        private var items = 0

        fun atEnd(): Boolean = offset == bytes.size

        fun readItem(depth: Int) {
            require(depth <= MAX_CBOR_DEPTH) {
                "Coinkite Tap card CBOR nesting exceeds the safety limit"
            }
            require(++items <= MAX_CBOR_CONTAINER_ITEMS) {
                "Coinkite Tap card CBOR contains too many values"
            }
            val initial = readByte()
            val major = initial ushr 5
            val additional = initial and 0x1f
            if (additional == 31) {
                when (major) {
                    2, 3 -> readIndefiniteString(major)
                    4 -> readIndefiniteArray(depth)
                    5 -> readIndefiniteMap(depth)
                    7 -> error("Coinkite Tap card CBOR contains an unexpected break")
                    else -> error("Coinkite Tap card CBOR uses an invalid indefinite-length value")
                }
                return
            }
            when (major) {
                0, 1 -> readArgument(additional)
                2, 3 -> {
                    val length = readArgument(additional)
                    require(length <= (bytes.size - offset).toULong()) {
                        "Coinkite Tap card CBOR string is truncated"
                    }
                    offset += length.toInt()
                }
                4 -> {
                    val count = containerCount(readArgument(additional))
                    repeat(count) { readItem(depth + 1) }
                }
                5 -> {
                    val count = containerCount(readArgument(additional))
                    require(count <= MAX_CBOR_CONTAINER_ITEMS / 2) {
                        "Coinkite Tap card CBOR map contains too many entries"
                    }
                    repeat(count * 2) { readItem(depth + 1) }
                }
                6 -> error("Coinkite Tap card CBOR tags are not permitted")
                7 -> readSimple(additional)
                else -> error("Coinkite Tap card CBOR major type is invalid")
            }
        }

        private fun readIndefiniteString(expectedMajor: Int) {
            while (!consumeBreakIfPresent()) {
                require(++items <= MAX_CBOR_CONTAINER_ITEMS) {
                    "Coinkite Tap card CBOR contains too many values"
                }
                val initial = readByte()
                val major = initial ushr 5
                val additional = initial and 0x1f
                require(major == expectedMajor && additional != 31) {
                    "Coinkite Tap card CBOR indefinite string contains an invalid chunk"
                }
                val length = readArgument(additional)
                require(length <= (bytes.size - offset).toULong()) {
                    "Coinkite Tap card CBOR string is truncated"
                }
                offset += length.toInt()
            }
        }

        private fun readIndefiniteArray(depth: Int) {
            while (!consumeBreakIfPresent()) {
                readItem(depth + 1)
            }
        }

        private fun readIndefiniteMap(depth: Int) {
            var entries = 0
            while (!consumeBreakIfPresent()) {
                require(++entries <= MAX_CBOR_CONTAINER_ITEMS / 2) {
                    "Coinkite Tap card CBOR map contains too many entries"
                }
                readItem(depth + 1)
                require(!nextIsBreak()) {
                    "Coinkite Tap card CBOR indefinite map ended after a key"
                }
                readItem(depth + 1)
            }
        }

        private fun nextIsBreak(): Boolean {
            require(offset < bytes.size) {
                "Coinkite Tap card CBOR indefinite-length value is unterminated"
            }
            return bytes[offset].toInt() and 0xff == 0xff
        }

        private fun consumeBreakIfPresent(): Boolean {
            if (!nextIsBreak()) return false
            offset++
            return true
        }

        private fun readArgument(additional: Int): ULong = when (additional) {
            in 0..23 -> additional.toULong()
            24 -> readUnsigned(1).also {
                require(it >= 24uL) { "Coinkite Tap card CBOR integer is non-canonical" }
            }
            25 -> readUnsigned(2).also {
                require(it > 0xffuL) { "Coinkite Tap card CBOR integer is non-canonical" }
            }
            26 -> readUnsigned(4).also {
                require(it > 0xffffuL) { "Coinkite Tap card CBOR integer is non-canonical" }
            }
            27 -> readUnsigned(8).also {
                require(it > 0xffff_ffffuL) { "Coinkite Tap card CBOR integer is non-canonical" }
            }
            else -> error("Coinkite Tap card CBOR uses a reserved additional value")
        }

        private fun readSimple(additional: Int) {
            when (additional) {
                in 0..23 -> Unit
                24 -> {
                    val value = readUnsigned(1)
                    require(value >= 32uL) { "Coinkite Tap card CBOR simple value is non-canonical" }
                }
                25 -> readUnsigned(2)
                26 -> readUnsigned(4)
                27 -> readUnsigned(8)
                else -> error("Coinkite Tap card CBOR simple value is invalid")
            }
        }

        private fun containerCount(value: ULong): Int {
            require(value <= MAX_CBOR_CONTAINER_ITEMS.toULong()) {
                "Coinkite Tap card CBOR container exceeds the safety limit"
            }
            return value.toInt()
        }

        private fun readUnsigned(byteCount: Int): ULong {
            require(offset <= bytes.size - byteCount) {
                "Coinkite Tap card CBOR value is truncated"
            }
            var value = 0uL
            repeat(byteCount) {
                value = (value shl 8) or readByte().toULong()
            }
            return value
        }

        private fun readByte(): Int {
            require(offset < bytes.size) { "Coinkite Tap card CBOR value is truncated" }
            return bytes[offset++].toInt() and 0xff
        }
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

    private fun Map.string(key: String): String? {
        val item = value(key) ?: return null
        return (item as? UnicodeString)?.string
            ?: error("Coinkite Tap card field $key was not text")
    }

    private fun Map.boolean(key: String): Boolean? {
        val item = value(key) ?: return null
        return when (item) {
            SimpleValue.TRUE -> true
            SimpleValue.FALSE -> false
            else -> error("Coinkite Tap card field $key was not a boolean")
        }
    }

    private fun Map.long(key: String): Long? {
        val item = value(key) ?: return null
        val value = (item as? Number)?.value
            ?: error("Coinkite Tap card field $key was not an integer")
        require(value >= BigInteger.valueOf(Long.MIN_VALUE) && value <= BigInteger.valueOf(Long.MAX_VALUE)) {
            "Coinkite Tap card field $key exceeded the supported integer range"
        }
        return value.toLong()
    }

    private fun Map.longArray(key: String): List<Long>? {
        val item = value(key) ?: return null
        val array = item as? Array
            ?: error("Coinkite Tap card field $key was not an array")
        return array.valuesWithoutBreak().map { entry ->
            val value = (entry as? Number)?.value
                ?: error("Coinkite Tap card field $key contained a non-integer")
            require(value >= BigInteger.valueOf(Long.MIN_VALUE) && value <= BigInteger.valueOf(Long.MAX_VALUE)) {
                "Coinkite Tap card field $key exceeded the supported integer range"
            }
            value.toLong()
        }
    }

    private fun Map.bytes(key: String): ByteArray? {
        val item = value(key) ?: return null
        return (item as? ByteString)?.bytes
            ?: error("Coinkite Tap card field $key was not binary data")
    }

    private fun Map.bytesArray(key: String): List<ByteArray>? {
        val item = value(key) ?: return null
        val array = item as? Array
            ?: error("Coinkite Tap card field $key was not an array")
        return array.valuesWithoutBreak().map { entry ->
            (entry as? ByteString)?.bytes
                ?: error("Coinkite Tap card field $key contained non-binary data")
        }
    }

    private fun Array.valuesWithoutBreak(): List<DataItem> {
        return if (isChunked && dataItems.lastOrNull() == Special.BREAK) {
            dataItems.dropLast(1)
        } else {
            dataItems
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class AuthenticatedCommand(
        val sessionKey: ByteArray,
        val ephemeralPublicKey: ByteArray,
        val encryptedCvc: ByteArray
    ) {
        fun clear() {
            sessionKey.fill(0)
            ephemeralPublicKey.fill(0)
            encryptedCvc.fill(0)
        }
    }

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
        val cvcBytes = ByteArray(cvc.size)
        var cvcMask = ByteArray(0)
        return try {
            cvc.indices.forEach { index ->
                val code = cvc[index].code
                require(code in 0x21..0x7E) { "Coinkite card PIN/spend code must contain printable ASCII characters" }
                cvcBytes[index] = code.toByte()
            }
            cvcMask = mask.copyOfRange(0, cvcBytes.size)
            val encryptedCvc = xorBytes(cvcBytes, cvcMask)
            AuthenticatedCommand(sessionKey, ephemeralPublicKey, encryptedCvc)
        } catch (t: Throwable) {
            sessionKey.fill(0)
            ephemeralPublicKey.fill(0)
            throw t
        } finally {
            nonceDigest.fill(0)
            mask.fill(0)
            cvcBytes.fill(0)
            cvcMask.fill(0)
        }
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
            return try {
                TapsignerTapProtocol.parseSatscardUnsealResponse(
                    response = isoDep.transceive(command),
                    sessionKey = sessionKey,
                    verifiedSlot = verifiedSlot
                )
            } finally {
                command.fill(0)
                sessionKey.fill(0)
            }
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

    /**
     * Direct TAPSIGNER signing for supported native-SegWit inputs.
     * The card remains in the RF field while Clench signs every eligible
     * input, with a fresh authenticated command and nonce for each input. The
     * returned PSBT still passes through Clench's normal signature-only merge,
     * finalization, and transaction-policy validation.
     */
    fun signPsbt(tag: Tag, cvc: CharArray, psbtBase64: String): TapsignerPsbtSignResult {
        try {
            val isoDep = IsoDep.get(tag) ?: error("TAPSIGNER requires ISO-DEP NFC, not NDEF")
            var plan: TapsignerPsbtSigning.Plan? = null
            val signatures = mutableListOf<TapsignerPsbtSigning.Signature>()
            try {
                isoDep.connect()
                isoDep.timeout = 20_000
                var status = selectOrReadStatus(isoDep)
            if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
            if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
            val accountPath = requireSupportedSigningPath(status)
            if ((status.authDelaySeconds ?: 0L) > 0L) {
                clearCoinkiteAuthDelay(isoDep, status.authDelaySeconds!!, "TAPSIGNER")
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
                if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
                if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
                if (requireSupportedSigningPath(status) != accountPath) {
                    error("TAPSIGNER derivation path changed before signing")
                }
            }

            val cardPubkey = verifiedCardPubkey(status)
            var latestCardNonce = verifyTapsignerCard(isoDep, status, cardPubkey)
            plan = TapsignerPsbtSigning.prepare(psbtBase64, accountPath)

            plan.requests.forEach { request ->
                var signed = false
                for (attempt in 0 until 5) {
                    var command: ByteArray? = null
                    var response: ByteArray? = null
                    var proof: TapsignerSignProof? = null
                    try {
                        command = TapsignerTapProtocol.authenticatedTapsignerSignCommand(
                            digest = request.digest,
                            subpath = request.subpath,
                            cardPubkey = cardPubkey,
                            cardNonce = latestCardNonce,
                            cvc = cvc
                        )
                        response = isoDep.transceive(command)
                        proof = TapsignerTapProtocol.parseTapsignerSignProof(response)
                        if (proof.slot != 0L) error("TAPSIGNER signed with an unexpected slot")
                        if (request.candidatePubkeys.none { MessageDigest.isEqual(it, proof.pubkey) }) {
                            error("TAPSIGNER returned a key outside this wallet policy")
                        }
                        if (!CoinkiteTapCardVerifier.verifyEcdsa(
                                proof.pubkey,
                                request.digest,
                                proof.signature
                            )) {
                            error("TAPSIGNER payment signature did not verify")
                        }
                        signatures += TapsignerPsbtSigning.Signature(
                            inputIndex = request.inputIndex,
                            pubkey = proof.pubkey.copyOf(),
                            compactSignature = proof.signature.copyOf()
                        )
                        latestCardNonce.fill(0)
                        latestCardNonce = proof.cardNonce.copyOf()
                        signed = true
                        break
                    } catch (e: CoinkiteTapCardException) {
                        if (e.code != 205L || attempt == 4) throw e
                        val refreshed = TapsignerTapProtocol.parseStatusResponse(
                            isoDep.transceive(TapsignerTapProtocol.statusCommand())
                        )
                        requireSupportedSigningPath(refreshed)
                        val nextNonce = requireTapsignerContinuity(
                            status = refreshed,
                            expectedCardPubkey = cardPubkey,
                            expectedPath = accountPath,
                            expectedCardNonce = null
                        )
                        latestCardNonce.fill(0)
                        latestCardNonce = nextNonce
                    } finally {
                        command?.fill(0)
                        proof?.signature?.fill(0)
                        proof?.pubkey?.fill(0)
                        proof?.cardNonce?.fill(0)
                        response?.fill(0)
                    }
                }
                if (!signed) error("TAPSIGNER could not sign input ${request.inputIndex + 1}")
            }

            val finalStatus = TapsignerTapProtocol.parseStatusResponse(
                isoDep.transceive(TapsignerTapProtocol.statusCommand())
            )
            requireSupportedSigningPath(finalStatus)
            val finalNonce = requireTapsignerContinuity(
                status = finalStatus,
                expectedCardPubkey = cardPubkey,
                expectedPath = accountPath,
                expectedCardNonce = latestCardNonce
            )
            finalNonce.fill(0)
            val signedPsbt = TapsignerPsbtSigning.inject(plan, signatures)
            return TapsignerPsbtSignResult(
                signedPsbtBase64 = signedPsbt,
                signedInputCount = signatures.size,
                summary = "TAPSIGNER signed ${signatures.size} input${if (signatures.size == 1) "" else "s"}; Clench is verifying the resulting PSBT"
            )
            } finally {
                plan?.clear()
                plan?.parsed?.clear()
                signatures.forEach { it.clear() }
                runCatching { isoDep.close() }
            }
        } finally {
            cvc.fill('0')
        }
    }

    fun readAccountXpub(
        tag: Tag,
        cvc: CharArray,
        expectedIsTestnet: Boolean
    ): TapsignerAccountXpubResult {
        val isoDep = IsoDep.get(tag) ?: error("TAPSIGNER requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        try {
            isoDep.timeout = 10000
            var status = selectOrReadStatus(isoDep)
            if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
            if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
            requireTapsignerNetwork(status.isTestnet, expectedIsTestnet)
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
                requireTapsignerNetwork(status.isTestnet, expectedIsTestnet)
                if (status.derivationPath == null) {
                    error("This TAPSIGNER has not been set up yet. Initialize it with a TAPSIGNER-compatible wallet first, then return to Clench to import its xpub.")
                }
            }
            val targetPath = singleSigAccountPath(expectedIsTestnet)
            val targetPathDisplay = formatDerivationPath(targetPath)
            val cardPubkey = verifiedCardPubkey(status)
            var latestCardNonce = verifyTapsignerCard(isoDep, status, cardPubkey)
            val deriveNonce = randomNonce()
            val previousCardNonce = latestCardNonce
            val derive = TapsignerTapProtocol.parseTapsignerDeriveResponse(
                isoDep.transceive(
                    TapsignerTapProtocol.authenticatedDeriveCommand(
                        path = targetPath,
                        nonce = deriveNonce,
                        cardPubkey = cardPubkey,
                        cardNonce = previousCardNonce,
                        cvc = cvc
                    )
                )
            )
            latestCardNonce = proveCurrentDerivedKey(
                isoDep = isoDep,
                cardPubkey = cardPubkey,
                cardNonce = derive.cardNonce,
                cvc = cvc,
                expectedPath = targetPath,
                deriveProof = derive
            )
            return readVerifiedAccountXpub(
                isoDep = isoDep,
                cardPubkey = cardPubkey,
                initialCardNonce = latestCardNonce,
                cvc = cvc,
                reportedPath = targetPathDisplay,
                summaryPrefix = "TAPSIGNER single-sig xpub imported",
                derivePreviousCardNonce = previousCardNonce,
                deriveRequestNonce = deriveNonce,
                deriveProof = derive,
                expectedPath = targetPath,
                expectedIsTestnet = expectedIsTestnet
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
        var expectedMasterChainCode: ByteArray? = null
        try {
            isoDep.timeout = 10000
            var status = selectOrReadStatus(isoDep)
            if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
            if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
            requireTapsignerNetwork(status.isTestnet, isTestnet)
            if ((status.authDelaySeconds ?: 0L) > 0L) {
                clearCoinkiteAuthDelay(isoDep, status.authDelaySeconds!!, "TAPSIGNER")
                status = TapsignerTapProtocol.parseStatusResponse(
                    isoDep.transceive(TapsignerTapProtocol.statusCommand())
                )
                if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
                if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
                requireTapsignerNetwork(status.isTestnet, isTestnet)
            }

            val cardPubkey = verifiedCardPubkey(status)
            var latestCardNonce = verifyTapsignerCard(isoDep, status, cardPubkey)

            if (status.derivationPath == null) {
                if (!initializeIfNeeded) {
                    error("This TAPSIGNER has not been set up yet. Use Set up as multisig cosigner to initialize it at $targetPathDisplay.")
                }
                val chainCode = randomChainCode()
                val newResult = TapsignerTapProtocol.parseTapsignerNewResponse(
                    isoDep.transceive(
                        TapsignerTapProtocol.authenticatedNewTapsignerCommand(
                            cardPubkey = cardPubkey,
                            cardNonce = latestCardNonce,
                            cvc = cvc,
                            chainCode = chainCode
                        )
                    )
                )
                expectedMasterChainCode = chainCode
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
            }
            val deriveNonce = randomNonce()
            val previousCardNonce = latestCardNonce
            val derive = TapsignerTapProtocol.parseTapsignerDeriveResponse(
                isoDep.transceive(
                    TapsignerTapProtocol.authenticatedDeriveCommand(
                        path = targetPath,
                        nonce = deriveNonce,
                        cardPubkey = cardPubkey,
                        cardNonce = previousCardNonce,
                        cvc = cvc
                    )
                )
            )
            latestCardNonce = proveCurrentDerivedKey(
                isoDep = isoDep,
                cardPubkey = cardPubkey,
                cardNonce = derive.cardNonce,
                cvc = cvc,
                expectedPath = targetPath,
                deriveProof = derive
            )

            return readVerifiedAccountXpub(
                isoDep = isoDep,
                cardPubkey = cardPubkey,
                initialCardNonce = latestCardNonce,
                cvc = cvc,
                reportedPath = targetPathDisplay,
                summaryPrefix = "TAPSIGNER multisig cosigner imported",
                expectedMasterChainCode = expectedMasterChainCode,
                derivePreviousCardNonce = previousCardNonce,
                deriveRequestNonce = deriveNonce,
                deriveProof = derive,
                expectedPath = targetPath,
                expectedIsTestnet = isTestnet
            )
        } finally {
            isoDep.close()
            expectedMasterChainCode?.fill(0)
            cvc.fill('0')
        }
    }

    fun initializeAndReadAccountXpub(
        tag: Tag,
        cvc: CharArray,
        expectedIsTestnet: Boolean
    ): TapsignerAccountXpubResult {
        val isoDep = IsoDep.get(tag) ?: error("TAPSIGNER requires ISO-DEP NFC, not NDEF")
        isoDep.connect()
        var expectedMasterChainCode: ByteArray? = null
        try {
            isoDep.timeout = 10000
            var status = selectOrReadStatus(isoDep)
            if (!status.isTapsigner) error("Coinkite NFC card is not reporting TAPSIGNER mode")
            if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
            requireTapsignerNetwork(status.isTestnet, expectedIsTestnet)
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
                requireTapsignerNetwork(status.isTestnet, expectedIsTestnet)
                if (status.derivationPath != null) {
                    error("This TAPSIGNER is already initialized. Use NFC import instead.")
                }
            }
            val cardPubkey = verifiedCardPubkey(status)
            val verifiedCardNonce = verifyTapsignerCard(isoDep, status, cardPubkey)
            val chainCode = randomChainCode()
            val newResult = TapsignerTapProtocol.parseTapsignerNewResponse(
                isoDep.transceive(
                    TapsignerTapProtocol.authenticatedNewTapsignerCommand(
                        cardPubkey = cardPubkey,
                        cardNonce = verifiedCardNonce,
                        cvc = cvc,
                        chainCode = chainCode
                    )
                )
            )
            expectedMasterChainCode = chainCode
            if (newResult.slot != 0L) error("TAPSIGNER initialized an unexpected slot")
            val targetPath = singleSigAccountPath(expectedIsTestnet)
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
            val proofCardNonce = proveCurrentDerivedKey(
                isoDep = isoDep,
                cardPubkey = cardPubkey,
                cardNonce = derive.cardNonce,
                cvc = cvc,
                expectedPath = targetPath,
                deriveProof = derive
            )
            return readVerifiedAccountXpub(
                isoDep = isoDep,
                cardPubkey = cardPubkey,
                initialCardNonce = proofCardNonce,
                cvc = cvc,
                reportedPath = targetPathDisplay,
                summaryPrefix = "TAPSIGNER initialized and xpub imported",
                expectedMasterChainCode = expectedMasterChainCode,
                derivePreviousCardNonce = newResult.cardNonce,
                deriveRequestNonce = deriveNonce,
                deriveProof = derive,
                expectedPath = targetPath,
                expectedIsTestnet = expectedIsTestnet
            )
        } finally {
            isoDep.close()
            expectedMasterChainCode?.fill(0)
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

    /**
     * Protect the spend-critical account tuple from response substitution. A
     * fresh app-secret digest is encrypted inside a CVC-authenticated `sign`
     * command. A fixed non-hardened child derived from the returned account pubkey and
     * chain code must sign it. This binds both halves of the eventual account
     * xpub instead of trusting the firmware's ambiguous derive attestation.
     */
    private fun proveCurrentDerivedKey(
        isoDep: IsoDep,
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        cvc: CharArray,
        expectedPath: List<Long>,
        deriveProof: TapsignerDeriveResult
    ): ByteArray {
        val proofSubpath = listOf(0L, 0L)
        val expectedProofPubkey = CoinkiteTapCardVerifier.deriveUnhardenedPublicKey(
            parentPubkey = deriveProof.pubkey,
            parentChainCode = deriveProof.chainCode,
            subpath = proofSubpath
        )
        var currentCardNonce = cardNonce.copyOf()
        try {
            for (attempt in 0 until 5) {
                val challenge = randomProofChallenge()
                var command: ByteArray? = null
                var response: ByteArray? = null
                var proof: TapsignerSignProof? = null
                try {
                    val outbound = TapsignerTapProtocol.authenticatedTapsignerProofCommand(
                        challenge = challenge,
                        subpath = proofSubpath,
                        cardPubkey = cardPubkey,
                        cardNonce = currentCardNonce,
                        cvc = cvc
                    )
                    command = outbound
                    response = isoDep.transceive(outbound)
                    proof = TapsignerTapProtocol.parseTapsignerSignProof(response)
                    CoinkiteTapCardVerifier.verifyTapsignerProofOfPossession(
                        challenge = challenge,
                        proof = proof,
                        expectedDerivedPubkey = expectedProofPubkey
                    )
                    val status = TapsignerTapProtocol.parseStatusResponse(
                        isoDep.transceive(TapsignerTapProtocol.statusCommand())
                    )
                    return requireTapsignerContinuity(
                        status = status,
                        expectedCardPubkey = cardPubkey,
                        expectedPath = expectedPath,
                        expectedCardNonce = proof.cardNonce
                    )
                } catch (e: CoinkiteTapCardException) {
                    if (e.code != 205L || attempt == 4) throw e
                    // Firmware 0.9.0 advanced card_nonce on error 205. A
                    // status refresh is safe for every version and lets the
                    // next attempt use a fresh challenge and ECDH session.
                    val status = TapsignerTapProtocol.parseStatusResponse(
                        isoDep.transceive(TapsignerTapProtocol.statusCommand())
                    )
                    val nextNonce = requireTapsignerContinuity(
                        status = status,
                        expectedCardPubkey = cardPubkey,
                        expectedPath = expectedPath,
                        expectedCardNonce = null
                    )
                    currentCardNonce.fill(0)
                    currentCardNonce = nextNonce
                } finally {
                    challenge.fill(0)
                    command?.fill(0)
                    proof?.signature?.fill(0)
                    proof?.pubkey?.fill(0)
                    proof?.cardNonce?.fill(0)
                    response?.fill(0)
                }
            }
            error("TAPSIGNER could not complete proof of key possession")
        } finally {
            currentCardNonce.fill(0)
            expectedProofPubkey.fill(0)
        }
    }

    internal fun requireTapsignerContinuity(
        status: CoinkiteTapCardStatus,
        expectedCardPubkey: ByteArray,
        expectedPath: List<Long>,
        expectedCardNonce: ByteArray?
    ): ByteArray {
        if (!status.isTapsigner) error("Coinkite NFC card stopped reporting TAPSIGNER mode")
        if (status.isTampered == true) error("TAPSIGNER tamper warning is set")
        if (status.derivationPath != expectedPath) {
            error("TAPSIGNER derivation path changed during account verification")
        }
        val returnedCardPubkey = status.cardPubkeyHex?.hexToBytes()
            ?: error("TAPSIGNER status did not include card pubkey")
        val returnedCardNonce = status.cardNonceHex?.hexToBytes()
            ?: error("TAPSIGNER status did not include card nonce")
        try {
            if (!MessageDigest.isEqual(returnedCardPubkey, expectedCardPubkey)) {
                error("A different TAPSIGNER card responded during account verification")
            }
            if (expectedCardNonce != null &&
                !MessageDigest.isEqual(returnedCardNonce, expectedCardNonce)
            ) {
                error("TAPSIGNER card nonce changed unexpectedly after key proof")
            }
            return returnedCardNonce.copyOf()
        } finally {
            returnedCardPubkey.fill(0)
            returnedCardNonce.fill(0)
        }
    }

    private fun readVerifiedAccountXpub(
        isoDep: IsoDep,
        cardPubkey: ByteArray,
        initialCardNonce: ByteArray,
        cvc: CharArray,
        reportedPath: String?,
        summaryPrefix: String,
        expectedMasterChainCode: ByteArray? = null,
        derivePreviousCardNonce: ByteArray,
        deriveRequestNonce: ByteArray,
        deriveProof: TapsignerDeriveResult,
        expectedPath: List<Long>,
        expectedIsTestnet: Boolean
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
        expectedMasterChainCode?.let { expected ->
            TapsignerTapProtocol.requireMasterXpubChainCode(
                xpub = master.xpub,
                expectedChainCode = expected
            )
        }
        val masterPubkey = master.xpub.copyOfRange(45, 78)
        val masterChainCode = master.xpub.copyOfRange(13, 45)
        CoinkiteTapCardVerifier.verifyTapsignerDerive(
            previousCardNonce = derivePreviousCardNonce,
            requestNonce = deriveRequestNonce,
            derive = deriveProof,
            masterPubkey = masterPubkey,
            masterChainCode = masterChainCode
        )
        val fingerprint = CoinkiteTapCardVerifier.fingerprintHexFromPublicKey(masterPubkey)
        masterChainCode.fill(0)
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
        val xpub = canonicalTapsignerAccountXpub(
            returnedXpub = account.xpub,
            expectedChainCode = deriveProof.chainCode,
            expectedPubkey = deriveProof.pubkey,
            expectedPath = expectedPath,
            isTestnet = expectedIsTestnet
        )
        // Retain the already validated card encoding only for matching wallets
        // imported by older Clench builds, which preserved Coinkite's parent-
        // fingerprint placeholder verbatim. New imports always use `xpub`.
        val cardReturnedXpub = account.xpub.base58CheckEncode()
        val status = selectOrReadStatus(isoDep)
        requireTapsignerNetwork(status.isTestnet, expectedIsTestnet)
        val refreshedPath = status.displayPath
            ?: error("TAPSIGNER did not report a derivation path after authenticated derive")
        if (reportedPath != null && refreshedPath != reportedPath) {
            error("TAPSIGNER reported $refreshedPath after deriving $reportedPath")
        }
        val originPath = refreshedPath.removePrefix("m/")
        val originWrapped = "[$fingerprint/$originPath]$xpub"
        return TapsignerAccountXpubResult(
            xpub = xpub,
            cardReturnedXpub = cardReturnedXpub,
            originWrappedXpub = originWrapped,
            masterFingerprint = fingerprint,
            derivationPath = refreshedPath,
            isTestnet = expectedIsTestnet,
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

    private fun randomProofChallenge(): ByteArray {
        return ByteArray(32).also { SecureRandom().nextBytes(it) }
    }

    internal fun singleSigAccountPath(isTestnet: Boolean): List<Long> {
        return listOf(84L, if (isTestnet) 1L else 0L, 0L).map { it or HARDENED_FLAG }
    }

    /**
     * Direct payment signing is intentionally narrower than generic xpub import.
     * Fail closed unless the card is on the network's standard account-zero path
     * for either BIP84 single-signature or BIP48 native-SegWit multisig.
     */
    internal fun requireSupportedSigningPath(status: CoinkiteTapCardStatus): List<Long> {
        val actualPath = status.derivationPath
            ?: error("This TAPSIGNER has not been set up yet")
        val isTestnet = status.isTestnet == true
        val singleSigPath = singleSigAccountPath(isTestnet)
        val multisigPath = multisigAccountPath(isTestnet)
        if (actualPath != singleSigPath && actualPath != multisigPath) {
            val network = if (isTestnet) "testnet" else "mainnet"
            error(
                "TAPSIGNER payment signing requires the $network BIP84 account-0 path " +
                    "${formatDerivationPath(singleSigPath)} or BIP48 native-SegWit " +
                    "multisig account-0 path ${formatDerivationPath(multisigPath)}; card reported " +
                    formatDerivationPath(actualPath)
            )
        }
        return actualPath
    }

    internal fun multisigAccountPath(isTestnet: Boolean): List<Long> {
        return listOf(48L, if (isTestnet) 1L else 0L, 0L, 2L).map { it or HARDENED_FLAG }
    }

    internal fun requireTapsignerNetwork(
        cardIsTestnet: Boolean?,
        expectedIsTestnet: Boolean
    ) {
        val actualIsTestnet = cardIsTestnet == true
        if (actualIsTestnet == expectedIsTestnet) return
        val cardNetwork = if (actualIsTestnet) "testnet" else "mainnet"
        val clenchNetwork = if (expectedIsTestnet) "testnet" else "mainnet"
        error(
            "TAPSIGNER is configured for $cardNetwork, but Clench is set to " +
                "$clenchNetwork. Switch Clench to $cardNetwork and retry."
        )
    }

    /**
     * Serialize only the account metadata Clench has independently established:
     * card/app network, hardened account path, and the chain code/public key bound
     * by the encrypted child-key proof. Coinkite uses a zero parent fingerprint
     * placeholder for derived xpubs, so that unauthenticated header field is not
     * copied from the card response.
     */
    internal fun canonicalTapsignerAccountXpub(
        returnedXpub: ByteArray,
        expectedChainCode: ByteArray,
        expectedPubkey: ByteArray,
        expectedPath: List<Long>,
        isTestnet: Boolean
    ): String {
        TapsignerTapProtocol.requireTapsignerXpubBinding(
            xpub = returnedXpub,
            expectedChainCode = expectedChainCode,
            expectedPubkey = expectedPubkey
        )
        require(expectedPath.isNotEmpty() && expectedPath.size <= 255) {
            "TAPSIGNER account path must contain between 1 and 255 components"
        }
        require(expectedPath.all { it in 0..0xffff_ffffL }) {
            "TAPSIGNER account path contains an invalid component"
        }

        val mainnetVersion = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E)
        val testnetVersion = byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xCF.toByte())
        val expectedVersion = if (isTestnet) testnetVersion else mainnetVersion
        val oppositeVersion = if (isTestnet) mainnetVersion else testnetVersion
        val returnedVersion = returnedXpub.copyOfRange(0, 4)
        try {
            when {
                MessageDigest.isEqual(returnedVersion, expectedVersion) -> Unit
                MessageDigest.isEqual(returnedVersion, oppositeVersion) -> {
                    error("TAPSIGNER xpub network did not match its card status")
                }
                else -> error("TAPSIGNER returned an unsupported account xpub version")
            }
        } finally {
            returnedVersion.fill(0)
        }

        val expectedDepth = expectedPath.size
        val returnedDepth = returnedXpub[4].toInt() and 0xff
        if (returnedDepth != expectedDepth) {
            error("TAPSIGNER xpub depth did not match its account path")
        }
        val expectedChild = expectedPath.last()
        val returnedChild = ((returnedXpub[9].toLong() and 0xffL) shl 24) or
            ((returnedXpub[10].toLong() and 0xffL) shl 16) or
            ((returnedXpub[11].toLong() and 0xffL) shl 8) or
            (returnedXpub[12].toLong() and 0xffL)
        if (returnedChild != expectedChild) {
            error("TAPSIGNER xpub child number did not match its account path")
        }

        val canonical = ByteArray(78)
        expectedVersion.copyInto(canonical, destinationOffset = 0)
        canonical[4] = expectedDepth.toByte()
        // Bytes 5..8 intentionally remain zero: Coinkite cannot report the
        // immediate hardened parent's fingerprint for a public account xpub.
        canonical[9] = (expectedChild ushr 24).toByte()
        canonical[10] = (expectedChild ushr 16).toByte()
        canonical[11] = (expectedChild ushr 8).toByte()
        canonical[12] = expectedChild.toByte()
        expectedChainCode.copyInto(canonical, destinationOffset = 13)
        expectedPubkey.copyInto(canonical, destinationOffset = 45)
        return try {
            canonical.base58CheckEncode()
        } finally {
            canonical.fill(0)
        }
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
