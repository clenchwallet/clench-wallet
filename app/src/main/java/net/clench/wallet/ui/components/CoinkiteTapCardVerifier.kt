package net.clench.wallet.ui.components

import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint

data class VerifiedSatscardSlot(
    val slot: Long,
    val pubkey: ByteArray,
    val address: String,
    val isTestnet: Boolean
)

object CoinkiteTapCardVerifier {
    private val factoryRootPubkey = "03028a0e89e70d0ec0d932053a89ab1da7d9182bdc6d2f03e706ee99517d05d9e1".hexToBytes()
    private val params = SECNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
    private val curveQ = params.curve.field.characteristic
    private const val ADDR_TRIM = 12
    private const val BECH32_CONST = 1
    private val bech32Charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l".toCharArray()

    fun verifySatscardRead(
        status: CoinkiteTapCardStatus,
        cardNonce: ByteArray,
        readNonce: ByteArray,
        read: SatscardReadResult
    ): VerifiedSatscardSlot {
        val slot = status.activeSlot ?: error("SATSCARD did not report an active slot")
        if (slot !in 0..255) error("SATSCARD active slot is outside the supported range")
        val msg = "OPENDIME".toByteArray(Charsets.US_ASCII) + cardNonce + readNonce + byteArrayOf(slot.toByte())
        val digest = sha256(msg)
        if (!verifyEcdsa(read.pubkey, digest, read.signature)) {
            error("SATSCARD read signature did not verify")
        }
        val derivedAddress = segwitAddress(read.pubkey, status.isTestnet == true)
        val expected = status.address ?: error("SATSCARD status did not include an address for the sealed slot")
        if (!matchesCardAddress(expected, derivedAddress)) {
            error("SATSCARD payment address did not match the verified slot public key")
        }
        return VerifiedSatscardSlot(
            slot = slot,
            pubkey = read.pubkey,
            address = derivedAddress,
            isTestnet = status.isTestnet == true
        )
    }

    fun verifyCertificateChain(
        cardPubkey: ByteArray,
        cardNonce: ByteArray,
        checkNonce: ByteArray,
        authSignature: ByteArray,
        certChain: List<ByteArray>,
        sealedSlotPubkey: ByteArray?,
        cardVersion: String?
    ) {
        require(cardPubkey.size == 33) { "Coinkite card pubkey was invalid" }
        require(cardNonce.size == 16) { "Coinkite card nonce was invalid" }
        require(checkNonce.size == 16) { "Coinkite check nonce was invalid" }
        require(authSignature.size == 64) { "Coinkite check signature was invalid" }
        require(certChain.size >= 2) { "Coinkite certificate chain was incomplete" }

        val includeSlotPubkey = sealedSlotPubkey != null && cardVersion != "0.9.0"
        val msg = buildList<ByteArray> {
            add("OPENDIME".toByteArray(Charsets.US_ASCII))
            add(cardNonce)
            add(checkNonce)
            if (includeSlotPubkey) add(sealedSlotPubkey)
        }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        if (!verifyEcdsa(cardPubkey, sha256(msg), authSignature)) {
            error("Coinkite card identity check signature did not verify")
        }

        var pubkey = cardPubkey
        certChain.forEach { certSignature ->
            pubkey = recoverPublicKey(sha256(pubkey), certSignature)
                ?: error("Coinkite certificate signature could not be recovered")
        }
        if (!pubkey.contentEquals(factoryRootPubkey)) {
            error("Coinkite certificate chain did not end at the trusted factory root")
        }
    }

    fun verifyUnsealedPrivateKey(
        privateKey: ByteArray,
        responsePubkey: ByteArray?,
        verifiedSlot: VerifiedSatscardSlot
    ) {
        val pubkey = publicKeyFromPrivateKey(privateKey)
        responsePubkey?.let {
            if (!it.contentEquals(pubkey)) error("SATSCARD unseal pubkey did not match the returned private key")
        }
        if (!pubkey.contentEquals(verifiedSlot.pubkey)) {
            error("SATSCARD unsealed private key did not match the verified slot public key")
        }
        val address = segwitAddress(pubkey, verifiedSlot.isTestnet)
        if (address != verifiedSlot.address) {
            error("SATSCARD unsealed private key did not match the verified payment address")
        }
    }

    fun publicKeyFromPrivateKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        val scalar = BigInteger(1, privateKey)
        require(scalar > BigInteger.ZERO && scalar < domain.n) { "Private key is outside the secp256k1 range" }
        return domain.g.multiply(scalar).normalize().getEncoded(true)
    }

    fun segwitAddress(pubkey: ByteArray, testnet: Boolean): String {
        require(pubkey.size == 33) { "Compressed public key must be 33 bytes" }
        val program = hash160(pubkey)
        val data = byteArrayOf(0) + convertBits(program, fromBits = 8, toBits = 5, pad = true)
        return bech32Encode(if (testnet) "tb" else "bc", data)
    }

    private fun matchesCardAddress(expected: String, actual: String): Boolean {
        if (!expected.contains("_")) return expected.equals(actual, ignoreCase = false)
        val left = expected.substringBefore("_")
        val right = expected.substringAfterLast("_")
        return left.length == ADDR_TRIM &&
            right.length == ADDR_TRIM &&
            actual.startsWith(left) &&
            actual.endsWith(right)
    }

    private fun verifyEcdsa(pubkey: ByteArray, digest: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64 || digest.size != 32) return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r <= BigInteger.ZERO || r >= domain.n || s <= BigInteger.ZERO || s >= domain.n) return false
        val q = runCatching { domain.curve.decodePoint(pubkey) }.getOrNull() ?: return false
        val e = BigInteger(1, digest)
        val w = s.modInverse(domain.n)
        val u1 = e.multiply(w).mod(domain.n)
        val u2 = r.multiply(w).mod(domain.n)
        val point = ECAlgorithms.sumOfTwoMultiplies(domain.g, u1, q, u2).normalize()
        if (point.isInfinity) return false
        return point.affineXCoord.toBigInteger().mod(domain.n) == r
    }

    private fun recoverPublicKey(digest: ByteArray, signature: ByteArray): ByteArray? {
        if (digest.size != 32 || signature.size != 65) return null
        val header = signature[0].toInt() and 0xFF
        val recId = when (header) {
            in 27..30 -> header - 27
            in 39..42 -> header - 39
            else -> return null
        }
        val r = BigInteger(1, signature.copyOfRange(1, 33))
        val s = BigInteger(1, signature.copyOfRange(33, 65))
        if (r <= BigInteger.ZERO || r >= domain.n || s <= BigInteger.ZERO || s >= domain.n) return null
        val x = r.add(domain.n.multiply(BigInteger.valueOf((recId / 2).toLong())))
        if (x >= curveQ) return null
        val rPoint = decompressKey(x, (recId and 1) == 1)
        if (!rPoint.multiply(domain.n).isInfinity) return null

        val e = BigInteger(1, digest)
        val rInv = r.modInverse(domain.n)
        val srInv = s.multiply(rInv).mod(domain.n)
        val eInv = domain.n.subtract(e).multiply(rInv).mod(domain.n)
        val q = ECAlgorithms.sumOfTwoMultiplies(domain.g, eInv, rPoint, srInv).normalize()
        if (q.isInfinity) return null
        return q.getEncoded(true)
    }

    private fun decompressKey(x: BigInteger, yOdd: Boolean): ECPoint {
        val encoded = byteArrayOf(if (yOdd) 0x03 else 0x02) + x.toFixed32()
        return domain.curve.decodePoint(encoded).normalize()
    }

    private fun hash160(data: ByteArray): ByteArray {
        val sha = sha256(data)
        val digest = RIPEMD160Digest()
        digest.update(sha, 0, sha.size)
        return ByteArray(20).also { digest.doFinal(it, 0) }
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun convertBits(input: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val out = mutableListOf<Byte>()
        input.forEach { value ->
            val b = value.toInt() and 0xFF
            acc = (acc shl fromBits) or b
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out += ((acc shr bits) and maxv).toByte()
            }
        }
        if (pad) {
            if (bits > 0) out += ((acc shl (toBits - bits)) and maxv).toByte()
        } else {
            if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) error("Invalid bit conversion")
        }
        return out.toByteArray()
    }

    private fun bech32Encode(hrp: String, data: ByteArray): String {
        val checksum = createChecksum(hrp, data)
        val combined = data + checksum
        return hrp + "1" + combined.joinToString("") { bech32Charset[it.toInt() and 0x1F].toString() }
    }

    private fun createChecksum(hrp: String, data: ByteArray): ByteArray {
        val values = hrpExpand(hrp) + data.map { it.toInt() and 0xFF } + List(6) { 0 }
        val polymod = polymod(values) xor BECH32_CONST
        return ByteArray(6) { index -> ((polymod shr (5 * (5 - index))) and 0x1F).toByte() }
    }

    private fun hrpExpand(hrp: String): List<Int> {
        return hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }
    }

    private fun polymod(values: List<Int>): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        values.forEach { value ->
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor value
            for (i in 0..4) {
                if (((top ushr i) and 1) == 1) chk = chk xor generators[i]
            }
        }
        return chk
    }

    private fun BigInteger.toFixed32(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Invalid hex length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
