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
            subpath = listOf(0L, 0L),
            cardPubkey = cardPubkey,
            cardNonce = ByteArray(16) { (it + 1).toByte() },
            cvc = "123456".toCharArray()
        )
        try {
            assertEquals("00cb0000", command.take(4).toByteArray().toHex())
            assertTrue(command.toHex().contains("63636d64647369676e"))
            assertTrue(command.toHex().contains("64736c6f7400"))
            assertTrue(command.toHex().contains("6773756270617468820000"))
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
    fun `status parser accepts bounded indefinite CBOR used by real cards`() {
        val pubkeyTail = ByteArray(32) { index -> (index + 1).toByte() }
        val nonce = ByteArray(16) { index -> (index + 1).toByte() }
        val response = (
            "bf" +
                "6570726f746f01" +
                "637665727f63312e31622e30ff" +
                "697461707369676e6572f5" +
                "6b6e756d5f6261636b75707303" +
                "667075626b65795f41025820${pubkeyTail.toHex()}ff" +
                "6a636172645f6e6f6e63655f48${nonce.copyOfRange(0, 8).toHex()}" +
                "48${nonce.copyOfRange(8, 16).toHex()}ff" +
                "64706174689f1a800000541a800000001a80000000ffff" +
                "9000"
            ).hexToBytes()

        val status = TapsignerTapProtocol.parseStatusResponse(response)

        assertEquals(CoinkiteTapCardKind.TAPSIGNER, status.kind)
        assertEquals("1.1.0", status.version)
        assertEquals("m/84'/0'/0'", status.displayPath)
        assertEquals(3L, status.numberOfBackups)
        assertEquals("02${pubkeyTail.toHex()}", status.cardPubkeyHex)
        assertEquals(nonce.toHex(), status.cardNonceHex)
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
    fun `Tapsigner payment signing accepts network BIP84 and native Segwit BIP48 account zero`() {
        val mainnetPath = TapsignerNfcReader.singleSigAccountPath(isTestnet = false)
        val testnetPath = TapsignerNfcReader.singleSigAccountPath(isTestnet = true)
        val mainnetMultisigPath = TapsignerNfcReader.multisigAccountPath(isTestnet = false)
        val testnetMultisigPath = TapsignerNfcReader.multisigAccountPath(isTestnet = true)

        assertEquals(
            mainnetPath,
            TapsignerNfcReader.requireSupportedSigningPath(
                tapsignerSigningStatus(path = mainnetPath, isTestnet = false)
            )
        )
        assertEquals(
            testnetPath,
            TapsignerNfcReader.requireSupportedSigningPath(
                tapsignerSigningStatus(path = testnetPath, isTestnet = true)
            )
        )
        assertEquals(
            mainnetMultisigPath,
            TapsignerNfcReader.requireSupportedSigningPath(
                tapsignerSigningStatus(path = mainnetMultisigPath, isTestnet = false)
            )
        )
        assertEquals(
            testnetMultisigPath,
            TapsignerNfcReader.requireSupportedSigningPath(
                tapsignerSigningStatus(path = testnetMultisigPath, isTestnet = true)
            )
        )

        val customAccount = assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.requireSupportedSigningPath(
                tapsignerSigningStatus(
                    path = listOf(0x8000_0054L, 0x8000_0000L, 0x8000_0001L),
                    isTestnet = false
                )
            )
        }
        assertTrue(customAccount.message.orEmpty().contains("m/84'/0'/0'"))
        assertTrue(customAccount.message.orEmpty().contains("m/48'/0'/0'/2'"))
        assertTrue(customAccount.message.orEmpty().contains("m/84'/0'/1'"))

        val wrongNetworkPath = assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.requireSupportedSigningPath(
                tapsignerSigningStatus(path = mainnetPath, isTestnet = true)
            )
        }
        assertTrue(wrongNetworkPath.message.orEmpty().contains("testnet BIP84 account-0"))
        assertTrue(wrongNetworkPath.message.orEmpty().contains("m/84'/1'/0'"))
        assertTrue(wrongNetworkPath.message.orEmpty().contains("m/48'/1'/0'/2'"))
    }

    @Test
    fun `Tapsigner network gate accepts matching networks and rejects mismatch`() {
        TapsignerNfcReader.requireTapsignerNetwork(cardIsTestnet = null, expectedIsTestnet = false)
        TapsignerNfcReader.requireTapsignerNetwork(cardIsTestnet = false, expectedIsTestnet = false)
        TapsignerNfcReader.requireTapsignerNetwork(cardIsTestnet = true, expectedIsTestnet = true)

        val mainCardInTestnet = assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.requireTapsignerNetwork(cardIsTestnet = false, expectedIsTestnet = true)
        }
        assertTrue(mainCardInTestnet.message!!.contains("configured for mainnet"))
        assertTrue(mainCardInTestnet.message!!.contains("Clench is set to testnet"))

        val testCardInMainnet = assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.requireTapsignerNetwork(cardIsTestnet = true, expectedIsTestnet = false)
        }
        assertTrue(testCardInMainnet.message!!.contains("configured for testnet"))
        assertTrue(testCardInMainnet.message!!.contains("Clench is set to mainnet"))
    }

    @Test
    fun `canonical Tapsigner BIP84 xpubs bind network path and proven account tuple`() {
        val chainCode = ByteArray(32) { (it + 3).toByte() }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 9 })

        listOf(false, true).forEach { isTestnet ->
            val path = TapsignerNfcReader.singleSigAccountPath(isTestnet)
            val returned = serializedAccountXpub(
                isTestnet = isTestnet,
                path = path,
                chainCode = chainCode,
                pubkey = pubkey,
                parentFingerprint = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
            )

            val encoded = TapsignerNfcReader.canonicalTapsignerAccountXpub(
                returnedXpub = returned,
                expectedChainCode = chainCode,
                expectedPubkey = pubkey,
                expectedPath = path,
                isTestnet = isTestnet
            )
            val decoded = base58CheckDecode(encoded)
            val expectedVersion = if (isTestnet) "043587cf" else "0488b21e"

            assertTrue(encoded.startsWith(if (isTestnet) "tpub" else "xpub"))
            assertEquals(78, decoded.size)
            assertEquals(expectedVersion, decoded.copyOfRange(0, 4).toHex())
            assertEquals(3, decoded[4].toInt() and 0xff)
            assertEquals("00000000", decoded.copyOfRange(5, 9).toHex())
            assertEquals(path.last(), decoded.copyOfRange(9, 13).toUInt32())
            assertTrue(decoded.copyOfRange(13, 45).contentEquals(chainCode))
            assertTrue(decoded.copyOfRange(45, 78).contentEquals(pubkey))
        }
    }

    @Test
    fun `canonical Tapsigner BIP48 xpub uses path depth and final hardened child`() {
        val path = TapsignerNfcReader.multisigAccountPath(isTestnet = false)
        val chainCode = ByteArray(32) { (it + 31).toByte() }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 12 })
        val returned = serializedAccountXpub(false, path, chainCode, pubkey)

        val decoded = base58CheckDecode(
            TapsignerNfcReader.canonicalTapsignerAccountXpub(
                returnedXpub = returned,
                expectedChainCode = chainCode,
                expectedPubkey = pubkey,
                expectedPath = path,
                isTestnet = false
            )
        )

        assertEquals(4, decoded[4].toInt() and 0xff)
        assertEquals(0x80000002L, decoded.copyOfRange(9, 13).toUInt32())
    }

    @Test
    fun `canonical Tapsigner xpub rejects untrusted version depth and child metadata`() {
        val path = TapsignerNfcReader.singleSigAccountPath(isTestnet = false)
        val chainCode = ByteArray(32) { (it + 43).toByte() }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 15 })
        val valid = serializedAccountXpub(false, path, chainCode, pubkey)

        val oppositeNetwork = valid.copyOf().also {
            byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xcf.toByte()).copyInto(it)
        }
        assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.canonicalTapsignerAccountXpub(
                oppositeNetwork, chainCode, pubkey, path, isTestnet = false
            )
        }

        val unknownVersion = valid.copyOf().also { it[0] = 0x05 }
        assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.canonicalTapsignerAccountXpub(
                unknownVersion, chainCode, pubkey, path, isTestnet = false
            )
        }

        val wrongDepth = valid.copyOf().also { it[4] = 2 }
        assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.canonicalTapsignerAccountXpub(
                wrongDepth, chainCode, pubkey, path, isTestnet = false
            )
        }

        val wrongChild = valid.copyOf().also { it[12] = 1 }
        assertThrows(IllegalStateException::class.java) {
            TapsignerNfcReader.canonicalTapsignerAccountXpub(
                wrongChild, chainCode, pubkey, path, isTestnet = false
            )
        }
    }

    @Test
    fun `canonical Tapsigner xpub discards unauthenticated parent fingerprint`() {
        val path = TapsignerNfcReader.singleSigAccountPath(isTestnet = false)
        val chainCode = ByteArray(32) { (it + 61).toByte() }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(ByteArray(32).also { it[31] = 18 })
        val zeroParent = serializedAccountXpub(false, path, chainCode, pubkey)
        val arbitraryParent = serializedAccountXpub(
            false,
            path,
            chainCode,
            pubkey,
            parentFingerprint = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        )

        val first = TapsignerNfcReader.canonicalTapsignerAccountXpub(
            zeroParent, chainCode, pubkey, path, isTestnet = false
        )
        val second = TapsignerNfcReader.canonicalTapsignerAccountXpub(
            arbitraryParent, chainCode, pubkey, path, isTestnet = false
        )

        assertEquals(first, second)
        assertEquals("00000000", base58CheckDecode(second).copyOfRange(5, 9).toHex())
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
    fun `non-empty documented derive profile is rejected because root is unbound`() {
        val masterPrivateKey = ByteArray(32).also { it[31] = 7 }
        val derivedPrivateKey = ByteArray(32).also { it[31] = 8 }
        val masterPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(masterPrivateKey)
        val derivedPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(derivedPrivateKey)
        val previousCardNonce = ByteArray(16) { (it + 1).toByte() }
        val requestNonce = ByteArray(16) { (it + 21).toByte() }
        val masterChainCode = ByteArray(32) { (it + 31).toByte() }
        val derivedChainCode = ByteArray(32) { (it + 41).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "OPENDIME".toByteArray(Charsets.US_ASCII) +
                previousCardNonce + requestNonce + derivedChainCode
        )
        val derive = TapsignerDeriveResult(
            signature = compactSignature(derivedPrivateKey, digest),
            chainCode = derivedChainCode,
            masterPubkey = masterPubkey,
            pubkey = derivedPubkey,
            cardNonce = ByteArray(16) { (it + 61).toByte() }
        )

        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerDerive(
                previousCardNonce,
                requestNonce,
                derive,
                masterPubkey,
                masterChainCode
            )
        }

        val invalidMasterPubkey = ByteArray(33) { 0xff.toByte() }.also { it[0] = 0x02 }
        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerDerive(
                previousCardNonce,
                requestNonce,
                derive.copy(masterPubkey = invalidMasterPubkey),
                invalidMasterPubkey,
                masterChainCode
            )
        }
    }

    @Test
    fun `production-profile derive proof uses master key and master chain code`() {
        val masterPrivateKey = ByteArray(32).also { it[31] = 9 }
        val derivedPrivateKey = ByteArray(32).also { it[31] = 10 }
        val masterPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(masterPrivateKey)
        val derivedPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(derivedPrivateKey)
        val previousCardNonce = ByteArray(16) { (it + 2).toByte() }
        val requestNonce = ByteArray(16) { (it + 22).toByte() }
        val masterChainCode = ByteArray(32) { (it + 32).toByte() }
        val derivedChainCode = ByteArray(32) { (it + 42).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "OPENDIME".toByteArray(Charsets.US_ASCII) +
                previousCardNonce + requestNonce + masterChainCode
        )
        val derive = TapsignerDeriveResult(
            signature = compactSignature(masterPrivateKey, digest),
            chainCode = derivedChainCode,
            masterPubkey = masterPubkey,
            pubkey = derivedPubkey,
            cardNonce = ByteArray(16) { (it + 62).toByte() }
        )

        CoinkiteTapCardVerifier.verifyTapsignerDerive(
            previousCardNonce,
            requestNonce,
            derive,
            masterPubkey,
            masterChainCode
        )

        val substitutedMasterChainCode = masterChainCode.copyOf().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerDerive(
                previousCardNonce,
                requestNonce,
                derive,
                masterPubkey,
                substitutedMasterChainCode
            )
        }

        val differentMasterPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(
            ByteArray(32).also { it[31] = 13 }
        )
        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerDerive(
                previousCardNonce,
                requestNonce,
                derive,
                differentMasterPubkey,
                masterChainCode
            )
        }

        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerDerive(
                derive.cardNonce,
                requestNonce,
                derive,
                masterPubkey,
                masterChainCode
            )
        }
        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerDerive(
                previousCardNonce,
                requestNonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
                derive,
                masterPubkey,
                masterChainCode
            )
        }

        val derivedDigest = MessageDigest.getInstance("SHA-256").digest(
            "OPENDIME".toByteArray(Charsets.US_ASCII) +
                previousCardNonce + requestNonce + derivedChainCode
        )
        val masterKeyDerivedChain = derive.copy(
            signature = compactSignature(masterPrivateKey, derivedDigest)
        )
        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerDerive(
                previousCardNonce,
                requestNonce,
                masterKeyDerivedChain,
                masterPubkey,
                masterChainCode
            )
        }

        val derivedKeyMasterChain = derive.copy(
            signature = compactSignature(derivedPrivateKey, digest)
        )
        assertThrows(IllegalStateException::class.java) {
            CoinkiteTapCardVerifier.verifyTapsignerDerive(
                previousCardNonce,
                requestNonce,
                derivedKeyMasterChain,
                masterPubkey,
                masterChainCode
            )
        }
    }

    @Test
    fun `indefinite derive response verifies production transcript`() {
        val masterPrivateKey = ByteArray(32).also { it[31] = 11 }
        val derivedPrivateKey = ByteArray(32).also { it[31] = 12 }
        val masterPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(masterPrivateKey)
        val derivedPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(derivedPrivateKey)
        val previousCardNonce = ByteArray(16) { (it + 3).toByte() }
        val requestNonce = ByteArray(16) { (it + 23).toByte() }
        val masterChainCode = ByteArray(32) { (it + 33).toByte() }
        val derivedChainCode = ByteArray(32) { (it + 43).toByte() }
        val responseCardNonce = ByteArray(16) { (it + 63).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "OPENDIME".toByteArray(Charsets.US_ASCII) +
                previousCardNonce + requestNonce + masterChainCode
        )
        val response = indefiniteTapsignerDeriveResponse(
            signature = compactSignature(masterPrivateKey, digest),
            chainCode = derivedChainCode,
            masterPubkey = masterPubkey,
            pubkey = derivedPubkey,
            cardNonce = responseCardNonce
        )

        val parsed = TapsignerTapProtocol.parseTapsignerDeriveResponse(response)

        assertTrue(parsed.chainCode.contentEquals(derivedChainCode))
        assertTrue(parsed.masterPubkey.contentEquals(masterPubkey))
        assertTrue(parsed.pubkey.contentEquals(derivedPubkey))
        assertTrue(parsed.cardNonce.contentEquals(responseCardNonce))
        CoinkiteTapCardVerifier.verifyTapsignerDerive(
            previousCardNonce,
            requestNonce,
            parsed,
            masterPubkey,
            masterChainCode
        )
    }

    @Test
    fun `unhardened public derivation matches BIP32 vector one`() {
        // BIP32 test vector 1: m/0'/1/2' -> 2 -> 1000000000.
        val parentXpub = (
            "0488b21e03bef5a2f980000002" +
                "04466b9cc8e161e966409ca52986c584f07e9dc81f735db683c3ff6ec7b15" +
                "03f0357bfe1e341d01c69fe5654309956cbea516822fba8a601743a012a7896ee8dc2"
            ).hexToBytes()
        val expectedPubkey =
            "022a471424da5e657499d1ff51cb43c47481a03b1e77f951fe64cec9f5a48f7011".hexToBytes()

        val actual = CoinkiteTapCardVerifier.deriveUnhardenedPublicKey(
            parentPubkey = parentXpub.copyOfRange(45, 78),
            parentChainCode = parentXpub.copyOfRange(13, 45),
            subpath = listOf(2L, 1_000_000_000L)
        )
        val substitutedChainCode = parentXpub.copyOfRange(13, 45).also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        val substituted = CoinkiteTapCardVerifier.deriveUnhardenedPublicKey(
            parentPubkey = parentXpub.copyOfRange(45, 78),
            parentChainCode = substitutedChainCode,
            subpath = listOf(2L, 1_000_000_000L)
        )

        assertTrue(actual.contentEquals(expectedPubkey))
        assertFalse(substituted.contentEquals(expectedPubkey))
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
    fun `account xpub must match child-key-verified account key and chain code`() {
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

    private fun tapsignerSigningStatus(
        path: List<Long>,
        isTestnet: Boolean?
    ): CoinkiteTapCardStatus = CoinkiteTapCardStatus(
        isTapsigner = true,
        version = "1.1.0",
        birthHeight = 700553L,
        derivationPath = path,
        numberOfBackups = 3L,
        authDelaySeconds = 0L,
        cardPubkeyHex = null,
        cardNonceHex = null,
        address = null,
        slots = null,
        isTestnet = isTestnet,
        isTampered = false
    )

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

    private fun indefiniteTapsignerDeriveResponse(
        signature: ByteArray,
        chainCode: ByteArray,
        masterPubkey: ByteArray,
        pubkey: ByteArray,
        cardNonce: ByteArray
    ): ByteArray {
        var body = byteArrayOf(0xbf.toByte())
        listOf(
            "sig" to signature,
            "chain_code" to chainCode,
            "master_pubkey" to masterPubkey,
            "pubkey" to pubkey,
            "card_nonce" to cardNonce
        ).forEach { (key, value) ->
            val keyBytes = key.toByteArray(Charsets.US_ASCII)
            require(keyBytes.size < 24)
            val split = value.size / 2
            body += byteArrayOf((0x60 + keyBytes.size).toByte()) + keyBytes
            body += byteArrayOf(0x5f.toByte())
            body += cborByteString(value.copyOfRange(0, split))
            body += cborByteString(value.copyOfRange(split, value.size))
            body += byteArrayOf(0xff.toByte())
        }
        return body + byteArrayOf(0xff.toByte(), 0x90.toByte(), 0x00)
    }

    private fun cborByteString(value: ByteArray): ByteArray {
        return if (value.size < 24) {
            byteArrayOf((0x40 + value.size).toByte()) + value
        } else {
            byteArrayOf(0x58, value.size.toByte()) + value
        }
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

    private fun serializedAccountXpub(
        isTestnet: Boolean,
        path: List<Long>,
        chainCode: ByteArray,
        pubkey: ByteArray,
        parentFingerprint: ByteArray = ByteArray(4)
    ): ByteArray {
        require(path.isNotEmpty())
        require(chainCode.size == 32)
        require(pubkey.size == 33)
        require(parentFingerprint.size == 4)
        val serialized = ByteArray(78)
        val version = if (isTestnet) {
            byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xcf.toByte())
        } else {
            byteArrayOf(0x04, 0x88.toByte(), 0xb2.toByte(), 0x1e)
        }
        val child = path.last()
        version.copyInto(serialized, destinationOffset = 0)
        serialized[4] = path.size.toByte()
        parentFingerprint.copyInto(serialized, destinationOffset = 5)
        serialized[9] = (child ushr 24).toByte()
        serialized[10] = (child ushr 16).toByte()
        serialized[11] = (child ushr 8).toByte()
        serialized[12] = child.toByte()
        chainCode.copyInto(serialized, destinationOffset = 13)
        pubkey.copyInto(serialized, destinationOffset = 45)
        return serialized
    }

    private fun base58CheckDecode(encoded: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var value = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        encoded.forEach { char ->
            val digit = alphabet.indexOf(char)
            require(digit >= 0) { "Invalid Base58 character" }
            value = value.multiply(base).add(BigInteger.valueOf(digit.toLong()))
        }
        val magnitude = value.toByteArray().let { bytes ->
            if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
        val decoded = ByteArray(encoded.takeWhile { it == '1' }.length) + magnitude
        require(decoded.size >= 4)
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val digest = MessageDigest.getInstance("SHA-256")
        val expected = digest.digest(digest.digest(payload)).copyOfRange(0, 4)
        require(MessageDigest.isEqual(checksum, expected)) { "Invalid Base58Check checksum" }
        return payload
    }

    private fun ByteArray.toUInt32(): Long {
        require(size == 4)
        return ((this[0].toLong() and 0xffL) shl 24) or
            ((this[1].toLong() and 0xffL) shl 16) or
            ((this[2].toLong() and 0xffL) shl 8) or
            (this[3].toLong() and 0xffL)
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
