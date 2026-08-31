package net.clench.wallet.ui.components

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
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
            assertTrue(alreadySigned.message.orEmpty().contains("already contains signature"))
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

            val scriptError = assertThrows(IllegalStateException::class.java) {
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

    private val accountPath = listOf(0x8000_0054L, 0x8000_0000L, 0x8000_0000L)

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
