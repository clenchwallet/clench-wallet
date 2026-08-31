package net.clench.wallet.ui.components

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.RIPEMD160Digest

/**
 * TAPSIGNER bridge for PSBT v0 native-SegWit inputs.
 *
 * The bridge deliberately supports only ECDSA SIGHASH_ALL for native P2WPKH
 * at BIP84 account zero and standard native P2WSH multisig at BIP48 account zero.
 * It parses the canonical PSBT without changing any existing fields,
 * prepares the exact BIP-143 digest for each input controlled by the current
 * TAPSIGNER account path, and inserts only verified partial signatures.
 */
internal object TapsignerPsbtSigning {
    private val psbtMagic = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
    private const val maxPsbtBytes = 4 * 1024 * 1024
    private const val hardenedFlag = 0x8000_0000L
    private const val sighashAll = 1L
    private val secp256k1Order = SECNamedCurves.getByName("secp256k1").n
    private enum class SigningPolicy {
        BIP84_P2WPKH,
        BIP48_P2WSH_MULTISIG
    }

    data class Request(
        val inputIndex: Int,
        val digest: ByteArray,
        val subpath: List<Long>,
        val candidatePubkeys: List<ByteArray>
    ) {
        fun clear() {
            digest.fill(0)
            candidatePubkeys.forEach { it.fill(0) }
        }
    }

    data class Signature(
        val inputIndex: Int,
        val pubkey: ByteArray,
        val compactSignature: ByteArray
    ) {
        fun clear() {
            pubkey.fill(0)
            compactSignature.fill(0)
        }
    }

    class Plan internal constructor(
        internal val parsed: ParsedPsbt,
        val requests: List<Request>
    ) {
        fun clear() = requests.forEach { it.clear() }
    }

    fun prepare(psbtBase64: String, accountPath: List<Long>): Plan {
        require(accountPath.isNotEmpty()) { "TAPSIGNER did not report an account path" }
        require(accountPath.size <= 8 && accountPath.all { it in hardenedFlag..0xffff_ffffL }) {
            "TAPSIGNER account path must contain only hardened components"
        }
        val signingPolicy = signingPolicy(accountPath)
        val decoded = try {
            Base64.getDecoder().decode(psbtBase64.trim())
        } catch (_: IllegalArgumentException) {
            error("TAPSIGNER signing requires a base64 PSBT")
        }
        require(decoded.size in psbtMagic.size..maxPsbtBytes) { "PSBT size is outside the supported range" }
        val parsed = try {
            parsePsbt(decoded)
        } finally {
            decoded.fill(0)
        }
        val hashPrevouts = doubleSha256(concat(parsed.transaction.inputs.map { it.outpoint }))
        val hashSequences = doubleSha256(concat(parsed.transaction.inputs.map { it.sequence }))
        val hashOutputs = doubleSha256(concat(parsed.transaction.outputs))
        val requests = try {
            parsed.inputs.mapIndexed { inputIndex, inputMap ->
                prepareInput(
                    parsed = parsed,
                    inputMap = inputMap,
                    inputIndex = inputIndex,
                    accountPath = accountPath,
                    signingPolicy = signingPolicy,
                    hashPrevouts = hashPrevouts,
                    hashSequences = hashSequences,
                    hashOutputs = hashOutputs
                )
            }
        } catch (t: Throwable) {
            parsed.clear()
            throw t
        } finally {
            hashPrevouts.fill(0)
            hashSequences.fill(0)
            hashOutputs.fill(0)
        }
        require(requests.isNotEmpty()) { "PSBT has no inputs for TAPSIGNER" }
        return Plan(parsed, requests)
    }

    fun inject(plan: Plan, signatures: List<Signature>): String {
        require(signatures.size == plan.requests.size) {
            "TAPSIGNER returned ${signatures.size} signatures for ${plan.requests.size} inputs"
        }
        val byInput = signatures.associateBy { it.inputIndex }
        require(byInput.size == signatures.size) { "TAPSIGNER returned duplicate input signatures" }

        // Validate and encode every returned signature before mutating the parsed PSBT.
        // A bad later input must not leave earlier inputs partially modified and poison a retry.
        val staged = mutableListOf<StagedPartialSignature>()
        try {
            plan.requests.forEach { request ->
                val signature = byInput[request.inputIndex]
                    ?: error("TAPSIGNER did not sign input ${request.inputIndex + 1}")
                require(signature.pubkey.size == 33 &&
                    (signature.pubkey[0] == 0x02.toByte() || signature.pubkey[0] == 0x03.toByte())) {
                    "TAPSIGNER returned an invalid compressed public key"
                }
                if (request.candidatePubkeys.none { MessageDigest.isEqual(it, signature.pubkey) }) {
                    error("TAPSIGNER input ${request.inputIndex + 1} public key is not in this wallet policy")
                }
                if (!CoinkiteTapCardVerifier.verifyEcdsa(
                        signature.pubkey,
                        request.digest,
                        signature.compactSignature
                    )) {
                    error("TAPSIGNER input ${request.inputIndex + 1} signature did not verify")
                }
                requireLowS(signature.compactSignature)

                val inputMap = plan.parsed.inputs[request.inputIndex]
                val partialSigKey = byteArrayOf(0x02) + signature.pubkey
                if (inputMap.entries.any { MessageDigest.isEqual(it.key, partialSigKey) }) {
                    partialSigKey.fill(0)
                    error("TAPSIGNER already signed input ${request.inputIndex + 1}")
                }
                val der = try {
                    compactSignatureToDer(signature.compactSignature)
                } catch (t: Throwable) {
                    partialSigKey.fill(0)
                    throw t
                }
                try {
                    staged += StagedPartialSignature(
                        inputMap = inputMap,
                        key = partialSigKey,
                        value = der + byteArrayOf(sighashAll.toByte())
                    )
                } finally {
                    der.fill(0)
                }
            }
        } catch (t: Throwable) {
            staged.forEach { it.clear() }
            throw t
        }

        staged.forEach { pending ->
            pending.inputMap.entries += PsbtEntry(pending.key, pending.value)
        }

        val serialized = plan.parsed.serialize()
        return try {
            Base64.getEncoder().encodeToString(serialized)
        } finally {
            serialized.fill(0)
        }
    }

    internal fun bip143SighashAll(
        unsignedTransaction: ByteArray,
        inputIndex: Int,
        scriptCode: ByteArray,
        amountLittleEndian: ByteArray
    ): ByteArray {
        val transaction = parseUnsignedTransaction(unsignedTransaction)
        require(inputIndex in transaction.inputs.indices) { "Input index is outside the transaction" }
        require(amountLittleEndian.size == 8) { "Input amount must be an unsigned 64-bit value" }
        return computeBip143SighashAll(
            transaction,
            inputIndex,
            scriptCode,
            amountLittleEndian,
            doubleSha256(concat(transaction.inputs.map { it.outpoint })),
            doubleSha256(concat(transaction.inputs.map { it.sequence })),
            doubleSha256(concat(transaction.outputs))
        )
    }

    private fun prepareInput(
        parsed: ParsedPsbt,
        inputMap: PsbtMap,
        inputIndex: Int,
        accountPath: List<Long>,
        signingPolicy: SigningPolicy,
        hashPrevouts: ByteArray,
        hashSequences: ByteArray,
        hashOutputs: ByteArray
    ): Request {
        require(inputMap.entries.none { it.hasType(0x07) || it.hasType(0x08) }) {
            "TAPSIGNER input ${inputIndex + 1} already contains finalization data"
        }
        require(inputMap.entries.none { entry ->
            entry.key.isNotEmpty() && (entry.key[0].toInt() and 0xff) in 0x13..0x18
        }) {
            "TAPSIGNER input ${inputIndex + 1} contains incompatible Taproot fields"
        }
        require(inputMap.entries.none { entry ->
            entry.key.isNotEmpty() && (entry.key[0].toInt() and 0xff) in 0x0e..0x12
        }) {
            "TAPSIGNER signing requires PSBT v0 input fields"
        }
        require(inputMap.entries.none { it.hasType(0x04) }) {
            "TAPSIGNER supports native SegWit only; redeem scripts are not allowed"
        }
        inputMap.strictSingleValue(0x03, "sighash type")?.let { value ->
            require(value.size == 4 && readUInt32Le(value, 0) == sighashAll) {
                "TAPSIGNER supports only ECDSA SIGHASH_ALL"
            }
        }
        val witnessUtxo = inputMap.strictSingleValue(0x01, "witness UTXO")
            ?: error("TAPSIGNER input ${inputIndex + 1} is missing witness UTXO data")
        val (amount, witnessProgram) = parseTxOut(witnessUtxo)
        val derivations = inputMap.entries.mapNotNull { entry ->
            if (!entry.hasType(0x06)) return@mapNotNull null
            require(entry.key.size == 34) {
                "PSBT input ${inputIndex + 1} contains a malformed BIP32 derivation key"
            }
            val pubkey = entry.key.copyOfRange(1, 34)
            if (pubkey[0] != 0x02.toByte() && pubkey[0] != 0x03.toByte()) {
                pubkey.fill(0)
                error("PSBT input ${inputIndex + 1} contains an uncompressed derivation key")
            }
            val path = parseKeySourcePath(entry.value)
            Derivation(pubkey, path)
        }
        if (derivations.isEmpty()) {
            error("PSBT input ${inputIndex + 1} has no key below the active TAPSIGNER account path")
        }
        val prepared = try {
            when (signingPolicy) {
                SigningPolicy.BIP84_P2WPKH -> prepareP2wpkhInput(
                    inputMap = inputMap,
                    inputIndex = inputIndex,
                    witnessProgram = witnessProgram,
                    accountPath = accountPath,
                    derivations = derivations
                )
                SigningPolicy.BIP48_P2WSH_MULTISIG -> prepareP2wshMultisigInput(
                    inputMap = inputMap,
                    inputIndex = inputIndex,
                    witnessProgram = witnessProgram,
                    accountPath = accountPath,
                    derivations = derivations
                )
            }
        } catch (t: Throwable) {
            derivations.forEach { it.pubkey.fill(0) }
            amount.fill(0)
            witnessProgram.fill(0)
            throw t
        }

        val digest = try {
            computeBip143SighashAll(
                parsed.transaction,
                inputIndex,
                prepared.scriptCode,
                amount,
                hashPrevouts,
                hashSequences,
                hashOutputs
            )
        } finally {
            prepared.scriptCode.fill(0)
            amount.fill(0)
            witnessProgram.fill(0)
        }
        try {
            validateExistingPartialSignatures(
                inputMap = inputMap,
                inputIndex = inputIndex,
                allowedPubkeys = prepared.allowedPartialSignaturePubkeys,
                digest = digest,
                allowExisting = signingPolicy == SigningPolicy.BIP48_P2WSH_MULTISIG
            )
            if (prepared.candidatePubkeys.size == 1) {
                val cardCandidate = prepared.candidatePubkeys.single()
                require(inputMap.entries.none { entry ->
                    entry.hasType(0x02) && entry.key.size == 34 &&
                        entry.key.indices.drop(1).all { keyIndex ->
                            entry.key[keyIndex] == cardCandidate[keyIndex - 1]
                        }
                }) {
                    "TAPSIGNER already signed input ${inputIndex + 1}"
                }
            }
        } catch (t: Throwable) {
            digest.fill(0)
            prepared.candidatePubkeys.forEach { it.fill(0) }
            throw t
        } finally {
            prepared.allowedPartialSignaturePubkeys.forEach { allowedPubkey ->
                if (prepared.candidatePubkeys.none { candidate -> candidate === allowedPubkey }) {
                    allowedPubkey.fill(0)
                }
            }
        }
        return Request(
            inputIndex = inputIndex,
            digest = digest,
            subpath = prepared.subpath,
            candidatePubkeys = prepared.candidatePubkeys
        )
    }

    private fun signingPolicy(accountPath: List<Long>): SigningPolicy {
        val unhardened = accountPath.map { it and hardenedFlag.inv() }
        val coinTypeIsSupported = unhardened.getOrNull(1) == 0L || unhardened.getOrNull(1) == 1L
        return when {
            coinTypeIsSupported && unhardened == listOf(84L, unhardened[1], 0L) ->
                SigningPolicy.BIP84_P2WPKH
            coinTypeIsSupported && unhardened == listOf(48L, unhardened[1], 0L, 2L) ->
                SigningPolicy.BIP48_P2WSH_MULTISIG
            else -> error(
                "TAPSIGNER signing supports only BIP84 account-0 or BIP48 " +
                    "native-SegWit multisig account-0 paths on mainnet/testnet"
            )
        }
    }

    private fun prepareP2wpkhInput(
        inputMap: PsbtMap,
        inputIndex: Int,
        witnessProgram: ByteArray,
        accountPath: List<Long>,
        derivations: List<Derivation>
    ): PreparedPolicyInput {
        require(witnessProgram.isP2wpkh()) {
            "TAPSIGNER BIP84 signing supports only native SegWit P2WPKH inputs"
        }
        require(inputMap.strictSingleValue(0x05, "witness script") == null) {
            "P2WPKH input ${inputIndex + 1} must not contain a witness script"
        }
        require(derivations.size == 1) {
            "P2WPKH input ${inputIndex + 1} must contain exactly one BIP32 key origin"
        }
        val derivation = derivations.single()
        val relative = requireBranchIndexPath(derivation.path, accountPath, inputIndex)
        val keyHash = witnessProgram.copyOfRange(2, 22)
        try {
            require(MessageDigest.isEqual(hash160(derivation.pubkey), keyHash)) {
                "PSBT input ${inputIndex + 1} TAPSIGNER key does not match its P2WPKH output"
            }
        } finally {
            keyHash.fill(0)
        }
        return PreparedPolicyInput(
            scriptCode = byteArrayOf(0x76, 0xa9.toByte(), 0x14) +
                witnessProgram.copyOfRange(2, 22) +
                byteArrayOf(0x88.toByte(), 0xac.toByte()),
            candidatePubkeys = listOf(derivation.pubkey),
            allowedPartialSignaturePubkeys = listOf(derivation.pubkey),
            subpath = relative
        )
    }

    private fun prepareP2wshMultisigInput(
        inputMap: PsbtMap,
        inputIndex: Int,
        witnessProgram: ByteArray,
        accountPath: List<Long>,
        derivations: List<Derivation>
    ): PreparedPolicyInput {
        require(witnessProgram.isP2wsh()) {
            "TAPSIGNER BIP48 signing supports only native SegWit P2WSH inputs"
        }
        val witnessScript = inputMap.strictSingleValue(0x05, "witness script")
            ?: error("P2WSH input ${inputIndex + 1} is missing its witness script")
        val witnessScriptHash = sha256(witnessScript)
        try {
            require(MessageDigest.isEqual(witnessScriptHash, witnessProgram.copyOfRange(2, 34))) {
                "P2WSH input ${inputIndex + 1} witness script does not match its witness program"
            }
        } finally {
            witnessScriptHash.fill(0)
        }

        val multisig = parseStandardMultisigWitnessScript(witnessScript, inputIndex)
        try {
            require(derivations.all { derivation ->
                multisig.pubkeys.any { MessageDigest.isEqual(it, derivation.pubkey) }
            }) {
                "P2WSH input ${inputIndex + 1} has a BIP32 key origin outside its witness script"
            }

            // Other cosigners may legitimately use different accounts or origins. Select only
            // derivations below the active card's BIP48 account; the authenticated card response
            // must still return one of these exact witness-script keys.
            val eligibleDerivations = derivations.filter { it.path.startsWith(accountPath) }
            require(eligibleDerivations.isNotEmpty()) {
                "P2WSH input ${inputIndex + 1} has no key below the active TAPSIGNER account path"
            }
            val relativePaths = eligibleDerivations.map { derivation ->
                requireBranchIndexPath(derivation.path, accountPath, inputIndex)
            }.distinct()
            require(relativePaths.size == 1) {
                "P2WSH input ${inputIndex + 1} has conflicting TAPSIGNER branch/index paths"
            }
            return PreparedPolicyInput(
                scriptCode = witnessScript.copyOf(),
                candidatePubkeys = eligibleDerivations.map { it.pubkey },
                allowedPartialSignaturePubkeys = multisig.pubkeys.map { it.copyOf() },
                subpath = relativePaths.single()
            )
        } finally {
            multisig.pubkeys.forEach { it.fill(0) }
        }
    }

    private fun requireBranchIndexPath(
        fullPath: List<Long>,
        accountPath: List<Long>,
        inputIndex: Int
    ): List<Long> {
        require(fullPath.startsWith(accountPath)) {
            "PSBT input ${inputIndex + 1} key origin is outside the active TAPSIGNER account path"
        }
        val relative = fullPath.drop(accountPath.size)
        require(relative.size == 2 && relative[0] in 0L..1L && relative[1] < hardenedFlag) {
            "PSBT input ${inputIndex + 1} requires an unhardened receive/change branch and index"
        }
        return relative
    }

    private fun validateExistingPartialSignatures(
        inputMap: PsbtMap,
        inputIndex: Int,
        allowedPubkeys: List<ByteArray>,
        digest: ByteArray,
        allowExisting: Boolean
    ) {
        val partials = inputMap.entries.filter { it.hasType(0x02) }
        require(allowExisting || partials.isEmpty()) {
            "TAPSIGNER input ${inputIndex + 1} already contains a partial signature"
        }
        partials.forEach { entry ->
            require(entry.key.size == 34 &&
                (entry.key[1] == 0x02.toByte() || entry.key[1] == 0x03.toByte())) {
                "PSBT input ${inputIndex + 1} contains a malformed partial-signature key"
            }
            val pubkey = entry.key.copyOfRange(1, 34)
            require(allowedPubkeys.any { MessageDigest.isEqual(it, pubkey) }) {
                pubkey.fill(0)
                "PSBT input ${inputIndex + 1} contains a partial signature outside its wallet policy"
            }
            val compact = parseDerSighashAll(entry.value, inputIndex)
            try {
                requireLowS(compact)
                require(CoinkiteTapCardVerifier.verifyEcdsa(pubkey, digest, compact)) {
                    "PSBT input ${inputIndex + 1} contains an invalid existing partial signature"
                }
            } finally {
                pubkey.fill(0)
                compact.fill(0)
            }
        }
    }

    private fun parseDerSighashAll(value: ByteArray, inputIndex: Int): ByteArray {
        require(value.size >= 9 && value.last() == sighashAll.toByte()) {
            "PSBT input ${inputIndex + 1} existing signature must use SIGHASH_ALL"
        }
        val encoded = value.copyOfRange(0, value.size - 1)
        try {
            val primitive = try {
                ASN1Primitive.fromByteArray(encoded)
            } catch (_: Exception) {
                null
            }
            require(primitive is ASN1Sequence && primitive.size() == 2 &&
                MessageDigest.isEqual(primitive.encoded, encoded)) {
                "PSBT input ${inputIndex + 1} contains a non-canonical DER signature"
            }
            val r = (primitive.getObjectAt(0) as? ASN1Integer)?.value
            val s = (primitive.getObjectAt(1) as? ASN1Integer)?.value
            require(r != null && s != null && r.signum() > 0 && s.signum() > 0 &&
                r < secp256k1Order && s < secp256k1Order) {
                "PSBT input ${inputIndex + 1} contains an out-of-range ECDSA signature"
            }
            return toFixed32(r) + toFixed32(s)
        } finally {
            encoded.fill(0)
        }
    }

    private fun parseStandardMultisigWitnessScript(script: ByteArray, inputIndex: Int): StandardMultisig {
        require(script.size >= 71) {
            "P2WSH input ${inputIndex + 1} witness script is not standard multisig"
        }
        val threshold = decodeSmallInteger(script[0])
            ?: error("P2WSH input ${inputIndex + 1} has a non-minimal multisig threshold")
        val pubkeys = mutableListOf<ByteArray>()
        var offset = 1
        try {
            while (offset < script.size - 2 && script[offset] == 0x21.toByte()) {
                require(offset + 34 <= script.size - 2) {
                    "P2WSH input ${inputIndex + 1} has a truncated multisig public key"
                }
                val pubkey = script.copyOfRange(offset + 1, offset + 34)
                require(pubkey[0] == 0x02.toByte() || pubkey[0] == 0x03.toByte()) {
                    pubkey.fill(0)
                    "P2WSH input ${inputIndex + 1} contains an uncompressed multisig key"
                }
                pubkeys += pubkey
                offset += 34
            }
            require(offset + 2 == script.size && script.last() == 0xae.toByte()) {
                "P2WSH input ${inputIndex + 1} witness script is not standard CHECKMULTISIG"
            }
            val keyCount = decodeSmallInteger(script[offset])
                ?: error("P2WSH input ${inputIndex + 1} has a non-minimal multisig key count")
            require(keyCount == pubkeys.size && keyCount in 2..16 && threshold in 1..keyCount) {
                "P2WSH input ${inputIndex + 1} has an invalid multisig threshold or key count"
            }
            require(pubkeys.indices.all { leftIndex ->
                (leftIndex + 1 until pubkeys.size).none { rightIndex ->
                    MessageDigest.isEqual(pubkeys[leftIndex], pubkeys[rightIndex])
                }
            }) {
                "P2WSH input ${inputIndex + 1} contains duplicate multisig keys"
            }
            return StandardMultisig(threshold, pubkeys)
        } catch (t: Throwable) {
            pubkeys.forEach { it.fill(0) }
            throw t
        }
    }

    private fun decodeSmallInteger(opcode: Byte): Int? {
        val value = opcode.toInt() and 0xff
        return if (value in 0x51..0x60) value - 0x50 else null
    }

    private fun toFixed32(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        return try {
            require(encoded.size <= 33) { "ECDSA integer exceeds secp256k1 bounds" }
            val sourceOffset = if (encoded.size == 33) 1 else 0
            val count = encoded.size - sourceOffset
            ByteArray(32).also { encoded.copyInto(it, 32 - count, sourceOffset, encoded.size) }
        } finally {
            encoded.fill(0)
        }
    }

    private fun computeBip143SighashAll(
        transaction: ParsedTransaction,
        inputIndex: Int,
        scriptCode: ByteArray,
        amountLittleEndian: ByteArray,
        hashPrevouts: ByteArray,
        hashSequences: ByteArray,
        hashOutputs: ByteArray
    ): ByteArray {
        val preimage = ByteArrayOutputStream()
        preimage.write(transaction.version)
        preimage.write(hashPrevouts)
        preimage.write(hashSequences)
        preimage.write(transaction.inputs[inputIndex].outpoint)
        writeCompactSize(preimage, scriptCode.size.toLong())
        preimage.write(scriptCode)
        preimage.write(amountLittleEndian)
        preimage.write(transaction.inputs[inputIndex].sequence)
        preimage.write(hashOutputs)
        preimage.write(transaction.lockTime)
        preimage.write(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        val bytes = preimage.toByteArray()
        return try {
            doubleSha256(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun parsePsbt(bytes: ByteArray): ParsedPsbt {
        val cursor = Cursor(bytes)
        require(MessageDigest.isEqual(cursor.readBytes(psbtMagic.size), psbtMagic)) {
            "Invalid PSBT magic"
        }
        val global = parseMap(cursor)
        global.singleValue(0xfb)?.let { version ->
            require(version.size == 4 && readUInt32Le(version, 0) == 0L) {
                "TAPSIGNER signing requires PSBT v0"
            }
        }
        val unsignedTransactions = global.entries.filter { it.key.size == 1 && it.key[0] == 0x00.toByte() }
        require(unsignedTransactions.size == 1) { "PSBT v0 must contain one unsigned transaction" }
        val transaction = parseUnsignedTransaction(unsignedTransactions.single().value)
        val inputs = List(transaction.inputs.size) { parseMap(cursor) }
        val outputs = List(transaction.outputs.size) { parseMap(cursor) }
        require(cursor.remaining == 0) { "PSBT contains trailing data" }
        return ParsedPsbt(global, inputs, outputs, transaction)
    }

    private fun parseMap(cursor: Cursor): PsbtMap {
        val entries = mutableListOf<PsbtEntry>()
        val keys = mutableSetOf<String>()
        while (true) {
            val keyLength = cursor.readCompactSize()
            if (keyLength == 0L) break
            require(keyLength in 1..10_000L) { "PSBT key is too large" }
            val key = cursor.readBytes(keyLength.toInt())
            val keyId = key.joinToString("") { "%02x".format(it) }
            require(keys.add(keyId)) { "PSBT map contains a duplicate key" }
            val valueLength = cursor.readCompactSize()
            require(valueLength in 0..maxPsbtBytes.toLong()) { "PSBT value is too large" }
            entries += PsbtEntry(key, cursor.readBytes(valueLength.toInt()))
        }
        return PsbtMap(entries)
    }

    private fun parseUnsignedTransaction(bytes: ByteArray): ParsedTransaction {
        val cursor = Cursor(bytes)
        val version = cursor.readBytes(4)
        val inputCount = cursor.readCompactSize()
        require(inputCount in 1..100_000L) { "Unsigned transaction has an invalid input count" }
        val inputs = List(inputCount.toInt()) {
            val outpoint = cursor.readBytes(36)
            val scriptSigLength = cursor.readCompactSize()
            require(scriptSigLength == 0L) { "PSBT unsigned transaction input contains scriptSig data" }
            ParsedTxIn(outpoint, cursor.readBytes(4))
        }
        val outputCount = cursor.readCompactSize()
        require(outputCount in 1..100_000L) { "Unsigned transaction has an invalid output count" }
        val outputs = List(outputCount.toInt()) {
            val output = ByteArrayOutputStream()
            output.write(cursor.readBytes(8))
            val scriptLength = cursor.readCompactSize()
            require(scriptLength in 0..10_000L) { "Transaction output script is too large" }
            writeCompactSize(output, scriptLength)
            output.write(cursor.readBytes(scriptLength.toInt()))
            output.toByteArray()
        }
        val lockTime = cursor.readBytes(4)
        require(cursor.remaining == 0) { "Unsigned transaction contains trailing or witness data" }
        return ParsedTransaction(version, inputs, outputs, lockTime)
    }

    private fun parseTxOut(value: ByteArray): Pair<ByteArray, ByteArray> {
        val cursor = Cursor(value)
        val amount = cursor.readBytes(8)
        val scriptLength = cursor.readCompactSize()
        require(scriptLength in 2..10_000L) { "Witness UTXO script is invalid" }
        val script = cursor.readBytes(scriptLength.toInt())
        require(cursor.remaining == 0) { "Witness UTXO contains trailing data" }
        return amount to script
    }

    private fun parseKeySourcePath(value: ByteArray): List<Long> {
        require(value.size >= 4 && (value.size - 4) % 4 == 0) {
            "PSBT BIP32 derivation value is malformed"
        }
        return (4 until value.size step 4).map { offset -> readUInt32Le(value, offset) }
    }

    private fun compactSignatureToDer(signature: ByteArray): ByteArray {
        require(signature.size == 64) { "TAPSIGNER compact signature must be 64 bytes" }
        val vector = ASN1EncodableVector()
        vector.add(ASN1Integer(BigInteger(1, signature.copyOfRange(0, 32))))
        vector.add(ASN1Integer(BigInteger(1, signature.copyOfRange(32, 64))))
        return DERSequence(vector).encoded
    }

    private fun requireLowS(signature: ByteArray) {
        require(signature.size == 64) { "TAPSIGNER compact signature must be 64 bytes" }
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        require(s <= secp256k1Order.shiftRight(1)) { "TAPSIGNER returned a high-S signature" }
    }

    private fun ByteArray.isP2wpkh(): Boolean =
        size == 22 && this[0] == 0x00.toByte() && this[1] == 0x14.toByte()

    private fun ByteArray.isP2wsh(): Boolean =
        size == 34 && this[0] == 0x00.toByte() && this[1] == 0x20.toByte()

    private fun List<Long>.startsWith(prefix: List<Long>): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun hash160(bytes: ByteArray): ByteArray {
        val sha = sha256(bytes)
        val digest = RIPEMD160Digest()
        digest.update(sha, 0, sha.size)
        sha.fill(0)
        return ByteArray(20).also { digest.doFinal(it, 0) }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun doubleSha256(bytes: ByteArray): ByteArray {
        val first = sha256(bytes)
        return try {
            sha256(first)
        } finally {
            first.fill(0)
        }
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun readUInt32Le(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= bytes.size) { "Truncated unsigned integer" }
        return (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }

    private fun writeCompactSize(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "Negative compact-size value" }
        when {
            value < 0xfdL -> out.write(value.toInt())
            value <= 0xffffL -> {
                out.write(0xfd)
                out.write((value and 0xff).toInt())
                out.write(((value ushr 8) and 0xff).toInt())
            }
            value <= 0xffff_ffffL -> {
                out.write(0xfe)
                repeat(4) { shift -> out.write(((value ushr (shift * 8)) and 0xff).toInt()) }
            }
            else -> {
                out.write(0xff)
                repeat(8) { shift -> out.write(((value ushr (shift * 8)) and 0xff).toInt()) }
            }
        }
    }

    private class Cursor(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset

        fun readBytes(count: Int): ByteArray {
            require(count >= 0 && count <= remaining) { "Truncated Bitcoin payload" }
            return bytes.copyOfRange(offset, offset + count).also { offset += count }
        }

        fun readCompactSize(): Long {
            val prefix = readByte()
            return when (prefix) {
                in 0..0xfc -> prefix.toLong()
                0xfd -> readLittleEndian(2).also { require(it >= 0xfdL) { "Non-canonical compact size" } }
                0xfe -> readLittleEndian(4).also { require(it > 0xffffL) { "Non-canonical compact size" } }
                else -> readLittleEndian(8).also {
                    require(it > 0xffff_ffffL) { "Non-canonical compact size" }
                }
            }
        }

        private fun readByte(): Int {
            require(remaining > 0) { "Truncated Bitcoin payload" }
            return bytes[offset++].toInt() and 0xff
        }

        private fun readLittleEndian(count: Int): Long {
            require(count <= 8 && remaining >= count) { "Truncated compact-size value" }
            var result = 0L
            repeat(count) { shift -> result = result or (readByte().toLong() shl (shift * 8)) }
            require(result >= 0) { "Compact-size value exceeds signed bounds" }
            return result
        }
    }

    internal data class ParsedPsbt(
        val global: PsbtMap,
        val inputs: List<PsbtMap>,
        val outputs: List<PsbtMap>,
        val transaction: ParsedTransaction
    ) {
        fun serialize(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(psbtMagic)
            global.writeTo(out)
            inputs.forEach { it.writeTo(out) }
            outputs.forEach { it.writeTo(out) }
            return out.toByteArray()
        }

        fun clear() {
            global.clear()
            inputs.forEach { it.clear() }
            outputs.forEach { it.clear() }
            transaction.clear()
        }
    }

    internal data class PsbtMap(val entries: MutableList<PsbtEntry>) {
        fun singleValue(type: Int): ByteArray? {
            val matches = entries.filter { it.key.size == 1 && it.key[0] == type.toByte() }
            require(matches.size <= 1) { "PSBT map has duplicate singleton fields" }
            return matches.singleOrNull()?.value
        }

        fun strictSingleValue(type: Int, label: String): ByteArray? {
            val matches = entries.filter { it.hasType(type) }
            require(matches.all { it.key.size == 1 }) {
                "PSBT map contains malformed $label key data"
            }
            require(matches.size <= 1) { "PSBT map contains duplicate $label fields" }
            return matches.singleOrNull()?.value
        }

        fun writeTo(out: ByteArrayOutputStream) {
            entries.forEach { entry ->
                writeCompactSize(out, entry.key.size.toLong())
                out.write(entry.key)
                writeCompactSize(out, entry.value.size.toLong())
                out.write(entry.value)
            }
            out.write(0)
        }

        fun clear() = entries.forEach { it.clear() }
    }

    internal data class PsbtEntry(val key: ByteArray, val value: ByteArray) {
        fun hasType(type: Int): Boolean =
            key.isNotEmpty() && (key[0].toInt() and 0xff) == type

        fun clear() {
            key.fill(0)
            value.fill(0)
        }
    }

    internal data class ParsedTransaction(
        val version: ByteArray,
        val inputs: List<ParsedTxIn>,
        val outputs: List<ByteArray>,
        val lockTime: ByteArray
    ) {
        fun clear() {
            version.fill(0)
            inputs.forEach { it.clear() }
            outputs.forEach { it.fill(0) }
            lockTime.fill(0)
        }
    }

    internal data class ParsedTxIn(val outpoint: ByteArray, val sequence: ByteArray) {
        fun clear() {
            outpoint.fill(0)
            sequence.fill(0)
        }
    }

    private data class Derivation(val pubkey: ByteArray, val path: List<Long>)

    private data class PreparedPolicyInput(
        val scriptCode: ByteArray,
        val candidatePubkeys: List<ByteArray>,
        val allowedPartialSignaturePubkeys: List<ByteArray>,
        val subpath: List<Long>
    )

    private data class StandardMultisig(
        val threshold: Int,
        val pubkeys: List<ByteArray>
    )

    private data class StagedPartialSignature(
        val inputMap: PsbtMap,
        val key: ByteArray,
        val value: ByteArray
    ) {
        fun clear() {
            key.fill(0)
            value.fill(0)
        }
    }
}
