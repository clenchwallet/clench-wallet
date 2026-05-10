package net.clench.wallet.ui.viewmodel

import org.bitcoindevkit.Network
import java.math.BigInteger
import java.security.MessageDigest

data class WifPrivateKey(
    val value: String,
    val compressed: Boolean
)

object WifPrivateKeyParser {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val WIF_CANDIDATE = Regex("""(?<![1-9A-HJ-NP-Za-km-z])[5KLc9][1-9A-HJ-NP-Za-km-z]{50,51}(?![1-9A-HJ-NP-Za-km-z])""")
    private val SECP256K1_N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    fun extract(input: CharArray, network: Network): WifPrivateKey {
        val text = String(input)
        val candidates = WIF_CANDIDATE.findAll(text).map { it.value }.toList()
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("Enter or scan a WIF private key")
        }
        val firstError = mutableListOf<String>()
        for (candidate in candidates) {
            try {
                return parse(candidate, network)
            } catch (e: IllegalArgumentException) {
                if (firstError.isEmpty()) firstError.add(e.message ?: "Invalid WIF private key")
            }
        }
        throw IllegalArgumentException(firstError.firstOrNull() ?: "Invalid WIF private key")
    }

    fun parse(wif: String, network: Network): WifPrivateKey {
        val payload = base58CheckDecode(wif.trim())
        val expectedVersion = when (network) {
            Network.BITCOIN -> 0x80
            Network.TESTNET, Network.TESTNET4, Network.REGTEST, Network.SIGNET -> 0xEF
        }
        val version = payload.firstOrNull()?.toInt()?.and(0xFF)
            ?: throw IllegalArgumentException("Invalid WIF private key")
        if (version != expectedVersion) {
            val expected = if (expectedVersion == 0x80) "mainnet" else "testnet/signet/regtest"
            throw IllegalArgumentException("WIF private key is not for the active $expected network")
        }

        val keyPayload = payload.copyOfRange(1, payload.size)
        val compressed = when {
            keyPayload.size == 32 -> false
            keyPayload.size == 33 && keyPayload.last() == 0x01.toByte() -> true
            else -> throw IllegalArgumentException("WIF private key has an invalid payload length")
        }
        validatePrivateKey(keyPayload.copyOfRange(0, 32))
        return WifPrivateKey(wif.trim(), compressed)
    }

    fun fromRawPrivateKey(privateKey: ByteArray, network: Network, compressed: Boolean = true): WifPrivateKey {
        require(privateKey.size == 32) { "SATSCARD returned an invalid private key length" }
        validatePrivateKey(privateKey)
        val version = when (network) {
            Network.BITCOIN -> 0x80.toByte()
            Network.TESTNET, Network.TESTNET4, Network.REGTEST, Network.SIGNET -> 0xEF.toByte()
        }
        val payload = byteArrayOf(version) + privateKey + if (compressed) byteArrayOf(0x01) else ByteArray(0)
        return WifPrivateKey(base58CheckEncode(payload), compressed)
    }

    private fun validatePrivateKey(privateKey: ByteArray) {
        val scalar = BigInteger(1, privateKey)
        require(scalar > BigInteger.ZERO && scalar < SECP256K1_N) { "Private key is outside the secp256k1 range" }
    }

    private fun base58CheckDecode(input: String): ByteArray {
        val decoded = base58Decode(input)
        if (decoded.size < 5) throw IllegalArgumentException("Invalid WIF private key")
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val expected = sha256(sha256(payload)).copyOfRange(0, 4)
        if (!checksum.contentEquals(expected)) {
            throw IllegalArgumentException("WIF private key checksum is invalid")
        }
        return payload
    }

    private fun base58CheckEncode(payload: ByteArray): String {
        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
        return base58Encode(payload + checksum)
    }

    private fun base58Decode(input: String): ByteArray {
        var num = BigInteger.ZERO
        for (c in input) {
            val digit = ALPHABET.indexOf(c)
            if (digit < 0) throw IllegalArgumentException("Invalid Base58 character: $c")
            num = num.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = num.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val leadingZeros = input.takeWhile { it == '1' }.count()
        return ByteArray(leadingZeros) + bytes
    }

    private fun base58Encode(input: ByteArray): String {
        var num = BigInteger(1, input)
        val encoded = StringBuilder()
        while (num > BigInteger.ZERO) {
            val divRem = num.divideAndRemainder(BigInteger.valueOf(58))
            num = divRem[0]
            encoded.append(ALPHABET[divRem[1].toInt()])
        }
        repeat(input.takeWhile { it == 0.toByte() }.count()) {
            encoded.append('1')
        }
        return encoded.reverse().toString()
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }
}
