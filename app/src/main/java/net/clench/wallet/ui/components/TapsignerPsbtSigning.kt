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

/**
 * TAPSIGNER bridge for PSBT v0 native-SegWit inputs.
 *
 * The bridge deliberately supports only ECDSA SIGHASH_ALL for native P2WPKH.
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
                error("TAPSIGNER already signed input ${request.inputIndex + 1}")
            }
            val der = compactSignatureToDer(signature.compactSignature)
            inputMap.entries += PsbtEntry(partialSigKey, der + byteArrayOf(sighashAll.toByte()))
            der.fill(0)
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
        hashPrevouts: ByteArray,
        hashSequences: ByteArray,
        hashOutputs: ByteArray
    ): Request {
        require(inputMap.entries.none {
            it.key.isNotEmpty() &&
                (it.key[0] == 0x02.toByte() || it.key[0] == 0x07.toByte() || it.key[0] == 0x08.toByte())
        }) {
            "TAPSIGNER input ${inputIndex + 1} already contains signature or finalization data"
        }
        inputMap.singleValue(0x03)?.let { value ->
            require(value.size == 4 && readUInt32Le(value, 0) == sighashAll) {
                "TAPSIGNER supports only ECDSA SIGHASH_ALL"
            }
        }
        val witnessUtxo = inputMap.singleValue(0x01)
            ?: error("TAPSIGNER input ${inputIndex + 1} is missing witness UTXO data")
        val (amount, witnessProgram) = parseTxOut(witnessUtxo)
        val derivations = inputMap.entries.mapNotNull { entry ->
            if (entry.key.size != 34 || entry.key[0] != 0x06.toByte()) return@mapNotNull null
            val pubkey = entry.key.copyOfRange(1, 34)
            if (pubkey[0] != 0x02.toByte() && pubkey[0] != 0x03.toByte()) {
                pubkey.fill(0)
                error("PSBT input ${inputIndex + 1} contains an uncompressed derivation key")
            }
            val path = parseKeySourcePath(entry.value)
            if (!path.startsWith(accountPath)) {
                pubkey.fill(0)
                return@mapNotNull null
            }
            val relative = path.drop(accountPath.size)
            if (relative.size !in 0..2 || relative.any { it >= hardenedFlag }) {
                pubkey.fill(0)
                error("PSBT input ${inputIndex + 1} has an unsupported TAPSIGNER relative path")
            }
            Derivation(pubkey, relative)
        }
        if (derivations.isEmpty()) {
            error("PSBT input ${inputIndex + 1} has no key below the active TAPSIGNER account path")
        }
        val subpaths = derivations.map { it.subpath }.distinct()
        if (subpaths.size != 1) {
            derivations.forEach { it.pubkey.fill(0) }
            error("PSBT input ${inputIndex + 1} has conflicting TAPSIGNER subpaths")
        }

        val (scriptCode, eligibleDerivations) = when {
            witnessProgram.isP2wpkh() -> {
                val keyHash = witnessProgram.copyOfRange(2, 22)
                val matching = derivations.filter { hash160(it.pubkey).contentEquals(keyHash) }
                keyHash.fill(0)
                require(matching.size == 1) {
                    "PSBT input ${inputIndex + 1} TAPSIGNER key does not match its P2WPKH output"
                }
                (byteArrayOf(0x76, 0xa9.toByte(), 0x14) +
                    witnessProgram.copyOfRange(2, 22) +
                    byteArrayOf(0x88.toByte(), 0xac.toByte())) to matching
            }
            else -> error("TAPSIGNER signing supports only native SegWit P2WPKH inputs")
        }
        derivations.filterNot { eligibleDerivations.contains(it) }.forEach { it.pubkey.fill(0) }

        val digest = try {
            computeBip143SighashAll(
                parsed.transaction,
                inputIndex,
                scriptCode,
                amount,
                hashPrevouts,
                hashSequences,
                hashOutputs
            )
        } finally {
            scriptCode.fill(0)
            amount.fill(0)
            witnessProgram.fill(0)
        }
        return Request(
            inputIndex = inputIndex,
            digest = digest,
            subpath = subpaths.single(),
            candidatePubkeys = eligibleDerivations.map { it.pubkey }
        )
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

    private data class Derivation(val pubkey: ByteArray, val subpath: List<Long>)
}
