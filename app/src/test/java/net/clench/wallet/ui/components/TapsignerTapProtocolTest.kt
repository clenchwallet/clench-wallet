package net.clench.wallet.ui.components

import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborEncoder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun `wait command encodes unauthenticated retry delay request`() {
        val command = TapsignerTapProtocol.waitCommand()

        assertEquals("00cb0000", command.take(4).toByteArray().toHex())
        assertEquals(command.size - 5, command[4].toInt() and 0xFF)
        assertTrue(command.toHex().contains("63636d646477616974"))
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
    fun `authenticated new command encodes Tapsigner initialize request`() {
        val cardPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 1 })
        val command = TapsignerTapProtocol.authenticatedNewTapsignerCommand(
            cardPubkey = cardPubkey,
            cardNonce = ByteArray(16) { (it + 1).toByte() },
            cvc = "123456".toCharArray(),
            chainCode = ByteArray(32) { (it + 10).toByte() }
        )

        assertEquals("00cb0000", command.take(4).toByteArray().toHex())
        assertEquals(command.size - 5, command[4].toInt() and 0xFF)
        assertTrue(command.toHex().contains("63636d64636e6577"))
        assertTrue(command.toHex().contains("6a636861696e5f636f64655820"))
    }

    @Test
    fun `authenticated new command encodes Satscard active slot setup request`() {
        val cardPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 1 })
        val command = TapsignerTapProtocol.authenticatedNewSatscardCommand(
            slot = 4L,
            cardPubkey = cardPubkey,
            cardNonce = ByteArray(16) { (it + 1).toByte() },
            cvc = "123456".toCharArray(),
            chainCode = ByteArray(32) { (it + 11).toByte() }
        )

        assertEquals("00cb0000", command.take(4).toByteArray().toHex())
        assertEquals(command.size - 5, command[4].toInt() and 0xFF)
        assertTrue(command.toHex().contains("63636d64636e6577"))
        assertTrue(command.toHex().contains("64736c6f7404"))
        assertTrue(command.toHex().contains("6a636861696e5f636f64655820"))
    }

    @Test
    fun `authenticated derive command encodes BIP48 multisig path`() {
        val cardPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 1 })
        val command = TapsignerTapProtocol.authenticatedDeriveCommand(
            path = listOf(0x80000030L, 0x80000000L, 0x80000000L, 0x80000002L),
            nonce = ByteArray(16) { (it + 3).toByte() },
            cardPubkey = cardPubkey,
            cardNonce = ByteArray(16) { (it + 1).toByte() },
            cvc = "123456".toCharArray()
        )

        assertEquals("00cb0000", command.take(4).toByteArray().toHex())
        assertEquals(command.size - 5, command[4].toInt() and 0xFF)
        assertTrue(command.toHex().contains("63636d6466646572697665"))
        assertTrue(command.toHex().contains("6470617468841a800000301a800000001a800000001a80000002"))
        assertTrue(command.toHex().contains("656e6f6e636550"))
    }

    @Test
    fun `Tapsigner proof command encrypts secret challenge and fixes slot and subpath`() {
        val cardPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 1 })
        val challenge = ByteArray(32) { (it + 91).toByte() }
        val command = TapsignerTapProtocol.authenticatedTapsignerProofCommand(
            challenge = challenge,
            cardPubkey = cardPubkey,
            cardNonce = ByteArray(16) { (it + 1).toByte() },
            cvc = "123456".toCharArray()
        )
        try {
            assertEquals("00cb0000", command.take(4).toByteArray().toHex())
            assertTrue(command.toHex().contains("63636d64647369676e"))
            assertTrue(command.toHex().contains("64736c6f7400"))
            assertTrue(command.toHex().contains("677375627061746880"))
            assertTrue(command.toHex().contains("666469676573745820"))
            assertTrue(command.toHex().contains("67657075626b65795821"))
            assertFalse(command.toHex().contains(challenge.toHex()))
        } finally {
            command.fill(0)
            challenge.fill(0)
        }
    }

    @Test
    fun `SATSCARD read remains unauthenticated and unchanged`() {
        val nonce = ByteArray(16) { (it + 1).toByte() }
        val command = TapsignerTapProtocol.readCommand(nonce)
        val hex = command.toHex()

        assertTrue(hex.contains("63636d646472656164"))
        assertTrue(hex.contains("656e6f6e636550${nonce.toHex()}"))
        assertFalse(hex.contains("657075626b6579"))
        assertFalse(hex.contains("6478637663"))
        assertFalse(hex.contains("66646967657374"))
    }

    @Test
    fun `authenticated backup command encodes Tapsigner backup request`() {
        val cardPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 1 })
        val command = TapsignerTapProtocol.authenticatedBackupCommand(
            cardPubkey = cardPubkey,
            cardNonce = ByteArray(16) { (it + 1).toByte() },
            cvc = "123456".toCharArray()
        )

        assertEquals("00cb0000", command.take(4).toByteArray().toHex())
        assertEquals(command.size - 5, command[4].toInt() and 0xFF)
        assertTrue(command.toHex().contains("63636d64666261636b7570"))
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
        assertEquals("TAPSIGNER detected: firmware 1.1.0, path m/84'/0'/0', 3 backups", status.summary())
        assertEquals(66, status.cardPubkeyHex?.length)
        assertEquals(32, status.cardNonceHex?.length)
    }

    @Test
    fun `wait parser extracts remaining auth delay`() {
        val response = waitResponse(authDelay = 14L)

        val result = TapsignerTapProtocol.parseWaitResponse(response)

        assertEquals(14L, result.authDelaySeconds)
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
    fun `Tapsigner import paths distinguish single sig and multisig`() {
        assertEquals(
            "m/84'/0'/0'",
            TapsignerNfcReader.formatDerivationPath(TapsignerNfcReader.singleSigAccountPath(isTestnet = false))
        )
        assertEquals(
            "m/84'/1'/0'",
            TapsignerNfcReader.formatDerivationPath(TapsignerNfcReader.singleSigAccountPath(isTestnet = true))
        )
        assertEquals(
            "m/48'/0'/0'/2'",
            TapsignerNfcReader.formatDerivationPath(TapsignerNfcReader.multisigAccountPath(isTestnet = false))
        )
        assertEquals(
            "m/48'/1'/0'/2'",
            TapsignerNfcReader.formatDerivationPath(TapsignerNfcReader.multisigAccountPath(isTestnet = true))
        )
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
        assertEquals("SATSCARD slot 3 unsealed", result.summary)
    }

    @Test
    fun `SATSCARD display slots are one-based`() {
        assertEquals(1L, satscardDisplaySlot(0L))
        assertEquals(10L, satscardDisplaySlot(9L))
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
    fun `master xpub must preserve wallet provided chain code`() {
        val chainCode = ByteArray(32) { index -> (index + 1).toByte() }
        val rawXpub = ByteArray(78)
        chainCode.copyInto(rawXpub, destinationOffset = 13)

        TapsignerTapProtocol.requireMasterXpubChainCode(rawXpub, chainCode)

        val substituted = chainCode.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.requireMasterXpubChainCode(rawXpub, substituted)
        }
    }

    @Test
    fun `new parser extracts initialized slot and nonce`() {
        val nonce = ByteArray(16) { index -> (index + 5).toByte() }
        val response = tapsignerNewResponse(slot = 0L, cardNonce = nonce)

        val result = TapsignerTapProtocol.parseTapsignerNewResponse(response)

        assertEquals(0L, result.slot)
        assertTrue(result.cardNonce.contentEquals(nonce))
    }

    @Test
    fun `derive parser extracts returned pubkeys and nonce`() {
        val chainCode = ByteArray(32) { index -> (index + 1).toByte() }
        val masterPubkey = ByteArray(33) { index -> if (index == 0) 0x02.toByte() else index.toByte() }
        val pubkey = ByteArray(33) { index -> if (index == 0) 0x03.toByte() else (index + 1).toByte() }
        val nonce = ByteArray(16) { index -> (index + 5).toByte() }
        val response = tapsignerDeriveResponse(chainCode, masterPubkey, pubkey, nonce)

        val result = TapsignerTapProtocol.parseTapsignerDeriveResponse(response)

        assertEquals(64, result.signature.size)
        assertTrue(result.chainCode.contentEquals(chainCode))
        assertTrue(result.masterPubkey.contentEquals(masterPubkey))
        assertTrue(result.pubkey.contentEquals(pubkey))
        assertTrue(result.cardNonce.contentEquals(nonce))
    }

    @Test
    fun `derive proof verifies nonce-bound response and rejects substitution`() {
        val privateKey = ByteArray(32).also { it[31] = 7 }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(privateKey)
        val previousCardNonce = ByteArray(16) { (it + 1).toByte() }
        val requestNonce = ByteArray(16) { (it + 21).toByte() }
        val chainCode = ByteArray(32) { (it + 41).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "OPENDIME".toByteArray(Charsets.US_ASCII) +
                previousCardNonce + requestNonce + chainCode
        )
        val derive = TapsignerDeriveResult(
            signature = compactSignature(privateKey, digest),
            chainCode = chainCode,
            masterPubkey = pubkey,
            pubkey = pubkey,
            cardNonce = ByteArray(16) { (it + 61).toByte() }
        )

        CoinkiteTapCardVerifier.verifyTapsignerDerive(previousCardNonce, requestNonce, derive)

        val substituted = derive.copy(chainCode = chainCode.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerDerive(previousCardNonce, requestNonce, substituted)
        }
    }

    @Test
    fun `Tapsigner secret challenge proves expected derived key possession`() {
        val privateKey = ByteArray(32).also { it[31] = 15 }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(privateKey)
        val challenge = ByteArray(32) { (it + 17).toByte() }
        val proof = tapsignerSignProof(privateKey, challenge)

        CoinkiteTapCardVerifier.verifyTapsignerProofOfPossession(
            challenge = challenge,
            proof = proof,
            expectedDerivedPubkey = pubkey
        )
    }

    @Test
    fun `active relay cannot substitute a self consistent key and signature tuple`() {
        val expectedPrivateKey = ByteArray(32).also { it[31] = 16 }
        val expectedPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(expectedPrivateKey)
        val attackerPrivateKey = ByteArray(32).also { it[31] = 17 }
        val challenge = ByteArray(32) { (it + 18).toByte() }
        val attackerProof = tapsignerSignProof(attackerPrivateKey, challenge)

        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerProofOfPossession(
                challenge = challenge,
                proof = attackerProof,
                expectedDerivedPubkey = expectedPubkey
            )
        }
    }

    @Test
    fun `Tapsigner proof rejects replay and wrong session decryption`() {
        val privateKey = ByteArray(32).also { it[31] = 18 }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(privateKey)
        val oldChallenge = ByteArray(32) { (it + 19).toByte() }
        val freshChallenge = ByteArray(32) { (it + 51).toByte() }
        val replayedProof = tapsignerSignProof(privateKey, oldChallenge)

        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerProofOfPossession(
                challenge = freshChallenge,
                proof = replayedProof,
                expectedDerivedPubkey = pubkey
            )
        }

        val commandSession = ByteArray(32) { (it + 71).toByte() }
        val wrongCardSession = commandSession.copyOf().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        val digestSeenByWrongSession = ByteArray(32) { index ->
            (freshChallenge[index].toInt() xor
                commandSession[index].toInt() xor
                wrongCardSession[index].toInt()).toByte()
        }
        val wrongSessionProof = tapsignerSignProof(privateKey, digestSeenByWrongSession)
        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerProofOfPossession(
                challenge = freshChallenge,
                proof = wrongSessionProof,
                expectedDerivedPubkey = pubkey
            )
        }
    }

    @Test
    fun `Tapsigner proof requires slot zero`() {
        val privateKey = ByteArray(32).also { it[31] = 19 }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(privateKey)
        val challenge = ByteArray(32) { (it + 20).toByte() }

        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerProofOfPossession(
                challenge = challenge,
                proof = tapsignerSignProof(privateKey, challenge).copy(slot = 1L),
                expectedDerivedPubkey = pubkey
            )
        }
    }

    @Test
    fun `Tapsigner continuity binds certified card path and response nonce`() {
        val status = TapsignerTapProtocol.parseStatusResponse(tapsignerStatusResponse())
        val cardPubkey = status.cardPubkeyHex!!.hexToBytes()
        val cardNonce = status.cardNonceHex!!.hexToBytes()
        val path = listOf(0x80000054L, 0x80000000L, 0x80000000L)

        val verifiedNonce = TapsignerNfcReader.requireTapsignerContinuity(
            status = status,
            expectedCardPubkey = cardPubkey,
            expectedPath = path,
            expectedCardNonce = cardNonce
        )
        assertTrue(verifiedNonce.contentEquals(cardNonce))

        assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.requireTapsignerContinuity(
                status = status,
                expectedCardPubkey = cardPubkey.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() },
                expectedPath = path,
                expectedCardNonce = cardNonce
            )
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.requireTapsignerContinuity(
                status = status,
                expectedCardPubkey = cardPubkey,
                expectedPath = TapsignerNfcReader.multisigAccountPath(isTestnet = false),
                expectedCardNonce = cardNonce
            )
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.requireTapsignerContinuity(
                status = status,
                expectedCardPubkey = cardPubkey,
                expectedPath = path,
                expectedCardNonce = cardNonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            )
        }
    }

    @Test
    fun `account xpub must match nonce-verified derivation key and chain code`() {
        val chainCode = ByteArray(32) { (it + 3).toByte() }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 9 })
        val rawXpub = ByteArray(78).also {
            chainCode.copyInto(it, destinationOffset = 13)
            pubkey.copyInto(it, destinationOffset = 45)
        }

        TapsignerTapProtocol.requireTapsignerXpubBinding(rawXpub, chainCode, pubkey)

        val substituted = rawXpub.copyOf().also { it[77] = (it[77].toInt() xor 1).toByte() }
        assertThrows(IllegalStateException::class.java) {
            TapsignerTapProtocol.requireTapsignerXpubBinding(substituted, chainCode, pubkey)
        }
    }

    @Test
    fun `backup parser extracts encrypted data and nonce`() {
        val data = "encrypted backup".toByteArray()
        val nonce = ByteArray(16) { index -> (index + 8).toByte() }
        val response = tapsignerBackupResponse(data, nonce)

        val result = TapsignerTapProtocol.parseTapsignerBackupResponse(response)

        assertTrue(result.data.contentEquals(data))
        assertTrue(result.cardNonce.contentEquals(nonce))
    }

    @Test
    fun `sign proof parser enforces response shapes`() {
        val privateKey = ByteArray(32).also { it[31] = 20 }
        val challenge = ByteArray(32) { (it + 21).toByte() }
        val proof = tapsignerSignProof(privateKey, challenge)
        val response = tapsignerSignResponse(proof)

        val parsed = TapsignerTapProtocol.parseTapsignerSignProof(response)
        assertEquals(0L, parsed.slot)
        assertTrue(parsed.signature.contentEquals(proof.signature))
        assertTrue(parsed.pubkey.contentEquals(proof.pubkey))
        assertTrue(parsed.cardNonce.contentEquals(proof.cardNonce))
    }

    @Test
    fun `xpub parser preserves Coinkite error code`() {
        val response = coinkiteErrorResponse(code = 406L, error = "invalid state")

        try {
            TapsignerTapProtocol.parseTapsignerXpubResponse(response)
        } catch (e: CoinkiteTapCardException) {
            assertEquals(406L, e.code)
            assertEquals("invalid state", e.cardError)
            assertTrue(e.message!!.contains("406"))
            return
        }
        error("Expected Coinkite Tap error to be preserved")
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

    private fun waitResponse(authDelay: Long): ByteArray {
        val map = CborBuilder().addMap()
            .put("success", true)
            .put("auth_delay", authDelay)
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

    private fun tapsignerNewResponse(slot: Long, cardNonce: ByteArray): ByteArray {
        val map = CborBuilder().addMap()
            .put("slot", slot)
            .put("card_nonce", cardNonce)
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(map.end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun tapsignerDeriveResponse(
        chainCode: ByteArray,
        masterPubkey: ByteArray,
        pubkey: ByteArray,
        cardNonce: ByteArray
    ): ByteArray {
        val map = CborBuilder().addMap()
            .put("sig", ByteArray(64) { index -> (index + 1).toByte() })
            .put("chain_code", chainCode)
            .put("master_pubkey", masterPubkey)
            .put("pubkey", pubkey)
            .put("card_nonce", cardNonce)
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(map.end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun tapsignerBackupResponse(data: ByteArray, cardNonce: ByteArray): ByteArray {
        val map = CborBuilder().addMap()
            .put("data", data)
            .put("card_nonce", cardNonce)
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(map.end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun tapsignerSignResponse(proof: TapsignerSignProof): ByteArray {
        val map = CborBuilder().addMap()
            .put("slot", proof.slot)
            .put("sig", proof.signature)
            .put("pubkey", proof.pubkey)
            .put("card_nonce", proof.cardNonce)
        val out = ByteArrayOutputStream()
        CborEncoder(out).encode(map.end().build())
        return out.toByteArray() + byteArrayOf(0x90.toByte(), 0x00)
    }

    private fun coinkiteErrorResponse(code: Long, error: String): ByteArray {
        val map = CborBuilder().addMap()
            .put("code", code)
            .put("error", error)
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

    private fun tapsignerSignProof(
        privateKey: ByteArray,
        challenge: ByteArray
    ): TapsignerSignProof {
        return TapsignerSignProof(
            slot = 0L,
            signature = compactSignature(privateKey, challenge),
            pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(privateKey),
            cardNonce = ByteArray(16) { (it + 111).toByte() }
        )
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
