package net.clench.wallet.ui.components

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TapsignerPsbtSigningTest {
    @Test
    fun `BIP143 native P2WPKH SIGHASH_ALL matches official vector`() {
        val unsignedTransaction = (
            "0100000002" +
                "fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f" +
                "0000000000eeffffff" +
                "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a" +
                "0100000000ffffffff" +
                "02" +
                "202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac" +
                "9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac" +
                "11000000"
            ).hexToBytes()
        val scriptCode = "76a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac".hexToBytes()
        val amount = "0046c32300000000".hexToBytes()

        val digest = TapsignerPsbtSigning.bip143SighashAll(
            unsignedTransaction = unsignedTransaction,
            inputIndex = 1,
            scriptCode = scriptCode,
            amountLittleEndian = amount
        )

        assertArrayEquals(
            "c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670".hexToBytes(),
            digest
        )
    }

    @Test
    fun `BIP48 native P2WSH SIGHASH_ALL matches Sparrow Drongo oracle`() {
        // Fixed independently by Sparrow/Drongo's TransactionTest
        // verifyP2SHP2WSHSigHashAll vector. BIP143 commits to the witness script
        // and amount, so the same oracle applies when that script is spent as
        // native P2WSH. No Sparrow classes are used at runtime or by this test.
        val unsignedTransaction = (
            "0100000001" +
                "36641869ca081e70f394c6948e8af409e18b619df2ed74aa106c1ca29787b96e" +
                "0100000000ffffffff" +
                "02" +
                "00e9a435000000001976a914389ffce9cd9ae88dcc0631e88a821ffdbe9bfe2688ac" +
                "c0832f05000000001976a9147480a33f950689af511e6e84c138dbbd3c3ee41588ac" +
                "00000000"
            ).hexToBytes()
        val witnessScript = (
            "56" +
                "210307b8ae49ac90a048e9b53357a2354b3334e9c8bee813ecb98e99a7e07e8c3ba3" +
                "2103b28f0c28bfab54554ae8c658ac5c3e0ce6e79ad336331f78c428dd43eea8449b" +
                "21034b8113d703413d57761b8b9781957b8c0ac1dfe69f492580ca4195f50376ba4a" +
                "21033400f6afecb833092a9a21cfdf1ed1376e58c5d1f47de74683123987e967a8f4" +
                "2103a6d48b1131e94ba04d9737d61acdaa1322008af9602b3b14862c07a1789aac16" +
                "2102d8b661b0b3302ee2f162b09e07a55ad5dfbe673a9f01d9f0c19617681024306b" +
                "56ae"
            ).hexToBytes()
        val oraclePubkey =
            "0307b8ae49ac90a048e9b53357a2354b3334e9c8bee813ecb98e99a7e07e8c3ba3".hexToBytes()
        val oracleDigest =
            "185c0be5263dce5b4bb50a047973c1b6272bfbd0103a89444597dc40b248ee7c".hexToBytes()
        val oracleCompactSignature = (
            "6ac44d672dac41f9b00e28f4df20c52eeb087207e8d758d76d92c6fab3b73e2b" +
                "367750dbbe19290069cba53d096f44530e4f98acaa594810388cf7409a1870ce"
            ).hexToBytes()
        val oracleDerSighashAll = (
            "304402206ac44d672dac41f9b00e28f4df20c52eeb087207e8d758d76d92c6fab3b73e2b" +
                "0220367750dbbe19290069cba53d096f44530e4f98acaa594810388cf7409a1870ce01"
            ).hexToBytes()
        val witnessUtxo = (
            "b168de3a00000000" +
                "22" +
                "0020a16b5755f7f6f96dbd65f5f0d6ab9418b89af4b1f14a1bb8a09062c35f0dcb54"
            ).hexToBytes()
        val derivation = ByteArrayOutputStream().apply {
            write("12345678".hexToBytes())
            (multisigAccountPath + listOf(0L, 17L)).forEach { component ->
                write(littleEndian(component, 4))
            }
        }.toByteArray()
        val rawPsbt = ByteArrayOutputStream().apply {
            write("70736274ff".hexToBytes())
            writeEntry(this, byteArrayOf(0x00), unsignedTransaction)
            write(0)
            writeEntry(this, byteArrayOf(0x01), witnessUtxo)
            writeEntry(this, byteArrayOf(0x05), witnessScript)
            writeEntry(this, byteArrayOf(0x06) + oraclePubkey, derivation)
            write(0)
            repeat(2) { write(0) }
        }.toByteArray()
        var plan: TapsignerPsbtSigning.Plan? = null
        var signedBytes: ByteArray? = null

        try {
            val prepared = TapsignerPsbtSigning.prepare(
                Base64.getEncoder().encodeToString(rawPsbt),
                multisigAccountPath
            )
            plan = prepared
            val request = prepared.requests.single()
            assertEquals(listOf(0L, 17L), request.subpath)
            assertArrayEquals(oraclePubkey, request.candidatePubkeys.single())
            assertArrayEquals(oracleDigest, request.digest)

            val signed = Base64.getDecoder().decode(
                TapsignerPsbtSigning.inject(
                    prepared,
                    listOf(
                        TapsignerPsbtSigning.Signature(
                            inputIndex = 0,
                            pubkey = oraclePubkey.copyOf(),
                            compactSignature = oracleCompactSignature.copyOf()
                        )
                    )
                )
            )
            signedBytes = signed
            val partial = prepared.parsed.inputs.single().entries.single { it.hasType(0x02) }
            assertArrayEquals(byteArrayOf(0x02) + oraclePubkey, partial.key)
            assertArrayEquals(oracleDerSighashAll, partial.value)
            assertTrue(signed.containsSubsequence(unsignedTransaction))
        } finally {
            plan?.clear()
            plan?.parsed?.clear()
            signedBytes?.fill(0)
            unsignedTransaction.fill(0)
            witnessScript.fill(0)
            oraclePubkey.fill(0)
            oracleDigest.fill(0)
            oracleCompactSignature.fill(0)
            oracleDerSighashAll.fill(0)
            witnessUtxo.fill(0)
            derivation.fill(0)
            rawPsbt.fill(0)
        }
    }

    @Test
    fun `sign command rejects hardened or overlong relative paths`() {
        val cardPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(
            ByteArray(32).also { it[31] = 1 }
        )
        val digest = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(16) { (it + 1).toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            TapsignerTapProtocol.authenticatedTapsignerSignCommand(
                digest,
                listOf(0x8000_0000L),
                cardPubkey,
                nonce,
                "123456".toCharArray()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TapsignerTapProtocol.authenticatedTapsignerSignCommand(
                digest,
                listOf(0L, 1L, 2L),
                cardPubkey,
                nonce,
                "123456".toCharArray()
            )
        }
    }

    @Test
    fun `native P2WPKH PSBT is prepared and receives a verified partial signature`() {
        val privateKey = BigInteger.ONE
        val publicKey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(toFixed32(privateKey))
        val publicKeyHex = publicKey.joinToString("") { "%02x".format(it) }
        val unsignedTransaction = (
            "0200000001" +
                "00".repeat(32) + "00000000" + "00" + "ffffffff" +
                "01" + "8403000000000000" + "16" + "0014" + "00".repeat(20) +
                "00000000"
            ).hexToBytes()
        val witnessUtxo = (
            "e803000000000000" + "16" +
                "0014751e76e8199196d454941c45d1b3a323f1433bd6"
            ).hexToBytes()
        val derivation = (
            "d34db33f" +
                "54000080" + "00000080" + "00000080" +
                "00000000" + "07000000"
            ).hexToBytes()
        val rawPsbt = (
            "70736274ff" +
                "0100" + "52" + unsignedTransaction.joinToString("") { "%02x".format(it) } + "00" +
                "0101" + "1f" + witnessUtxo.joinToString("") { "%02x".format(it) } +
                "22" + "06" + publicKeyHex + "18" + derivation.joinToString("") { "%02x".format(it) } +
                "00" +
                "00"
            ).hexToBytes()
        val accountPath = listOf(0x8000_0054L, 0x8000_0000L, 0x8000_0000L)
        val plan = TapsignerPsbtSigning.prepare(
            Base64.getEncoder().encodeToString(rawPsbt),
            accountPath
        )
        val compactSignature = signCompactLowS(privateKey, plan.requests.single().digest)

        try {
            assertEquals(listOf(0L, 7L), plan.requests.single().subpath)
            assertArrayEquals(publicKey, plan.requests.single().candidatePubkeys.single())

            val signedPsbt = TapsignerPsbtSigning.inject(
                plan,
                listOf(
                    TapsignerPsbtSigning.Signature(
                        inputIndex = 0,
                        pubkey = publicKey.copyOf(),
                        compactSignature = compactSignature.copyOf()
                    )
                )
            )
            val signedBytes = Base64.getDecoder().decode(signedPsbt)
            val partialSignatureKey = byteArrayOf(0x02) + publicKey
            assertTrue(signedBytes.containsSubsequence(unsignedTransaction))
            assertTrue(signedBytes.containsSubsequence(partialSignatureKey))
            val alreadySigned = assertThrows(IllegalArgumentException::class.java) {
                TapsignerPsbtSigning.prepare(signedPsbt, accountPath)
            }
            assertTrue(alreadySigned.message.orEmpty().contains("already contains a partial signature"))
            signedBytes.fill(0)
        } finally {
            plan.clear()
            plan.parsed.clear()
            privateKey.toByteArray().fill(0)
            publicKey.fill(0)
            compactSignature.fill(0)
            unsignedTransaction.fill(0)
            witnessUtxo.fill(0)
            derivation.fill(0)
            rawPsbt.fill(0)
        }
    }

    @Test
    fun `multi-input receive and change PSBT is signed without changing the transaction`() {
        val privateKeys = listOf(BigInteger.ONE, BigInteger.TWO)
        val fixture = buildTestPsbt(
            listOf(
                FixtureInput(privateKeys[0], branch = 0L, index = 7L),
                FixtureInput(privateKeys[1], branch = 1L, index = 3L)
            )
        )
        val plan = TapsignerPsbtSigning.prepare(fixture.base64, accountPath)
        val signatures = plan.requests.mapIndexed { index, request ->
            TapsignerPsbtSigning.Signature(
                inputIndex = request.inputIndex,
                pubkey = fixture.pubkeys[index].copyOf(),
                compactSignature = signCompactLowS(privateKeys[index], request.digest)
            )
        }

        try {
            assertEquals(2, plan.requests.size)
            assertEquals(listOf(0L, 7L), plan.requests[0].subpath)
            assertEquals(listOf(1L, 3L), plan.requests[1].subpath)

            val duplicate = assertThrows(IllegalArgumentException::class.java) {
                TapsignerPsbtSigning.inject(
                    plan,
                    listOf(
                        signatures[0],
                        signatures[0].copy(
                            pubkey = signatures[0].pubkey.copyOf(),
                            compactSignature = signatures[0].compactSignature.copyOf()
                        )
                    )
                )
            }
            assertTrue(duplicate.message.orEmpty().contains("duplicate input signatures"))

            val signedBytes = Base64.getDecoder().decode(
                TapsignerPsbtSigning.inject(plan, signatures)
            )
            try {
                assertTrue(signedBytes.containsSubsequence(fixture.unsignedTransaction))
                fixture.pubkeys.forEach { pubkey ->
                    assertTrue(signedBytes.containsSubsequence(byteArrayOf(0x02) + pubkey))
                }
            } finally {
                signedBytes.fill(0)
            }
        } finally {
            plan.clear()
            plan.parsed.clear()
            signatures.forEach { it.clear() }
            fixture.clear()
        }
    }

    @Test
    fun `prepare rejects PSBT v2 weak sighash unsupported scripts and mismatched keys`() {
        val versionTwo = buildTestPsbt(
            listOf(FixtureInput(BigInteger.ONE, branch = 0L, index = 0L)),
            psbtVersion = 2L
        )
        val weakSighash = buildTestPsbt(
            listOf(FixtureInput(BigInteger.ONE, branch = 0L, index = 0L, sighashType = 2L))
        )
        val p2wsh = buildTestPsbt(
            listOf(
                FixtureInput(
                    BigInteger.ONE,
                    branch = 0L,
                    index = 0L,
                    witnessProgramOverride = byteArrayOf(0x00, 0x20) + ByteArray(32)
                )
            )
        )
        val mismatchedKey = buildTestPsbt(
            listOf(
                FixtureInput(
                    signingPrivateKey = BigInteger.ONE,
                    branch = 0L,
                    index = 0L,
                    derivationPrivateKey = BigInteger.TWO
                )
            )
        )

        try {
            val versionError = assertThrows(IllegalArgumentException::class.java) {
                TapsignerPsbtSigning.prepare(versionTwo.base64, accountPath)
            }
            assertTrue(versionError.message.orEmpty().contains("PSBT v0"))

            val sighashError = assertThrows(IllegalArgumentException::class.java) {
                TapsignerPsbtSigning.prepare(weakSighash.base64, accountPath)
            }
            assertTrue(sighashError.message.orEmpty().contains("SIGHASH_ALL"))

            val scriptError = assertThrows(IllegalArgumentException::class.java) {
                TapsignerPsbtSigning.prepare(p2wsh.base64, accountPath)
            }
            assertTrue(scriptError.message.orEmpty().contains("native SegWit P2WPKH"))

            val keyError = assertThrows(IllegalArgumentException::class.java) {
                TapsignerPsbtSigning.prepare(mismatchedKey.base64, accountPath)
            }
            assertTrue(keyError.message.orEmpty().contains("does not match its P2WPKH output"))
        } finally {
            versionTwo.clear()
            weakSighash.clear()
            p2wsh.clear()
            mismatchedKey.clear()
        }
    }

    @Test
    fun `inject rejects missing wrong-key invalid and high-S signatures without mutating plan`() {
        val privateKey = BigInteger.ONE
        val otherPrivateKey = BigInteger.TWO
        val fixture = buildTestPsbt(
            listOf(FixtureInput(privateKey, branch = 0L, index = 9L))
        )
        val plan = TapsignerPsbtSigning.prepare(fixture.base64, accountPath)
        val request = plan.requests.single()
        val validSignature = signCompactLowS(privateKey, request.digest)
        val invalidSignature = signCompactLowS(otherPrivateKey, request.digest)
        val otherPubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(toFixed32(otherPrivateKey))
        val highSignature = validSignature.copyOf()
        val curveOrder = SECNamedCurves.getByName("secp256k1").n
        try {
            val lowS = BigInteger(1, validSignature.copyOfRange(32, 64))
            toFixed32(curveOrder.subtract(lowS)).copyInto(highSignature, destinationOffset = 32)

            val missing = assertThrows(IllegalArgumentException::class.java) {
                TapsignerPsbtSigning.inject(plan, emptyList())
            }
            assertTrue(missing.message.orEmpty().contains("0 signatures for 1 inputs"))

            val wrongKey = assertThrows(IllegalStateException::class.java) {
                TapsignerPsbtSigning.inject(
                    plan,
                    listOf(
                        TapsignerPsbtSigning.Signature(
                            inputIndex = 0,
                            pubkey = otherPubkey.copyOf(),
                            compactSignature = invalidSignature.copyOf()
                        )
                    )
                )
            }
            assertTrue(wrongKey.message.orEmpty().contains("not in this wallet policy"))

            val invalid = assertThrows(IllegalStateException::class.java) {
                TapsignerPsbtSigning.inject(
                    plan,
                    listOf(
                        TapsignerPsbtSigning.Signature(
                            inputIndex = 0,
                            pubkey = fixture.pubkeys.single().copyOf(),
                            compactSignature = invalidSignature.copyOf()
                        )
                    )
                )
            }
            assertTrue(invalid.message.orEmpty().contains("signature did not verify"))

            val highS = assertThrows(IllegalArgumentException::class.java) {
                TapsignerPsbtSigning.inject(
                    plan,
                    listOf(
                        TapsignerPsbtSigning.Signature(
                            inputIndex = 0,
                            pubkey = fixture.pubkeys.single().copyOf(),
                            compactSignature = highSignature.copyOf()
                        )
                    )
                )
            }
            assertTrue(highS.message.orEmpty().contains("high-S signature"))

            val signedBytes = Base64.getDecoder().decode(
                TapsignerPsbtSigning.inject(
                    plan,
                    listOf(
                        TapsignerPsbtSigning.Signature(
                            inputIndex = 0,
                            pubkey = fixture.pubkeys.single().copyOf(),
                            compactSignature = validSignature.copyOf()
                        )
                    )
                )
            )
            signedBytes.fill(0)
        } finally {
            plan.clear()
            plan.parsed.clear()
            fixture.clear()
            validSignature.fill(0)
            invalidSignature.fill(0)
            otherPubkey.fill(0)
            highSignature.fill(0)
        }
    }

    @Test
    fun `BIP48 2 of 3 preserves an existing cosigner signature and selects only the active card key`() {
        val firstCosigner = BigInteger.ONE
        val tapsigner = BigInteger.TWO
        val recoveryCosigner = BigInteger.valueOf(3L)
        val input = MultisigFixtureInput(
            scriptPrivateKeys = listOf(firstCosigner, tapsigner, recoveryCosigner),
            derivations = listOf(
                MultisigDerivation(firstCosigner, foreignMultisigAccountPath + listOf(0L, 7L)),
                MultisigDerivation(tapsigner, multisigAccountPath + listOf(0L, 7L)),
                MultisigDerivation(recoveryCosigner, recoveryAccountPath + listOf(0L, 7L))
            ),
            existingPartials = listOf(MultisigPartial(firstCosigner))
        )
        val fixture = buildMultisigPsbt(listOf(input))
        val cardPubkey = publicKey(tapsigner)
        val existingPubkey = publicKey(firstCosigner)
        var plan: TapsignerPsbtSigning.Plan? = null
        var cardSignature: ByteArray? = null
        try {
            val prepared = TapsignerPsbtSigning.prepare(fixture.base64, multisigAccountPath)
            plan = prepared
            val request = prepared.requests.single()
            assertEquals(listOf(0L, 7L), request.subpath)
            assertEquals(1, request.candidatePubkeys.size)
            assertArrayEquals(cardPubkey, request.candidatePubkeys.single())

            cardSignature = signCompactLowS(tapsigner, request.digest)
            val signed = Base64.getDecoder().decode(
                TapsignerPsbtSigning.inject(
                    prepared,
                    listOf(
                        TapsignerPsbtSigning.Signature(
                            inputIndex = 0,
                            pubkey = cardPubkey.copyOf(),
                            compactSignature = requireNotNull(cardSignature).copyOf()
                        )
                    )
                )
            )
            try {
                assertTrue(signed.containsSubsequence(byteArrayOf(0x02) + cardPubkey))
                assertTrue(signed.containsSubsequence(byteArrayOf(0x02) + existingPubkey))
                fixture.existingPartialValues.forEach { value ->
                    assertTrue("Existing cosigner signature must be preserved byte-for-byte", signed.containsSubsequence(value))
                }
            } finally {
                signed.fill(0)
            }
        } finally {
            plan?.clear()
            plan?.parsed?.clear()
            cardSignature?.fill(0)
            cardPubkey.fill(0)
            existingPubkey.fill(0)
            fixture.clear()
        }
    }

    @Test
    fun `BIP48 accepts standard CHECKMULTISIG keys in unsorted multi order`() {
        val tapsigner = BigInteger.TWO
        // Pubkeys for private keys 1, 2, 3 are lexicographically ordered in
        // that sequence, so 2, 1, 3 deliberately exercises descriptor `multi`.
        val input = MultisigFixtureInput(
            scriptPrivateKeys = listOf(tapsigner, BigInteger.ONE, BigInteger.valueOf(3L)),
            derivations = listOf(
                MultisigDerivation(tapsigner, multisigAccountPath + listOf(1L, 11L)),
                MultisigDerivation(BigInteger.ONE, foreignMultisigAccountPath + listOf(1L, 11L)),
                MultisigDerivation(BigInteger.valueOf(3L), recoveryAccountPath + listOf(1L, 11L))
            )
        )
        val fixture = buildMultisigPsbt(listOf(input))
        val cardPubkey = publicKey(tapsigner)
        var plan: TapsignerPsbtSigning.Plan? = null
        var compact: ByteArray? = null
        try {
            val prepared = TapsignerPsbtSigning.prepare(fixture.base64, multisigAccountPath)
            plan = prepared
            val request = prepared.requests.single()
            assertEquals(listOf(1L, 11L), request.subpath)
            assertEquals(1, request.candidatePubkeys.size)
            assertArrayEquals(cardPubkey, request.candidatePubkeys.single())
            compact = signCompactLowS(tapsigner, request.digest)
            val signed = Base64.getDecoder().decode(
                TapsignerPsbtSigning.inject(
                    prepared,
                    listOf(
                        TapsignerPsbtSigning.Signature(
                            0,
                            cardPubkey.copyOf(),
                            requireNotNull(compact).copyOf()
                        )
                    )
                )
            )
            try {
                assertTrue(signed.containsSubsequence(byteArrayOf(0x02) + cardPubkey))
            } finally {
                signed.fill(0)
            }
        } finally {
            plan?.clear()
            plan?.parsed?.clear()
            compact?.fill(0)
            cardPubkey.fill(0)
            fixture.clear()
        }
    }

    @Test
    fun `BIP48 rejects a witness script hash mismatch`() {
        val fixture = buildMultisigPsbt(
            listOf(
                standardMultisigInput(
                    tapsigner = BigInteger.TWO,
                    branch = 0L,
                    index = 2L,
                    witnessProgramOverride = byteArrayOf(0x00, 0x20) + ByteArray(32) { 0x55 }
                )
            )
        )
        try {
            val error = assertThrows(RuntimeException::class.java) {
                TapsignerPsbtSigning.prepare(fixture.base64, multisigAccountPath)
            }
            assertTrue(error.message.orEmpty().contains("witness script does not match"))
        } finally {
            fixture.clear()
        }
    }

    @Test
    fun `BIP48 requires one unambiguous eligible card derivation in the witness script`() {
        val keyOne = BigInteger.ONE
        val keyTwo = BigInteger.TWO
        val keyThree = BigInteger.valueOf(3L)
        val missing = buildMultisigPsbt(
            listOf(
                MultisigFixtureInput(
                    scriptPrivateKeys = listOf(keyOne, keyTwo, keyThree),
                    derivations = listOf(
                        MultisigDerivation(keyOne, foreignMultisigAccountPath + listOf(0L, 4L)),
                        MultisigDerivation(keyTwo, recoveryAccountPath + listOf(0L, 4L)),
                        MultisigDerivation(keyThree, secondForeignAccountPath + listOf(0L, 4L))
                    )
                )
            )
        )
        val foreignEligible = buildMultisigPsbt(
            listOf(
                MultisigFixtureInput(
                    scriptPrivateKeys = listOf(keyOne, keyTwo, keyThree),
                    derivations = listOf(
                        MultisigDerivation(keyOne, foreignMultisigAccountPath + listOf(0L, 5L)),
                        MultisigDerivation(keyTwo, recoveryAccountPath + listOf(0L, 5L)),
                        MultisigDerivation(keyThree, secondForeignAccountPath + listOf(0L, 5L)),
                        MultisigDerivation(BigInteger.valueOf(9L), multisigAccountPath + listOf(0L, 5L))
                    )
                )
            )
        )
        val conflicting = buildMultisigPsbt(
            listOf(
                MultisigFixtureInput(
                    scriptPrivateKeys = listOf(keyOne, keyTwo, keyThree),
                    derivations = listOf(
                        MultisigDerivation(keyOne, multisigAccountPath + listOf(0L, 6L)),
                        MultisigDerivation(keyTwo, multisigAccountPath + listOf(1L, 6L)),
                        MultisigDerivation(keyThree, recoveryAccountPath + listOf(0L, 6L))
                    )
                )
            )
        )
        try {
            val missingError = assertThrows(RuntimeException::class.java) {
                TapsignerPsbtSigning.prepare(missing.base64, multisigAccountPath)
            }
            assertTrue(missingError.message.orEmpty().contains("active TAPSIGNER account path"))

            val foreignError = assertThrows(RuntimeException::class.java) {
                TapsignerPsbtSigning.prepare(foreignEligible.base64, multisigAccountPath)
            }
            assertTrue(foreignError.message.orEmpty().contains("outside its witness script"))

            val conflictingError = assertThrows(RuntimeException::class.java) {
                TapsignerPsbtSigning.prepare(conflicting.base64, multisigAccountPath)
            }
            assertTrue(conflictingError.message.orEmpty().contains("conflicting"))
        } finally {
            missing.clear()
            foreignEligible.clear()
            conflicting.clear()
        }
    }

    @Test
    fun `BIP48 rejects invalid high-S and foreign existing partial signatures`() {
        val invalid = buildMultisigPsbt(
            listOf(
                standardMultisigInput(
                    tapsigner = BigInteger.TWO,
                    branch = 0L,
                    index = 20L,
                    existingPartials = listOf(
                        MultisigPartial(
                            pubkeyPrivateKey = BigInteger.ONE,
                            signingPrivateKey = BigInteger.valueOf(8L)
                        )
                    )
                )
            )
        )
        val highS = buildMultisigPsbt(
            listOf(
                standardMultisigInput(
                    tapsigner = BigInteger.TWO,
                    branch = 0L,
                    index = 21L,
                    existingPartials = listOf(MultisigPartial(BigInteger.ONE, highS = true))
                )
            )
        )
        val foreign = buildMultisigPsbt(
            listOf(
                standardMultisigInput(
                    tapsigner = BigInteger.TWO,
                    branch = 0L,
                    index = 22L,
                    existingPartials = listOf(MultisigPartial(BigInteger.valueOf(9L)))
                )
            )
        )
        try {
            val invalidError = assertThrows(RuntimeException::class.java) {
                TapsignerPsbtSigning.prepare(invalid.base64, multisigAccountPath)
            }
            assertTrue(invalidError.message.orEmpty().contains("invalid existing partial signature"))

            val highSError = assertThrows(RuntimeException::class.java) {
                TapsignerPsbtSigning.prepare(highS.base64, multisigAccountPath)
            }
            assertTrue(highSError.message.orEmpty().contains("high-S signature"))

            val foreignError = assertThrows(RuntimeException::class.java) {
                TapsignerPsbtSigning.prepare(foreign.base64, multisigAccountPath)
            }
            assertTrue(foreignError.message.orEmpty().contains("outside its wallet policy"))
        } finally {
            invalid.clear()
            highS.clear()
            foreign.clear()
        }
    }

    @Test
    fun `BIP48 rejects a PSBT already signed by the active card key`() {
        val tapsigner = BigInteger.TWO
        val fixture = buildMultisigPsbt(
            listOf(
                standardMultisigInput(
                    tapsigner = tapsigner,
                    branch = 1L,
                    index = 23L,
                    existingPartials = listOf(MultisigPartial(tapsigner))
                )
            )
        )
        try {
            val error = assertThrows(RuntimeException::class.java) {
                TapsignerPsbtSigning.prepare(fixture.base64, multisigAccountPath)
            }
            assertTrue(error.message.orEmpty().contains("already signed"))
        } finally {
            fixture.clear()
        }
    }

    @Test
    fun `multi-input injection failure leaves the parsed PSBT unchanged`() {
        val firstCardKey = BigInteger.TWO
        val secondCardKey = BigInteger.valueOf(5L)
        val fixture = buildMultisigPsbt(
            listOf(
                standardMultisigInput(firstCardKey, branch = 0L, index = 30L),
                MultisigFixtureInput(
                    scriptPrivateKeys = listOf(BigInteger.valueOf(4L), secondCardKey, BigInteger.valueOf(6L)),
                    derivations = listOf(
                        MultisigDerivation(BigInteger.valueOf(4L), foreignMultisigAccountPath + listOf(1L, 31L)),
                        MultisigDerivation(secondCardKey, multisigAccountPath + listOf(1L, 31L)),
                        MultisigDerivation(BigInteger.valueOf(6L), recoveryAccountPath + listOf(1L, 31L))
                    )
                )
            )
        )
        var plan: TapsignerPsbtSigning.Plan? = null
        var before: ByteArray? = null
        var firstSignature: ByteArray? = null
        var invalidSecondSignature: ByteArray? = null
        val firstPubkey = publicKey(firstCardKey)
        val secondPubkey = publicKey(secondCardKey)
        try {
            val prepared = TapsignerPsbtSigning.prepare(fixture.base64, multisigAccountPath)
            plan = prepared
            val beforeSnapshot = prepared.parsed.serialize()
            before = beforeSnapshot
            firstSignature = signCompactLowS(firstCardKey, prepared.requests[0].digest)
            invalidSecondSignature = signCompactLowS(BigInteger.valueOf(6L), prepared.requests[1].digest)

            val error = assertThrows(RuntimeException::class.java) {
                TapsignerPsbtSigning.inject(
                    prepared,
                    listOf(
                        TapsignerPsbtSigning.Signature(
                            0,
                            firstPubkey.copyOf(),
                            requireNotNull(firstSignature).copyOf()
                        ),
                        TapsignerPsbtSigning.Signature(
                            1,
                            secondPubkey.copyOf(),
                            requireNotNull(invalidSecondSignature).copyOf()
                        )
                    )
                )
            }
            assertTrue(error.message.orEmpty().contains("signature did not verify"))
            val after = prepared.parsed.serialize()
            try {
                assertArrayEquals("Injection must be all-or-nothing", beforeSnapshot, after)
            } finally {
                after.fill(0)
            }
        } finally {
            plan?.clear()
            plan?.parsed?.clear()
            before?.fill(0)
            firstSignature?.fill(0)
            invalidSecondSignature?.fill(0)
            firstPubkey.fill(0)
            secondPubkey.fill(0)
            fixture.clear()
        }
    }

    private val accountPath = listOf(0x8000_0054L, 0x8000_0000L, 0x8000_0000L)
    private val multisigAccountPath =
        listOf(0x8000_0030L, 0x8000_0000L, 0x8000_0000L, 0x8000_0002L)
    private val foreignMultisigAccountPath =
        listOf(0x8000_0030L, 0x8000_0000L, 0x8000_0001L, 0x8000_0002L)
    private val secondForeignAccountPath =
        listOf(0x8000_0030L, 0x8000_0000L, 0x8000_0002L, 0x8000_0002L)
    private val recoveryAccountPath = listOf(0x8000_002dL)

    private data class MultisigDerivation(
        val privateKey: BigInteger,
        val path: List<Long>
    )

    private data class MultisigPartial(
        val pubkeyPrivateKey: BigInteger,
        val signingPrivateKey: BigInteger = pubkeyPrivateKey,
        val highS: Boolean = false
    )

    private data class MultisigFixtureInput(
        val threshold: Int = 2,
        val scriptPrivateKeys: List<BigInteger>,
        val derivations: List<MultisigDerivation>,
        val existingPartials: List<MultisigPartial> = emptyList(),
        val witnessProgramOverride: ByteArray? = null
    )

    private data class BuiltMultisigPsbt(
        val base64: String,
        val unsignedTransaction: ByteArray,
        val existingPartialValues: List<ByteArray>
    ) {
        fun clear() {
            unsignedTransaction.fill(0)
            existingPartialValues.forEach { it.fill(0) }
        }
    }

    private fun standardMultisigInput(
        tapsigner: BigInteger,
        branch: Long,
        index: Long,
        existingPartials: List<MultisigPartial> = emptyList(),
        witnessProgramOverride: ByteArray? = null
    ): MultisigFixtureInput = MultisigFixtureInput(
        scriptPrivateKeys = listOf(BigInteger.ONE, tapsigner, BigInteger.valueOf(3L)),
        derivations = listOf(
            MultisigDerivation(BigInteger.ONE, foreignMultisigAccountPath + listOf(branch, index)),
            MultisigDerivation(tapsigner, multisigAccountPath + listOf(branch, index)),
            MultisigDerivation(BigInteger.valueOf(3L), recoveryAccountPath + listOf(branch, index))
        ),
        existingPartials = existingPartials,
        witnessProgramOverride = witnessProgramOverride
    )

    private fun buildMultisigPsbt(inputs: List<MultisigFixtureInput>): BuiltMultisigPsbt {
        require(inputs.isNotEmpty())
        val amounts = inputs.indices.map { 50_000L + (it * 1_000L) }
        val scriptPubkeys = inputs.map { input -> input.scriptPrivateKeys.map(::publicKey) }
        val witnessScripts = inputs.mapIndexed { index, input ->
            require(input.threshold in 1..scriptPubkeys[index].size)
            require(scriptPubkeys[index].size in 2..16)
            ByteArrayOutputStream().apply {
                write(0x50 + input.threshold)
                scriptPubkeys[index].forEach { pubkey ->
                    write(0x21)
                    write(pubkey)
                }
                write(0x50 + scriptPubkeys[index].size)
                write(0xae)
            }.toByteArray()
        }

        val transaction = ByteArrayOutputStream()
        transaction.write(littleEndian(2L, 4))
        writeCompactSize(transaction, inputs.size)
        inputs.indices.forEach { inputIndex ->
            transaction.write(ByteArray(32) { (inputIndex + 41).toByte() })
            transaction.write(littleEndian(inputIndex.toLong(), 4))
            transaction.write(0)
            transaction.write(littleEndian(0xffff_fffdL, 4))
        }
        transaction.write(1)
        transaction.write(littleEndian(amounts.sum() - 500L, 8))
        val outputScript = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x24 }
        writeCompactSize(transaction, outputScript.size)
        transaction.write(outputScript)
        transaction.write(ByteArray(4))
        val unsignedTransaction = transaction.toByteArray()

        val rawPsbt = ByteArrayOutputStream()
        val existingPartialValues = mutableListOf<ByteArray>()
        try {
            rawPsbt.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
            writeEntry(rawPsbt, byteArrayOf(0x00), unsignedTransaction)
            rawPsbt.write(0)

            inputs.forEachIndexed { inputIndex, input ->
                val witnessScript = witnessScripts[inputIndex]
                val witnessProgram = input.witnessProgramOverride?.copyOf()
                    ?: (byteArrayOf(0x00, 0x20) + MessageDigest.getInstance("SHA-256").digest(witnessScript))
                val witnessUtxo = ByteArrayOutputStream().apply {
                    write(littleEndian(amounts[inputIndex], 8))
                    writeCompactSize(this, witnessProgram.size)
                    write(witnessProgram)
                }.toByteArray()
                writeEntry(rawPsbt, byteArrayOf(0x01), witnessUtxo)
                writeEntry(rawPsbt, byteArrayOf(0x05), witnessScript)

                input.derivations.forEach { derivation ->
                    val pubkey = publicKey(derivation.privateKey)
                    val fingerprint = byteArrayOf(
                        0x52,
                        0x34,
                        0x10,
                        derivation.privateKey.toInt().toByte()
                    )
                    val value = ByteArrayOutputStream().apply {
                        write(fingerprint)
                        derivation.path.forEach { component -> write(littleEndian(component, 4)) }
                    }.toByteArray()
                    try {
                        writeEntry(rawPsbt, byteArrayOf(0x06) + pubkey, value)
                    } finally {
                        pubkey.fill(0)
                        fingerprint.fill(0)
                        value.fill(0)
                    }
                }

                if (input.existingPartials.isNotEmpty()) {
                    val digest = TapsignerPsbtSigning.bip143SighashAll(
                        unsignedTransaction = unsignedTransaction,
                        inputIndex = inputIndex,
                        scriptCode = witnessScript,
                        amountLittleEndian = littleEndian(amounts[inputIndex], 8)
                    )
                    try {
                        input.existingPartials.forEach { partial ->
                            val pubkey = publicKey(partial.pubkeyPrivateKey)
                            val compact = signCompactLowS(partial.signingPrivateKey, digest)
                            if (partial.highS) {
                                val curveOrder = SECNamedCurves.getByName("secp256k1").n
                                val lowS = BigInteger(1, compact.copyOfRange(32, 64))
                                toFixed32(curveOrder.subtract(lowS)).copyInto(compact, destinationOffset = 32)
                            }
                            val der = compactSignatureToDerForTest(compact)
                            val value = der + byteArrayOf(0x01)
                            try {
                                writeEntry(rawPsbt, byteArrayOf(0x02) + pubkey, value)
                                existingPartialValues += value.copyOf()
                            } finally {
                                pubkey.fill(0)
                                compact.fill(0)
                                der.fill(0)
                                value.fill(0)
                            }
                        }
                    } finally {
                        digest.fill(0)
                    }
                }

                rawPsbt.write(0)
                witnessProgram.fill(0)
                witnessUtxo.fill(0)
            }
            rawPsbt.write(0)

            val raw = rawPsbt.toByteArray()
            return try {
                BuiltMultisigPsbt(
                    base64 = Base64.getEncoder().encodeToString(raw),
                    unsignedTransaction = unsignedTransaction,
                    existingPartialValues = existingPartialValues
                )
            } finally {
                raw.fill(0)
            }
        } catch (t: Throwable) {
            unsignedTransaction.fill(0)
            existingPartialValues.forEach { it.fill(0) }
            throw t
        } finally {
            witnessScripts.forEach { it.fill(0) }
            scriptPubkeys.flatten().forEach { it.fill(0) }
            outputScript.fill(0)
        }
    }

    private fun compactSignatureToDerForTest(signature: ByteArray): ByteArray {
        require(signature.size == 64)
        val vector = ASN1EncodableVector()
        vector.add(ASN1Integer(BigInteger(1, signature.copyOfRange(0, 32))))
        vector.add(ASN1Integer(BigInteger(1, signature.copyOfRange(32, 64))))
        return DERSequence(vector).encoded
    }

    private fun publicKey(privateKey: BigInteger): ByteArray {
        val encoded = toFixed32(privateKey)
        return try {
            CoinkiteTapCardVerifier.publicKeyFromPrivateKey(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    private data class FixtureInput(
        val signingPrivateKey: BigInteger,
        val branch: Long,
        val index: Long,
        val derivationPrivateKey: BigInteger = signingPrivateKey,
        val witnessProgramOverride: ByteArray? = null,
        val sighashType: Long? = null
    )

    private data class BuiltTestPsbt(
        val base64: String,
        val unsignedTransaction: ByteArray,
        val pubkeys: List<ByteArray>
    ) {
        fun clear() {
            unsignedTransaction.fill(0)
            pubkeys.forEach { it.fill(0) }
        }
    }

    private fun buildTestPsbt(
        inputs: List<FixtureInput>,
        psbtVersion: Long? = null
    ): BuiltTestPsbt {
        require(inputs.isNotEmpty())
        val amounts = inputs.indices.map { 10_000L + (it * 1_000L) }
        val witnessPubkeys = inputs.map { input ->
            CoinkiteTapCardVerifier.publicKeyFromPrivateKey(toFixed32(input.signingPrivateKey))
        }
        val derivationPubkeys = inputs.map { input ->
            CoinkiteTapCardVerifier.publicKeyFromPrivateKey(toFixed32(input.derivationPrivateKey))
        }

        val transaction = ByteArrayOutputStream()
        transaction.write(littleEndian(2L, 4))
        writeCompactSize(transaction, inputs.size)
        inputs.indices.forEach { inputIndex ->
            transaction.write(ByteArray(32) { (inputIndex + 1).toByte() })
            transaction.write(littleEndian(inputIndex.toLong(), 4))
            transaction.write(0)
            transaction.write(littleEndian(0xffff_ffffL, 4))
        }
        transaction.write(1)
        transaction.write(littleEndian(amounts.sum() - 110L, 8))
        val outputScript = byteArrayOf(0x00, 0x14) + ByteArray(20) { 0x42 }
        writeCompactSize(transaction, outputScript.size)
        transaction.write(outputScript)
        transaction.write(ByteArray(4))
        val unsignedTransaction = transaction.toByteArray()

        val rawPsbt = ByteArrayOutputStream()
        rawPsbt.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
        writeEntry(rawPsbt, byteArrayOf(0x00), unsignedTransaction)
        psbtVersion?.let { writeEntry(rawPsbt, byteArrayOf(0xfb.toByte()), littleEndian(it, 4)) }
        rawPsbt.write(0)

        inputs.forEachIndexed { inputIndex, input ->
            val witnessProgram = input.witnessProgramOverride?.copyOf()
                ?: (byteArrayOf(0x00, 0x14) + hash160(witnessPubkeys[inputIndex]))
            val witnessUtxo = ByteArrayOutputStream().apply {
                write(littleEndian(amounts[inputIndex], 8))
                writeCompactSize(this, witnessProgram.size)
                write(witnessProgram)
            }.toByteArray()
            writeEntry(rawPsbt, byteArrayOf(0x01), witnessUtxo)

            val derivation = ByteArrayOutputStream().apply {
                write(byteArrayOf(0xd3.toByte(), 0x4d, 0xb3.toByte(), 0x3f))
                (accountPath + listOf(input.branch, input.index)).forEach { component ->
                    write(littleEndian(component, 4))
                }
            }.toByteArray()
            writeEntry(rawPsbt, byteArrayOf(0x06) + derivationPubkeys[inputIndex], derivation)
            input.sighashType?.let {
                writeEntry(rawPsbt, byteArrayOf(0x03), littleEndian(it, 4))
            }
            rawPsbt.write(0)

            witnessProgram.fill(0)
            witnessUtxo.fill(0)
            derivation.fill(0)
        }
        rawPsbt.write(0)

        val raw = rawPsbt.toByteArray()
        return try {
            BuiltTestPsbt(
                base64 = Base64.getEncoder().encodeToString(raw),
                unsignedTransaction = unsignedTransaction,
                pubkeys = derivationPubkeys
            )
        } finally {
            raw.fill(0)
            witnessPubkeys.forEach { it.fill(0) }
            outputScript.fill(0)
        }
    }

    private fun writeEntry(out: ByteArrayOutputStream, key: ByteArray, value: ByteArray) {
        writeCompactSize(out, key.size)
        out.write(key)
        writeCompactSize(out, value.size)
        out.write(value)
    }

    private fun writeCompactSize(out: ByteArrayOutputStream, value: Int) {
        require(value in 0..252) { "Test fixture only supports one-byte compact sizes" }
        out.write(value)
    }

    private fun littleEndian(value: Long, size: Int): ByteArray =
        ByteArray(size) { offset -> (value ushr (offset * 8)).toByte() }

    private fun hash160(bytes: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
        val digest = RIPEMD160Digest()
        digest.update(sha, 0, sha.size)
        sha.fill(0)
        return ByteArray(20).also { digest.doFinal(it, 0) }
    }

    private fun signCompactLowS(privateKey: BigInteger, digest: ByteArray): ByteArray {
        val curve = SECNamedCurves.getByName("secp256k1")
        val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privateKey, domain))
        val signature = signer.generateSignature(digest)
        val lowS = if (signature[1] > curve.n.shiftRight(1)) curve.n.subtract(signature[1]) else signature[1]
        return toFixed32(signature[0]) + toFixed32(lowS)
    }

    private fun toFixed32(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        val unsigned = if (encoded.size > 32) encoded.copyOfRange(encoded.size - 32, encoded.size) else encoded
        return ByteArray(32).also { unsigned.copyInto(it, destinationOffset = 32 - unsigned.size) }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }
}
