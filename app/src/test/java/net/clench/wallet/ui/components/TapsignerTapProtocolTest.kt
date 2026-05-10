package net.clench.wallet.ui.components

import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborEncoder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapsignerTapProtocolTest {

    @Test
    fun `select command uses Coinkite applet id`() {
        assertEquals(
            "00a404000ff0436f696e6b697465434152447631",
            TapsignerTapProtocol.selectAppletCommand().toHex()
        )
    }

    @Test
    fun `status command is a short Tap Protocol APDU`() {
        val command = TapsignerTapProtocol.statusCommand()

        assertEquals("00cb0000", command.take(4).toByteArray().toHex())
        assertEquals(command.size - 5, command[4].toInt() and 0xFF)
    }

    @Test
    fun `dump command includes slot number`() {
        val command = TapsignerTapProtocol.dumpCommand(3)

        assertEquals("00cb0000", command.take(4).toByteArray().toHex())
        assertTrue(command.toHex().contains("64736c6f7403"))
    }

    @Test
    fun `authenticated xpub command encodes xpub request`() {
        val cardPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 1 })
        val command = TapsignerTapProtocol.authenticatedXpubCommand(
            master = true,
            cardPubkey = cardPubkey,
            cardNonce = ByteArray(16) { (it + 1).toByte() },
            cvc = "123456".toCharArray()
        )

        assertEquals("00cb0000", command.take(4).toByteArray().toHex())
        assertEquals(command.size - 5, command[4].toInt() and 0xFF)
        assertTrue(command.toHex().contains("63636d646478707562"))
        assertTrue(command.toHex().contains("666d6173746572f5"))
    }

    @Test
    fun `status parser extracts Tapsigner metadata`() {
        val response = tapsignerStatusResponse()

        val status = TapsignerTapProtocol.parseStatusResponse(response)

        assertTrue(status.isTapsigner)
        assertFalse(status.isSatscard)
        assertEquals(CoinkiteTapCardKind.TAPSIGNER, status.kind)
        assertEquals("1.1.0", status.version)
        assertEquals(700553L, status.birthHeight)
        assertEquals("m/84'/0'/0'", status.displayPath)
        assertEquals(3L, status.numberOfBackups)
        assertEquals("Tapsigner detected: firmware 1.1.0, path m/84'/0'/0', 3 backups", status.summary())
        assertEquals(66, status.cardPubkeyHex?.length)
        assertEquals(32, status.cardNonceHex?.length)
    }

    @Test
    fun `Tapsigner status without path uses default BIP84 account path`() {
        val mainnetStatus = TapsignerTapProtocol.parseStatusResponse(tapsignerStatusWithoutPathResponse(testnet = false))
        val testnetStatus = TapsignerTapProtocol.parseStatusResponse(tapsignerStatusWithoutPathResponse(testnet = true))

        assertEquals(null, mainnetStatus.displayPath)
        assertEquals("m/84'/0'/0'", mainnetStatus.defaultTapsignerAccountPath)
        assertEquals(null, testnetStatus.displayPath)
        assertEquals("m/84'/1'/0'", testnetStatus.defaultTapsignerAccountPath)
    }

    @Test
    fun `status parser extracts Satscard metadata`() {
        val response = satscardStatusResponse()

        val status = TapsignerTapProtocol.parseStatusResponse(response)

        assertFalse(status.isTapsigner)
        assertTrue(status.isSatscard)
        assertEquals(CoinkiteTapCardKind.SATSCARD, status.kind)
        assertEquals("1.2.0", status.version)
        assertEquals(725000L, status.birthHeight)
        assertEquals("bc1qexampleaddress000000000000000000000000000", status.address)
        assertEquals(listOf(2L, 10L), status.slots)
        assertEquals(2L, status.activeSlot)
        assertEquals(10L, status.slotCount)
        assertEquals(false, status.isTestnet)
        assertEquals(false, status.isTampered)
        assertEquals(
            "SATSCARD detected: firmware 1.2.0, address bc1qexampleaddress000000000000000000000000000, 10 slots",
            status.summary()
        )
    }

    @Test
    fun `status parser rejects failed APDU status word`() {
        val failure = byteArrayOf(0x6D, 0x00)

        assertFalse(TapsignerTapProtocol.isSuccessResponse(failure))
        try {
            TapsignerTapProtocol.parseStatusResponse(failure)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("0x6D00"))
            return
        }
        error("Expected failed status word to be rejected")
    }

    @Test
    fun `unseal parser decrypts private key with session key`() {
        val sessionKey = ByteArray(32) { (it + 20).toByte() }
        val privateKey = ByteArray(32) { (it + 1).toByte() }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(privateKey)
        val address = CoinkiteTapCardVerifier.segwitAddress(pubkey, testnet = false)
        val verifiedSlot = VerifiedSatscardSlot(
            slot = 2L,
            pubkey = pubkey,
            address = address,
            isTestnet = false
        )
        val encrypted = privateKey.zip(sessionKey).map { (left, right) ->
            (left.toInt() xor right.toInt()).toByte()
        }.toByteArray()
        val response = satscardUnsealResponse(encrypted, pubkey)

        val result = TapsignerTapProtocol.parseSatscardUnsealResponse(response, sessionKey, verifiedSlot)

        assertEquals(2L, result.slot)
        assertTrue(result.privateKey.contentEquals(privateKey))
        assertEquals(66, result.publicKeyHex?.length)
        assertEquals(address, result.address)
    }

    @Test
    fun `xpub parser extracts raw serialized xpub and nonce`() {
        val rawXpub = ByteArray(78) { index -> index.toByte() }
        rawXpub[45] = 0x02
        val nonce = ByteArray(16) { index -> (index + 4).toByte() }
        val response = tapsignerXpubResponse(rawXpub, nonce)

        val result = TapsignerTapProtocol.parseTapsignerXpubResponse(response)

        assertTrue(result.xpub.contentEquals(rawXpub))
        assertTrue(result.cardNonce!!.contentEquals(nonce))
    }

    @Test
    fun `renders verified native segwit address for secp256k1 generator key`() {
        val privateKey = ByteArray(32).also { it[31] = 1 }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(privateKey)

        assertEquals(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            pubkey.toHex()
        )
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            CoinkiteTapCardVerifier.segwitAddress(pubkey, testnet = false)
        )
    }

    @Test
    fun `certificate recovery accepts compressed compact signature headers`() {
        val privateKey = ByteArray(32).also { it[31] = 1 }
        val expectedPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(privateKey)
        val digest = ByteArray(32) { index -> (index + 7).toByte() }
        val signature = compactSignature(privateKey, digest)

        val recovered = (31..34).firstNotNullOfOrNull { header ->
            CoinkiteTapCardVerifier.recoverPublicKey(digest, byteArrayOf(header.toByte()) + signature)
                ?.takeIf { it.contentEquals(expectedPubkey) }
        }

        assertTrue(recovered?.contentEquals(expectedPubkey) == true)
    }

    private fun tapsignerStatusResponse(): ByteArray {
        val path = listOf(0x80000054L, 0x80000000L, 0x80000000L)
        val map = CborBuilder().addMap()
            .put("proto", 1L)
            .put("ver", "1.1.0")
            .put("birth", 700553L)
            .put("tapsigner", true)
            .put("num_backups", 3L)
            .put("pubkey", ByteArray(33) { index -> if (index == 0) 0x02.toByte() else index.toByte() })
            .put("card_nonce", ByteArray(16) { index -> (index + 1).toByte() })
        val pathArray = map.putArray("path")
        path.forEach { pathArray.add(it) }
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(pathArray.end().end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun tapsignerStatusWithoutPathResponse(testnet: Boolean): ByteArray {
        val map = CborBuilder().addMap()
            .put("proto", 1L)
            .put("ver", "1.0.3")
            .put("birth", 700553L)
            .put("tapsigner", true)
            .put("testnet", testnet)
            .put("num_backups", 0L)
            .put("pubkey", ByteArray(33) { index -> if (index == 0) 0x02.toByte() else index.toByte() })
            .put("card_nonce", ByteArray(16) { index -> (index + 1).toByte() })
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(map.end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun satscardStatusResponse(): ByteArray {
        val map = CborBuilder().addMap()
            .put("proto", 1L)
            .put("ver", "1.2.0")
            .put("birth", 725000L)
            .put("tapsigner", false)
            .put("addr", "bc1qexampleaddress000000000000000000000000000")
            .put("testnet", false)
            .put("tampered", false)
            .put("pubkey", ByteArray(33) { index -> if (index == 0) 0x02.toByte() else (index + 1).toByte() })
            .put("card_nonce", ByteArray(16) { index -> (index + 2).toByte() })
        val slotsArray = map.putArray("slots")
        listOf(2L, 10L).forEach { slotsArray.add(it) }
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(slotsArray.end().end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun satscardUnsealResponse(encryptedPrivateKey: ByteArray, pubkey: ByteArray): ByteArray {
        val map = CborBuilder().addMap()
            .put("slot", 2L)
            .put("privkey", encryptedPrivateKey)
            .put("pubkey", pubkey)
            .put("card_nonce", ByteArray(16) { index -> (index + 4).toByte() })
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(map.end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun tapsignerXpubResponse(xpub: ByteArray, cardNonce: ByteArray): ByteArray {
        val map = CborBuilder().addMap()
            .put("xpub", xpub)
            .put("card_nonce", cardNonce)
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(map.end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun compactSignature(privateKey: ByteArray, digest: ByteArray): ByteArray {
        val params = SECNamedCurves.getByName("secp256k1")
        val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(BigInteger(1, privateKey), domain))
        val components = signer.generateSignature(digest)
        val r = components[0]
        val s = components[1].let { if (it > domain.n.shiftRight(1)) domain.n.subtract(it) else it }
        return r.toFixed32() + s.toFixed32()
    }

    private fun BigInteger.toFixed32(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
