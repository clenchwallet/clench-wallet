package net.clench.wallet.security

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Fail-closed policy for signature material returned by an external signer.
 *
 * Clench shows the complete transaction before handing a PSBT to a signer. A
 * returned signature is therefore only acceptable when it commits to every
 * reviewed input and output: ECDSA SIGHASH_ALL, or Taproot DEFAULT/ALL. Weak
 * NONE, SINGLE, or ANYONECANPAY signatures must never enter the canonical
 * PSBT, even when the unsigned transaction itself is unchanged.
 *
 * The PSBT merge deliberately starts with Clench's canonical PSBT and imports
 * only signature/finalization key-value pairs. Signer-supplied UTXOs, scripts,
 * derivations, global xpubs, proprietary fields, and unknown metadata are not
 * trusted or copied.
 */
internal object ExternalSignaturePolicy {
    enum class InputKind {
        ECDSA,
        ECDSA_WITNESS_SCRIPT,
        TAPROOT
    }

    data class FinalizedInput(
        val kind: InputKind,
        val scriptSig: ByteArray,
        val witness: List<ByteArray>
    )

    private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

    private const val PSBT_IN_PARTIAL_SIG = 0x02
    private const val PSBT_IN_SIGHASH_TYPE = 0x03
    private const val PSBT_IN_FINAL_SCRIPTSIG = 0x07
    private const val PSBT_IN_FINAL_SCRIPTWITNESS = 0x08
    private const val PSBT_IN_TAP_KEY_SIG = 0x13
    private const val PSBT_IN_TAP_SCRIPT_SIG = 0x14

    private val IMPORTABLE_SIGNATURE_TYPES = setOf(
        PSBT_IN_PARTIAL_SIG,
        PSBT_IN_FINAL_SCRIPTSIG,
        PSBT_IN_FINAL_SCRIPTWITNESS,
        PSBT_IN_TAP_KEY_SIG,
        PSBT_IN_TAP_SCRIPT_SIG
    )

    fun validatePsbtBase64(
        encoded: String,
        inputKinds: List<InputKind>,
        outputCount: Int
    ) {
        val psbt = parseBase64(encoded, inputKinds.size, outputCount)
        validateInputMaps(psbt.inputMaps, inputKinds)
    }

    /**
     * Return a canonical PSBT containing the current PSBT plus only validated
     * signature material from [returned]. No other returned metadata survives.
     */
    fun mergeSignatureMaterial(
        current: String,
        returned: String,
        inputKinds: List<InputKind>,
        outputCount: Int
    ): String {
        val currentPsbt = parseBase64(current, inputKinds.size, outputCount)
        val returnedPsbt = parseBase64(returned, inputKinds.size, outputCount)

        val canonicalUnsignedTx = currentPsbt.globalMap.unsignedTransaction()
            ?: throw SecurityException("Canonical PSBT is missing its unsigned transaction")
        val returnedUnsignedTx = returnedPsbt.globalMap.unsignedTransaction()
            ?: throw SecurityException("External signer PSBT is missing its unsigned transaction")
        if (!canonicalUnsignedTx.contentEquals(returnedUnsignedTx)) {
            throw SecurityException("External signer PSBT is for a different unsigned transaction")
        }

        validateInputMaps(currentPsbt.inputMaps, inputKinds)
        validateInputMaps(returnedPsbt.inputMaps, inputKinds)

        returnedPsbt.inputMaps.forEachIndexed { inputIndex, returnedMap ->
            val canonicalMap = currentPsbt.inputMaps[inputIndex]
            returnedMap.fields
                .filter { it.type in IMPORTABLE_SIGNATURE_TYPES }
                .forEach { returnedField ->
                    val existing = canonicalMap.fields.firstOrNull {
                        it.key.contentEquals(returnedField.key)
                    }
                    when {
                        existing == null -> canonicalMap.fields += returnedField.deepCopy()
                        existing.value.contentEquals(returnedField.value) -> Unit
                        else -> throw SecurityException(
                            "External signer returned conflicting signature material for input $inputIndex"
                        )
                    }
                }
        }

        // Validate the exact post-merge object as a defense against interactions
        // between previously collected and newly returned multisig material.
        validateInputMaps(currentPsbt.inputMaps, inputKinds)
        return Base64.getEncoder().encodeToString(currentPsbt.serialize())
    }

    /** Validate sighash bytes embedded in a finalized/raw transaction. */
    fun validateFinalizedInputs(inputs: List<FinalizedInput>) {
        inputs.forEachIndexed { index, input ->
            val context = "finalized transaction input $index"
            when (input.kind) {
                InputKind.TAPROOT -> {
                    requireNoEcdsaSignatures(input.scriptSig, input.witness, context)
                    validateTaprootWitness(input.witness, context, requireSignature = true)
                }
                InputKind.ECDSA,
                InputKind.ECDSA_WITNESS_SCRIPT -> {
                    val scriptItems = parseScriptPushes(input.scriptSig, context)
                    val witnessItems = if (
                        input.kind == InputKind.ECDSA_WITNESS_SCRIPT && input.witness.isNotEmpty()
                    ) {
                        input.witness.dropLast(1) // the final item is the witness script
                    } else {
                        input.witness
                    }
                    val found = validateEcdsaItems(scriptItems + witnessItems, context)
                    if (found == 0) {
                        throw SecurityException("$context contains no valid ECDSA SIGHASH_ALL signature")
                    }
                }
            }
        }
    }

    private fun validateInputMaps(inputMaps: List<PsbtMap>, inputKinds: List<InputKind>) {
        require(inputMaps.size == inputKinds.size) { "PSBT input count does not match the unsigned transaction" }
        inputMaps.forEachIndexed { index, map ->
            validateInputMap(index, map, inputKinds[index])
        }
    }

    private fun validateInputMap(index: Int, map: PsbtMap, kind: InputKind) {
        val context = "PSBT input $index"
        var finalScriptSig: ByteArray? = null
        var finalScriptWitness: List<ByteArray>? = null

        map.fields.forEach { field ->
            when (field.type) {
                PSBT_IN_SIGHASH_TYPE -> {
                    requireKeySize(field, 1, context)
                    if (field.value.size != 4) {
                        throw SecurityException("$context has a malformed sighash-type field")
                    }
                    val sighash = littleEndianUInt32(field.value)
                    val allowed = when (kind) {
                        InputKind.TAPROOT -> sighash == 0L || sighash == 1L
                        InputKind.ECDSA,
                        InputKind.ECDSA_WITNESS_SCRIPT -> sighash == 1L
                    }
                    if (!allowed) rejectSighash(context, sighash)
                }
                PSBT_IN_PARTIAL_SIG -> {
                    if (kind == InputKind.TAPROOT) {
                        throw SecurityException("$context contains an ECDSA signature for a Taproot input")
                    }
                    if (field.key.size <= 1) {
                        throw SecurityException("$context has a partial signature without a public key")
                    }
                    validateEcdsaSignature(field.value, "$context partial signature")
                }
                PSBT_IN_TAP_KEY_SIG -> {
                    requireKeySize(field, 1, context)
                    requireTaproot(kind, context)
                    validateTaprootSignature(field.value, "$context key-path signature")
                }
                PSBT_IN_TAP_SCRIPT_SIG -> {
                    requireKeySize(field, 65, context)
                    requireTaproot(kind, context)
                    validateTaprootSignature(field.value, "$context script-path signature")
                }
                PSBT_IN_FINAL_SCRIPTSIG -> {
                    requireKeySize(field, 1, context)
                    finalScriptSig = field.value
                }
                PSBT_IN_FINAL_SCRIPTWITNESS -> {
                    requireKeySize(field, 1, context)
                    finalScriptWitness = parseWitness(field.value, "$context final witness")
                }
            }
        }

        if (finalScriptSig != null || finalScriptWitness != null) {
            val finalized = FinalizedInput(
                kind = kind,
                scriptSig = finalScriptSig ?: byteArrayOf(),
                witness = finalScriptWitness ?: emptyList()
            )
            validateFinalizedInputs(listOf(finalized))
        }
    }

    private fun validateEcdsaSignature(signature: ByteArray, context: String) {
        if (!isStrictDerSignatureWithHashType(signature)) {
            throw SecurityException("$context is not a canonical DER signature")
        }
        val sighash = signature.last().toInt() and 0xff
        if (sighash != 0x01) rejectSighash(context, sighash.toLong())
    }

    private fun validateEcdsaItems(items: List<ByteArray>, context: String): Int {
        var signatures = 0
        items.forEach { item ->
            if (isStrictDerSignatureWithHashType(item)) {
                validateEcdsaSignature(item, context)
                signatures++
            }
        }
        return signatures
    }

    private fun validateTaprootSignature(signature: ByteArray, context: String) {
        when (signature.size) {
            64 -> Unit // SIGHASH_DEFAULT is encoded by omitting the flag byte.
            65 -> {
                val sighash = signature.last().toInt() and 0xff
                if (sighash != 0x01) rejectSighash(context, sighash.toLong())
            }
            else -> throw SecurityException("$context has an invalid Schnorr signature length")
        }
    }

    private fun validateTaprootWitness(
        witness: List<ByteArray>,
        context: String,
        requireSignature: Boolean
    ) {
        val items = witness.toMutableList()
        if (items.size >= 2 && items.lastOrNull()?.firstOrNull() == 0x50.toByte()) {
            items.removeAt(items.lastIndex) // optional BIP341 annex
        }

        val candidateSignatures = when {
            items.size == 1 -> items
            items.size >= 2 && isTaprootControlBlock(items.last()) -> items.dropLast(2)
            else -> items
        }.filter { it.size == 64 || it.size == 65 }

        candidateSignatures.forEach { validateTaprootSignature(it, context) }
        if (requireSignature && candidateSignatures.isEmpty()) {
            throw SecurityException("$context contains no Taproot DEFAULT/ALL signature")
        }
    }

    private fun requireNoEcdsaSignatures(
        scriptSig: ByteArray,
        witness: List<ByteArray>,
        context: String
    ) {
        val candidates = parseScriptPushes(scriptSig, context) + witness
        candidates.forEach { candidate ->
            if (isStrictDerSignatureWithHashType(candidate)) {
                throw SecurityException("$context contains an unexpected ECDSA signature")
            }
        }
    }

    private fun isTaprootControlBlock(value: ByteArray): Boolean =
        value.size >= 33 && (value.size - 33) % 32 == 0 &&
            ((value[0].toInt() and 0xfe) == 0xc0)

    /** Bitcoin Core's strict DER checks, with the final sighash byte included. */
    private fun isStrictDerSignatureWithHashType(signature: ByteArray): Boolean {
        if (signature.size !in 9..73) return false
        if ((signature[0].toInt() and 0xff) != 0x30) return false
        if ((signature[1].toInt() and 0xff) != signature.size - 3) return false

        val lenR = signature[3].toInt() and 0xff
        if (5 + lenR >= signature.size) return false
        val lenS = signature[5 + lenR].toInt() and 0xff
        if (lenR + lenS + 7 != signature.size) return false
        if ((signature[2].toInt() and 0xff) != 0x02 || lenR == 0) return false
        if ((signature[4].toInt() and 0x80) != 0) return false
        if (lenR > 1 && signature[4] == 0.toByte() && (signature[5].toInt() and 0x80) == 0) return false

        val sTypeIndex = lenR + 4
        val sValueIndex = lenR + 6
        if ((signature[sTypeIndex].toInt() and 0xff) != 0x02 || lenS == 0) return false
        if ((signature[sValueIndex].toInt() and 0x80) != 0) return false
        if (
            lenS > 1 && signature[sValueIndex] == 0.toByte() &&
            (signature[sValueIndex + 1].toInt() and 0x80) == 0
        ) return false
        return true
    }

    private fun parseScriptPushes(script: ByteArray, context: String): List<ByteArray> {
        val pushes = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < script.size) {
            val opcode = script[offset++].toInt() and 0xff
            val length = when (opcode) {
                in 0x01..0x4b -> opcode.toLong()
                0x4c -> readScriptLength(script, offset, 1, context).also { offset += 1 }
                0x4d -> readScriptLength(script, offset, 2, context).also { offset += 2 }
                0x4e -> readScriptLength(script, offset, 4, context).also { offset += 4 }
                else -> continue
            }
            if (length !in 0..Int.MAX_VALUE.toLong() || offset > script.size - length.toInt()) {
                throw SecurityException("$context contains a truncated script push")
            }
            pushes += script.copyOfRange(offset, offset + length.toInt())
            offset += length.toInt()
        }
        return pushes
    }

    private fun readScriptLength(
        script: ByteArray,
        offset: Int,
        bytes: Int,
        context: String
    ): Long {
        if (offset > script.size - bytes) {
            throw SecurityException("$context contains a truncated PUSHDATA length")
        }
        var value = 0L
        repeat(bytes) { index ->
            value = value or ((script[offset + index].toLong() and 0xffL) shl (index * 8))
        }
        return value
    }

    private fun parseWitness(serialized: ByteArray, context: String): List<ByteArray> {
        val cursor = Cursor(serialized, 0, context)
        val count = cursor.readCompactSize("item count")
        if (count !in 0..100_000L) throw SecurityException("$context has too many items")
        val items = ArrayList<ByteArray>(count.toInt())
        repeat(count.toInt()) { index ->
            val length = cursor.readCompactSize("item $index length")
            items += cursor.readBytes(length, "item $index")
        }
        if (!cursor.atEnd()) throw SecurityException("$context has trailing bytes")
        return items
    }

    private fun requireTaproot(kind: InputKind, context: String) {
        if (kind != InputKind.TAPROOT) {
            throw SecurityException("$context contains a Taproot signature for a non-Taproot input")
        }
    }

    private fun requireKeySize(field: PsbtField, size: Int, context: String) {
        if (field.key.size != size) {
            throw SecurityException("$context has a malformed PSBT signature key")
        }
    }

    private fun rejectSighash(context: String, sighash: Long): Nothing {
        throw SecurityException(
            "$context uses disallowed sighash 0x${sighash.toString(16)}; " +
                "external signatures must commit to every reviewed input and output"
        )
    }

    private fun littleEndianUInt32(value: ByteArray): Long =
        value.foldIndexed(0L) { index, result, byte ->
            result or ((byte.toLong() and 0xffL) shl (index * 8))
        }

    private fun parseBase64(encoded: String, inputCount: Int, outputCount: Int): ParsedPsbt {
        PsbtSafety.inspectBase64(encoded)
        val normalized = encoded.filterNot(Char::isWhitespace)
        val bytes = try {
            Base64.getDecoder().decode(normalized)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("External signer returned invalid PSBT Base64", e)
        }
        return ParsedPsbt.parse(bytes, inputCount, outputCount)
    }

    private data class PsbtField(val key: ByteArray, val value: ByteArray) {
        val type: Int get() = key.first().toInt() and 0xff
        fun deepCopy() = PsbtField(key.copyOf(), value.copyOf())
    }

    private data class PsbtMap(val fields: MutableList<PsbtField>) {
        fun unsignedTransaction(): ByteArray? = fields.firstOrNull {
            it.type == 0x00 && it.key.size == 1
        }?.value

        fun serialize(output: ByteArrayOutputStream) {
            fields.forEach { field ->
                output.writeCompactSize(field.key.size.toLong())
                output.write(field.key)
                output.writeCompactSize(field.value.size.toLong())
                output.write(field.value)
            }
            output.write(0)
        }
    }

    private data class ParsedPsbt(
        val globalMap: PsbtMap,
        val inputMaps: List<PsbtMap>,
        val outputMaps: List<PsbtMap>
    ) {
        fun serialize(): ByteArray = ByteArrayOutputStream().also { output ->
            output.write(PSBT_MAGIC)
            globalMap.serialize(output)
            inputMaps.forEach { it.serialize(output) }
            outputMaps.forEach { it.serialize(output) }
        }.toByteArray()

        companion object {
            fun parse(bytes: ByteArray, inputCount: Int, outputCount: Int): ParsedPsbt {
                if (inputCount < 0 || outputCount < 0) throw SecurityException("Invalid PSBT map count")
                if (bytes.size < PSBT_MAGIC.size || !bytes.copyOfRange(0, PSBT_MAGIC.size).contentEquals(PSBT_MAGIC)) {
                    throw SecurityException("External signer returned invalid PSBT magic")
                }
                val cursor = Cursor(bytes, PSBT_MAGIC.size, "PSBT")
                val global = cursor.readMap("global")
                val inputs = List(inputCount) { cursor.readMap("input $it") }
                val outputs = List(outputCount) { cursor.readMap("output $it") }
                if (!cursor.atEnd()) throw SecurityException("PSBT contains unexpected extra maps")
                return ParsedPsbt(global, inputs, outputs)
            }
        }
    }

    private class Cursor(
        private val bytes: ByteArray,
        private var offset: Int,
        private val context: String
    ) {
        fun atEnd(): Boolean = offset == bytes.size

        fun readMap(label: String): PsbtMap {
            val fields = mutableListOf<PsbtField>()
            while (true) {
                val keyLength = readCompactSize("$label key length")
                if (keyLength == 0L) break
                val key = readBytes(keyLength, "$label key")
                if (key.isEmpty()) throw SecurityException("$context $label contains an empty key")
                val valueLength = readCompactSize("$label value length")
                val value = readBytes(valueLength, "$label value")
                fields += PsbtField(key, value)
            }
            return PsbtMap(fields)
        }

        fun readCompactSize(label: String): Long {
            val first = readUnsignedByte(label)
            return when (first) {
                in 0..252 -> first.toLong()
                253 -> readLittleEndian(2, label).also {
                    if (it < 253) throw SecurityException("$context uses a non-canonical CompactSize")
                }
                254 -> readLittleEndian(4, label).also {
                    if (it <= 0xffffL) throw SecurityException("$context uses a non-canonical CompactSize")
                }
                else -> readLittleEndian(8, label).also {
                    if (it <= 0xffffffffL) throw SecurityException("$context uses a non-canonical CompactSize")
                }
            }
        }

        fun readBytes(length: Long, label: String): ByteArray {
            if (length !in 0..Int.MAX_VALUE.toLong()) {
                throw SecurityException("$context $label is too large")
            }
            val size = length.toInt()
            if (offset > bytes.size - size) throw SecurityException("$context is truncated while reading $label")
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }

        private fun readUnsignedByte(label: String): Int {
            if (offset >= bytes.size) throw SecurityException("$context is truncated while reading $label")
            return bytes[offset++].toInt() and 0xff
        }

        private fun readLittleEndian(count: Int, label: String): Long {
            if (offset > bytes.size - count) throw SecurityException("$context is truncated while reading $label")
            var value = 0L
            repeat(count) { index ->
                val part = bytes[offset++].toLong() and 0xffL
                if (index == 7 && part and 0x80L != 0L) {
                    throw SecurityException("$context $label exceeds the supported size")
                }
                value = value or (part shl (index * 8))
            }
            return value
        }
    }

    private fun ByteArrayOutputStream.writeCompactSize(value: Long) {
        when {
            value < 253L -> write(value.toInt())
            value <= 0xffffL -> {
                write(253)
                writeLittleEndian(value, 2)
            }
            value <= 0xffffffffL -> {
                write(254)
                writeLittleEndian(value, 4)
            }
            else -> {
                write(255)
                writeLittleEndian(value, 8)
            }
        }
    }

    private fun ByteArrayOutputStream.writeLittleEndian(value: Long, count: Int) {
        repeat(count) { index -> write(((value ushr (index * 8)) and 0xffL).toInt()) }
    }
}
