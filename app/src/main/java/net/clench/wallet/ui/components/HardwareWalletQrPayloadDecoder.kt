package net.clench.wallet.ui.components

import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoCoinInfo
import com.sparrowwallet.hummingbird.registry.MultiKey
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import com.sparrowwallet.hummingbird.registry.RegistryType
import com.sparrowwallet.hummingbird.registry.RegistryItem
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.URAccountDescriptor
import com.sparrowwallet.hummingbird.registry.URHDKey
import com.sparrowwallet.hummingbird.registry.UROutputDescriptor
import com.sparrowwallet.hummingbird.registry.URPSBT
import net.clench.wallet.security.InputLimits
import net.clench.wallet.security.PsbtSafety
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Normalizes QR payloads emitted by air-gapped hardware wallets into the text
 * Clench's existing import flows expect: xpub-with-origin, descriptor text, or
 * base64 PSBT/transaction bytes for signing flows.
 */
object HardwareWalletQrPayloadDecoder {
    private const val MAX_STATIC_BASE43_CHARS = 4_296
    private const val BASE43_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\$*+-./:"
    private val base43Indexes = IntArray(128) { -1 }.also { indexes ->
        BASE43_ALPHABET.forEachIndexed { index, character -> indexes[character.code] = index }
    }

    fun decodeUrPayload(ur: UR): String? {
        val registryItem = runCatching { ur.decodeFromRegistry() }.getOrNull()
        return when (registryItem) {
            is URPSBT -> encodeValidatedPsbt(registryItem.psbt)
            is CryptoPSBT -> encodeValidatedPsbt(registryItem.psbt)
            is CryptoAccount -> registryItem.outputDescriptors
                .mapNotNull { decodeCryptoOutput(it) }
                .preferredImportPayload()
            is URAccountDescriptor -> registryItem.outputDescriptors
                .mapNotNull { decodeUrOutputDescriptor(it) }
                .preferredImportPayload()
            is CryptoOutput -> decodeCryptoOutput(registryItem)
            is UROutputDescriptor -> decodeUrOutputDescriptor(registryItem)
            is CryptoHDKey -> xpubWithOrigin(registryItem)
            is URHDKey -> xpubWithOrigin(registryItem)
            else -> when (ur.registryType) {
                RegistryType.BYTES -> runCatching { normalizeBytePayload(ur.toBytes()) }.getOrNull()
                else -> null
            }
        }
    }

    /**
     * Normalize a static QR payload without changing ordinary descriptor, xpub,
     * Base64, or transaction text. Electrum-style Base43 is decoded only when it
     * is small enough for a static QR and the result is a structurally valid PSBT
     * or Bitcoin transaction.
     */
    fun normalizeStaticPayload(payload: String): String {
        val trimmed = payload.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_STATIC_BASE43_CHARS) return trimmed
        val decoded = decodeBase43(trimmed) ?: return trimmed
        return normalizeRecognizedBinary(decoded) ?: trimmed
    }

    private fun normalizeBytePayload(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > InputLimits.PSBT_BYTES) return null
        normalizeRecognizedBinary(bytes)?.let { return it }
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .trim()
                .takeIf { text ->
                    text.isNotEmpty() && text.all { character ->
                        !Character.isISOControl(character.code) || character in "\t\n\r"
                    }
                }
        }.getOrNull()
    }

    private fun normalizeRecognizedBinary(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > InputLimits.PSBT_BYTES) return null
        if (isValidPsbt(bytes) || isBitcoinTransaction(bytes)) {
            return Base64.getEncoder().encodeToString(bytes)
        }
        return null
    }

    private fun encodeValidatedPsbt(bytes: ByteArray): String? {
        if (!isValidPsbt(bytes)) return null
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun isValidPsbt(bytes: ByteArray): Boolean {
        if (bytes.size > InputLimits.PSBT_BYTES) return false
        return runCatching { PsbtSafety.inspectBytes(bytes) }.isSuccess
    }

    private fun isBitcoinTransaction(bytes: ByteArray): Boolean {
        if (bytes.size !in 10..InputLimits.PSBT_BYTES) return false
        return runCatching {
            val cursor = BitcoinTransactionCursor(bytes)
            cursor.skip(4) // version

            val hasWitness = if (cursor.peekUnsignedByte() == 0) {
                cursor.skip(1) // marker
                check(cursor.readUnsignedByte() == 1) { "Unsupported transaction witness flag" }
                true
            } else {
                false
            }

            val inputCount = cursor.readCompactSize()
            check(inputCount in 1..MAX_TX_ITEMS)
            check(inputCount <= cursor.remaining / MIN_TX_INPUT_BYTES)
            repeat(inputCount) {
                cursor.skip(32 + 4) // previous outpoint
                cursor.skip(cursor.readCompactSize()) // scriptSig
                cursor.skip(4) // sequence
            }

            val outputCount = cursor.readCompactSize()
            check(outputCount in 1..MAX_TX_ITEMS)
            check(outputCount <= cursor.remaining / MIN_TX_OUTPUT_BYTES)
            repeat(outputCount) {
                cursor.skip(8) // value
                cursor.skip(cursor.readCompactSize()) // scriptPubKey
            }

            if (hasWitness) {
                var witnessItems = 0
                repeat(inputCount) {
                    val stackItems = cursor.readCompactSize()
                    check(stackItems <= cursor.remaining)
                    check(witnessItems <= MAX_TX_ITEMS - stackItems)
                    witnessItems += stackItems
                    repeat(stackItems) {
                        cursor.skip(cursor.readCompactSize())
                    }
                }
            }

            cursor.skip(4) // locktime
            check(cursor.atEnd()) { "Transaction contains trailing bytes" }
        }.isSuccess
    }

    private fun decodeBase43(input: String): ByteArray? {
        if (input.isEmpty() || input.length > MAX_STATIC_BASE43_CHARS) return null
        val digits = ByteArray(input.length)
        input.forEachIndexed { index, character ->
            val value = if (character.code < base43Indexes.size) {
                base43Indexes[character.code]
            } else {
                -1
            }
            if (value < 0) return null
            digits[index] = value.toByte()
        }

        var zeroCount = 0
        while (zeroCount < digits.size && digits[zeroCount].toInt() == 0) zeroCount++

        val decoded = ByteArray(input.length)
        var outputIndex = decoded.size
        var startAt = zeroCount
        while (startAt < digits.size) {
            var remainder = 0
            for (index in startAt until digits.size) {
                val value = digits[index].toInt() and 0xff
                val accumulator = remainder * 43 + value
                digits[index] = (accumulator / 256).toByte()
                remainder = accumulator % 256
            }
            if (digits[startAt].toInt() == 0) startAt++
            decoded[--outputIndex] = remainder.toByte()
        }

        while (outputIndex < decoded.size && decoded[outputIndex].toInt() == 0) outputIndex++
        val firstByte = outputIndex - zeroCount
        if (firstByte < 0) return null
        return decoded.copyOfRange(firstByte, decoded.size)
            .takeIf { it.size <= InputLimits.PSBT_BYTES }
    }

    private class BitcoinTransactionCursor(private val bytes: ByteArray) {
        var offset: Int = 0
            private set

        val remaining: Int get() = bytes.size - offset

        fun atEnd(): Boolean = offset == bytes.size

        fun peekUnsignedByte(): Int {
            check(offset < bytes.size) { "Transaction is truncated" }
            return bytes[offset].toInt() and 0xff
        }

        fun readUnsignedByte(): Int {
            val value = peekUnsignedByte()
            offset++
            return value
        }

        fun readCompactSize(): Int {
            return when (val first = readUnsignedByte()) {
                in 0..252 -> first
                253 -> readLittleEndian(2).also { check(it >= 253) }.toInt()
                254 -> readLittleEndian(4).also {
                    check(it > 0xffffL && it <= Int.MAX_VALUE.toLong())
                }.toInt()
                else -> {
                    // Any canonical 0xff CompactSize is larger than a static QR payload.
                    skip(8)
                    error("Transaction field exceeds the supported size")
                }
            }
        }

        fun skip(length: Int) {
            check(length >= 0 && length <= remaining) { "Transaction is truncated" }
            offset += length
        }

        private fun readLittleEndian(byteCount: Int): Long {
            check(byteCount in 1..4 && byteCount <= remaining) { "Transaction is truncated" }
            var value = 0L
            repeat(byteCount) { index ->
                value = value or ((readUnsignedByte().toLong() and 0xffL) shl (index * 8))
            }
            return value
        }
    }

    private const val MAX_TX_ITEMS = 100_000
    private const val MIN_TX_INPUT_BYTES = 41
    private const val MIN_TX_OUTPUT_BYTES = 9

    fun decodeBbqrPayload(fileType: Char, rawBytes: ByteArray): String {
        return if (fileType == 'P' || fileType == 'T') {
            Base64.getEncoder().encodeToString(rawBytes)
        } else {
            rawBytes.toString(Charsets.UTF_8).trim()
        }
    }

    private fun decodeCryptoOutput(output: CryptoOutput): String? {
        output.hdKey?.let { return singleKeyDescriptor(output.scriptExpressions, it) }
        output.multiKey?.let { return multisigDescriptor(output.scriptExpressions, it) }
        return null
    }

    private fun singleKeyDescriptor(
        scriptExpressions: List<ScriptExpression>,
        hdKey: CryptoHDKey
    ): String? {
        if (scriptExpressions.isEmpty()) return null
        var descriptor = descriptorKeyText(hdKey) ?: return null
        scriptExpressions.asReversed().forEach { expression ->
            descriptor = wrapDescriptorExpression(expression, descriptor) ?: return null
        }
        return descriptor
    }

    private fun decodeUrOutputDescriptor(output: UROutputDescriptor): String? {
        val source = output.source?.takeIf { it.isNotBlank() }
        val keyTexts = output.keys.mapNotNull { registryKeyText(it) }
        if (source != null) {
            val expanded = expandDescriptorPlaceholders(source, keyTexts)
            if (expanded.isNotBlank() && !expanded.contains('@')) return expanded
            if (!source.contains('@')) return source
            return null
        }
        return keyTexts.singleOrNull()
    }

    private fun registryKeyText(key: RegistryItem): String? {
        return when (key) {
            is CryptoHDKey -> xpubWithOrigin(key)
            is URHDKey -> xpubWithOrigin(key)
            is CryptoOutput -> decodeCryptoOutput(key)
            is UROutputDescriptor -> decodeUrOutputDescriptor(key)
            else -> null
        }
    }

    private fun expandDescriptorPlaceholders(source: String, keys: List<String>): String {
        var expanded = source
        keys.forEachIndexed { index, key ->
            expanded = expanded.replace(Regex("@$index(/\\*\\*|/[01]/\\*|/\\*)?(?!\\d)")) { match ->
                val suffix = match.groupValues.getOrNull(1).orEmpty()
                when (suffix) {
                    "/**", "/*", "" -> "$key/0/*"
                    else -> "$key$suffix"
                }
            }
        }
        return expanded
    }

    private fun multisigDescriptor(
        scriptExpressions: List<ScriptExpression>,
        multiKey: MultiKey
    ): String? {
        val hdKeys = multiKey.hdKeys.orEmpty()
        if (hdKeys.isEmpty()) return null
        if (multiKey.threshold !in 1..hdKeys.size) return null

        val keys = hdKeys.map { descriptorKeyText(it) ?: return null }
        val multisigFunction = if (scriptExpressions.contains(ScriptExpression.SORTED_MULTISIG)) {
            "sortedmulti"
        } else {
            "multi"
        }
        var descriptor = "$multisigFunction(${multiKey.threshold},${keys.joinToString(",")})"

        scriptExpressions
            .filterNot { it == ScriptExpression.MULTISIG || it == ScriptExpression.SORTED_MULTISIG }
            .asReversed()
            .forEach { expression ->
                descriptor = wrapDescriptorExpression(expression, descriptor) ?: return null
            }

        return descriptor
    }

    private fun descriptorKeyText(key: CryptoHDKey): String? {
        val xpub = xpubWithOrigin(key) ?: return null
        val childPath = externalChildPath(key.children?.path)
        return if (childPath.isBlank()) xpub else "$xpub/$childPath"
    }

    private fun externalChildPath(path: String?): String {
        val normalized = path
            ?.trim()
            ?.removePrefix("m/")
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
            ?: return "0/*"

        return normalized
            .replace(Regex("<([^;>]+);[^>]+>")) { match -> match.groupValues[1] }
            .let { if (it == "*" || it == "**") "0/*" else it }
    }

    private fun wrapDescriptorExpression(expression: ScriptExpression, inner: String): String? {
        val function = when (expression) {
            ScriptExpression.SCRIPT_HASH -> "sh"
            ScriptExpression.WITNESS_SCRIPT_HASH -> "wsh"
            ScriptExpression.PUBLIC_KEY -> "pk"
            ScriptExpression.PUBLIC_KEY_HASH -> "pkh"
            ScriptExpression.WITNESS_PUBLIC_KEY_HASH -> "wpkh"
            ScriptExpression.TAPROOT -> "tr"
            else -> return null
        }
        return "$function($inner)"
    }

    private fun List<String>.preferredImportPayload(): String? {
        return maxByOrNull { payloadScore(it) }
    }

    private fun payloadScore(text: String): Int {
        val lower = text.lowercase()
        return if (
            lower.startsWith("wpkh(") ||
            lower.startsWith("pkh(") ||
            lower.startsWith("sh(") ||
            lower.startsWith("wsh(") ||
            lower.startsWith("tr(")
        ) {
            2
        } else {
            1
        }
    }

    private fun xpubWithOrigin(key: CryptoHDKey): String? {
        if (key.isPrivateKey) return null
        val pubKey = key.key ?: return null
        val chainCode = key.chainCode ?: return null
        if (pubKey.size != 33 || chainCode.size != 32) return null

        val origin = key.origin
        val originPath = origin?.path?.removePrefix("m/").orEmpty()
        val fingerprint = (origin?.sourceFingerprint ?: key.parentFingerprint)?.toHexUpper().orEmpty()
        val xpub = serializeXpub(
            publicKey = pubKey,
            chainCode = chainCode,
            testnet = key.useInfo?.network == CryptoCoinInfo.Network.TESTNET,
            depth = originDepth(originPath),
            parentFingerprint = key.parentFingerprint ?: ByteArray(4),
            childNumber = lastPathComponent(originPath)
        )
        return if (fingerprint.isNotBlank() && originPath.isNotBlank()) {
            "[$fingerprint/$originPath]$xpub"
        } else xpub
    }

    private fun originDepth(path: String): Int {
        if (path.isBlank()) return 0
        return path.split('/').count { it.isNotBlank() }.coerceIn(0, 255)
    }

    private fun lastPathComponent(path: String): Int {
        val last = path.split('/').lastOrNull { it.isNotBlank() } ?: return 0
        val hardened = last.endsWith("'") || last.endsWith("h") || last.endsWith("H")
        val index = last.trimEnd('\'', 'h', 'H').toIntOrNull() ?: return 0
        return if (hardened) index or 0x80000000.toInt() else index
    }

    private fun serializeXpub(
        publicKey: ByteArray,
        chainCode: ByteArray,
        testnet: Boolean,
        depth: Int,
        parentFingerprint: ByteArray,
        childNumber: Int
    ): String {
        val version = if (testnet) {
            byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xCF.toByte()) // tpub
        } else {
            byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E) // xpub
        }
        val payload = ByteArray(78)
        version.copyInto(payload, 0)
        payload[4] = depth.toByte()
        val parent = if (parentFingerprint.size >= 4) parentFingerprint.copyOfRange(0, 4) else ByteArray(4)
        parent.copyInto(payload, 5)
        writeInt32(payload, 9, childNumber)
        chainCode.copyInto(payload, 13)
        publicKey.copyInto(payload, 45)
        val checksum = doubleSha256(payload).copyOfRange(0, 4)
        return base58Encode(payload + checksum)
    }

    private fun writeInt32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xff).toByte()
        target[offset + 1] = ((value ushr 16) and 0xff).toByte()
        target[offset + 2] = ((value ushr 8) and 0xff).toByte()
        target[offset + 3] = (value and 0xff).toByte()
    }

    private fun doubleSha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(data))
    }

    private fun base58Encode(input: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = BigInteger(1, input)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val divRem = num.divideAndRemainder(BigInteger.valueOf(58))
            num = divRem[0]
            sb.append(alphabet[divRem[1].toInt()])
        }
        for (byte in input) {
            if (byte == 0.toByte()) sb.append('1') else break
        }
        return sb.reverse().toString()
    }

    private fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }
}
