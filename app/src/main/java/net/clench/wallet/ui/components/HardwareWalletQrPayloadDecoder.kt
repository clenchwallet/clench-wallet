package net.clench.wallet.ui.components

import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoCoinInfo
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import com.sparrowwallet.hummingbird.registry.RegistryType
import com.sparrowwallet.hummingbird.registry.RegistryItem
import com.sparrowwallet.hummingbird.registry.URAccountDescriptor
import com.sparrowwallet.hummingbird.registry.URHDKey
import com.sparrowwallet.hummingbird.registry.UROutputDescriptor
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Normalizes QR payloads emitted by air-gapped hardware wallets into the text
 * Clench's existing import flows expect: xpub-with-origin, descriptor text, or
 * base64 PSBT/transaction bytes for signing flows.
 */
object HardwareWalletQrPayloadDecoder {
    fun decodeUrPayload(ur: UR): String? {
        val registryItem = runCatching { ur.decodeFromRegistry() }.getOrNull()
        return when (registryItem) {
            is CryptoPSBT -> android.util.Base64.encodeToString(registryItem.psbt, android.util.Base64.NO_WRAP)
            is CryptoAccount -> registryItem.outputDescriptors
                .mapNotNull { decodeCryptoOutput(it) }
                .firstOrNull()
            is URAccountDescriptor -> registryItem.outputDescriptors
                .mapNotNull { decodeUrOutputDescriptor(it) }
                .firstOrNull()
            is CryptoOutput -> decodeCryptoOutput(registryItem)
            is UROutputDescriptor -> decodeUrOutputDescriptor(registryItem)
            is CryptoHDKey -> xpubWithOrigin(registryItem)
            is URHDKey -> xpubWithOrigin(registryItem)
            else -> when (ur.registryType) {
                RegistryType.BYTES -> runCatching { ur.toBytes().toString(Charsets.UTF_8).trim() }.getOrNull()
                else -> null
            }
        }
    }

    fun decodeBbqrPayload(fileType: Char, rawBytes: ByteArray): String {
        return if (fileType == 'P' || fileType == 'T') {
            android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)
        } else {
            rawBytes.toString(Charsets.UTF_8).trim()
        }
    }

    private fun decodeCryptoOutput(output: CryptoOutput): String? {
        output.hdKey?.let { return xpubWithOrigin(it) }
        output.multiKey?.hdKeys?.firstOrNull()?.let { return xpubWithOrigin(it) }
        return null
    }

    private fun decodeUrOutputDescriptor(output: UROutputDescriptor): String? {
        val source = output.source?.takeIf { it.isNotBlank() }
        val keyTexts = output.keys.mapNotNull { registryKeyText(it) }
        if (source != null) {
            val expanded = expandDescriptorPlaceholders(source, keyTexts)
            if (expanded.isNotBlank() && !expanded.contains('@')) return expanded
            if (!source.contains('@')) return source
        }
        return keyTexts.firstOrNull()
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
            expanded = expanded.replace(Regex("@$index(/\\*\\*|/[01]/\\*|/\\*)?")) { match ->
                val suffix = match.groupValues.getOrNull(1).orEmpty()
                when (suffix) {
                    "/**", "/*", "" -> "$key/0/*"
                    else -> "$key$suffix"
                }
            }
        }
        return expanded
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
