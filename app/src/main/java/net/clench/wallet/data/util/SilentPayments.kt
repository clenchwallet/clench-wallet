package net.clench.wallet.data.util

import fr.acinq.secp256k1.Secp256k1
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * BIP-352 Silent Payments — sending support.
 *
 * Silent Payments allow a receiver to publish a single static address (sp1...)
 * that senders can pay to, where each payment creates a unique on-chain address.
 * No notification transaction needed (unlike BIP-47).
 *
 * This module implements SENDING only. Receiving (scanning) is significantly more
 * complex and requires scanning all transactions — left for future BDK native support.
 *
 * BDK 2.3.1 does NOT have native Silent Payment support.
 * When BDK adds SP support, this module can be replaced with BDK's native API.
 *
 * Reference: https://github.com/bitcoin/bips/blob/master/bip-0352.mediawiki
 */
object SilentPayments {

    /**
     * Parsed Silent Payment address containing the scan and spend public keys.
     * The address format is: sp1<bech32m encoded data>
     * Data = scan_pubkey (33 bytes) || spend_pubkey (33 bytes)
     */
    data class SilentPaymentAddress(
        val scanPubKey: ByteArray,   // 33 bytes compressed public key
        val spendPubKey: ByteArray,  // 33 bytes compressed public key
        val hrp: String              // "sp" for mainnet, "tsp" for testnet/signet
    ) {
        val isMainnet: Boolean get() = hrp == "sp"
        val isTestnet: Boolean get() = hrp == "tsp"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SilentPaymentAddress) return false
            return scanPubKey.contentEquals(other.scanPubKey) &&
                    spendPubKey.contentEquals(other.spendPubKey) &&
                    hrp == other.hrp
        }

        override fun hashCode(): Int {
            var result = scanPubKey.contentHashCode()
            result = 31 * result + spendPubKey.contentHashCode()
            result = 31 * result + hrp.hashCode()
            return result
        }
    }

    /**
     * Check if a string looks like a Silent Payment address.
     * Quick prefix check — does NOT fully validate the bech32m encoding.
     */
    fun isSilentPaymentAddress(address: String): Boolean {
        val trimmed = address.trim().lowercase()
        return trimmed.startsWith("sp1") || trimmed.startsWith("tsp1")
    }

    /**
     * Parse a Silent Payment address (sp1... or tsp1...).
     *
     * BIP-352 address format:
     * - HRP: "sp" (mainnet) or "tsp" (testnet/signet)
     * - Bech32m encoding
     * - Data: version byte (0x00) || scan_pubkey_x (32 bytes) || spend_pubkey_x (32 bytes)
     *
     * The public keys in the address are x-only (32 bytes each), which we convert
     * to compressed format (33 bytes with 0x02 prefix) for use with secp256k1.
     *
     * @return Parsed address or null if invalid
     */
    fun parseAddress(address: String): SilentPaymentAddress? {
        return try {
            val trimmed = address.trim()
            val lower = trimmed.lowercase()

            // Determine HRP
            val hrp = when {
                lower.startsWith("sp1") -> "sp"
                lower.startsWith("tsp1") -> "tsp"
                else -> return null
            }

            // Bech32m decode
            val decoded = Bech32m.decode(trimmed) ?: return null
            if (decoded.hrp != hrp) return null

            // Convert 5-bit groups back to 8-bit bytes
            val data = Bech32m.convertBits(decoded.data, 5, 8, false) ?: return null

            // First byte is the version (must be 0 for v0 silent payments)
            if (data.isEmpty() || data[0] != 0.toByte()) return null

            // Remaining data: scan_pubkey_x (32 bytes) || spend_pubkey_x (32 bytes)
            val keyData = data.drop(1).toByteArray()
            if (keyData.size != 64) return null

            // x-only pubkeys (32 bytes each) → compressed pubkeys (33 bytes with 0x02 prefix)
            // BIP-352 specifies keys have even parity
            val scanPubKeyX = keyData.sliceArray(0 until 32)
            val spendPubKeyX = keyData.sliceArray(32 until 64)

            val scanPubKey = ByteArray(33).also {
                it[0] = 0x02
                System.arraycopy(scanPubKeyX, 0, it, 1, 32)
            }
            val spendPubKey = ByteArray(33).also {
                it[0] = 0x02
                System.arraycopy(spendPubKeyX, 0, it, 1, 32)
            }

            SilentPaymentAddress(scanPubKey, spendPubKey, hrp)
        } catch (e: Exception) {
            android.util.Log.w("SilentPayments", "Failed to parse SP address: ${e.message}")
            null
        }
    }

    /**
     * Derive the actual P2TR output key for a Silent Payment.
     *
     * BIP-352 derivation for sending:
     * 1. Sum all input private keys: a_sum = Σ a_i
     * 2. Compute input hash: input_hash = hash("BIP0352/Inputs" || outpoint_lowest || A_sum)
     * 3. Compute shared secret: ecdh_shared_secret = input_hash * a_sum * B_scan
     * 4. Compute output key: t_k = hash("BIP0352/SharedSecret" || ser(ecdh_shared_secret) || ser32(k))
     * 5. Output public key: P_output = B_spend + t_k * G
     *
     * @param spAddress The parsed Silent Payment address
     * @param senderPrivateKeys Private keys of all inputs being spent (32 bytes each)
     * @param outpoints List of outpoints being spent, each as "txid:vout" (for input_hash)
     * @param outputIndex The output index k (usually 0 for single-recipient)
     * @return The derived x-only public key (32 bytes) for the P2TR output, or null on error
     */
    fun deriveOutputKey(
        spAddress: SilentPaymentAddress,
        senderPrivateKeys: List<ByteArray>,
        outpoints: List<String>,
        outputIndex: Int = 0
    ): ByteArray? {
        return try {
            val secp = Secp256k1

            // Step 1: Sum all input private keys (mod n)
            if (senderPrivateKeys.isEmpty()) return null
            var aSum = senderPrivateKeys[0].copyOf()
            for (i in 1 until senderPrivateKeys.size) {
                aSum = secp.privKeyTweakAdd(aSum, senderPrivateKeys[i])
            }

            // Compute A_sum (compressed public key of summed private key)
            val aSumPub = secp.pubkeyCreate(aSum)

            // Step 2: Compute input_hash
            // Find the lexicographically smallest outpoint
            val smallestOutpoint = outpoints
                .map { parseOutpointToBytes(it) }
                .filterNotNull()
                .minWithOrNull(compareBy<ByteArray> { it.size }.thenBy { it.toHex() })
                ?: return null

            val inputHash = taggedHash(
                "BIP0352/Inputs",
                smallestOutpoint + aSumPub
            )

            // Step 3: Compute the tweaked private key: input_hash * a_sum
            val tweakedPrivKey = secp.privKeyTweakMul(aSum, inputHash)

            // Step 4: ECDH shared secret = tweakedPrivKey * B_scan
            // Use pubKeyTweakMul equivalent: multiply scan pubkey by tweaked privkey
            val sharedSecret = secp.ecdh(tweakedPrivKey, spAddress.scanPubKey)

            // Step 5: Compute t_k = hash("BIP0352/SharedSecret" || shared_secret || ser32(k))
            val ser32k = ByteArray(4)
            ser32k[0] = ((outputIndex shr 24) and 0xFF).toByte()
            ser32k[1] = ((outputIndex shr 16) and 0xFF).toByte()
            ser32k[2] = ((outputIndex shr 8) and 0xFF).toByte()
            ser32k[3] = (outputIndex and 0xFF).toByte()

            val tk = taggedHash(
                "BIP0352/SharedSecret",
                sharedSecret + ser32k
            )

            // Step 6: Output public key = B_spend + t_k * G
            // t_k * G
            val tkPub = secp.pubkeyCreate(tk)
            // B_spend + t_k * G
            val outputPubKey = secp.pubKeyCombine(arrayOf(spAddress.spendPubKey, tkPub))

            // Return x-only (drop the prefix byte)
            if (outputPubKey.size == 33) {
                outputPubKey.sliceArray(1 until 33)
            } else if (outputPubKey.size == 65) {
                outputPubKey.sliceArray(1 until 33)
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("SilentPayments", "Failed to derive output key: ${e.message}", e)
            null
        }
    }

    /**
     * Derive the P2TR address for a Silent Payment output.
     *
     * @return A standard P2TR (bc1p... or tb1p...) address, or null on error
     */
    fun deriveOutputAddress(
        spAddress: SilentPaymentAddress,
        senderPrivateKeys: List<ByteArray>,
        outpoints: List<String>,
        outputIndex: Int = 0
    ): String? {
        val outputKey = deriveOutputKey(spAddress, senderPrivateKeys, outpoints, outputIndex)
            ?: return null

        // Encode as P2TR address (bech32m with witness version 1)
        val hrp = if (spAddress.isMainnet) "bc" else "tb"
        return Bech32m.encodeSegwitAddress(hrp, 1, outputKey)
    }

    // ---- Internal helpers ----

    /**
     * BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || msg)
     */
    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        val preimage = tagHash + tagHash + msg
        return sha256(preimage)
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    /**
     * Parse "txid:vout" to the serialized outpoint format (txid_le || vout_le).
     * The txid is reversed to little-endian as used in Bitcoin serialization.
     */
    private fun parseOutpointToBytes(outpoint: String): ByteArray? {
        return try {
            val parts = outpoint.split(":")
            if (parts.size != 2) return null
            val txidHex = parts[0]
            val vout = parts[1].toIntOrNull() ?: return null

            // txid is displayed big-endian, reverse to little-endian
            val txidBytes = txidHex.hexToByteArray().reversedArray()
            val voutBytes = ByteArray(4)
            voutBytes[0] = (vout and 0xFF).toByte()
            voutBytes[1] = ((vout shr 8) and 0xFF).toByte()
            voutBytes[2] = ((vout shr 16) and 0xFF).toByte()
            voutBytes[3] = ((vout shr 24) and 0xFF).toByte()

            txidBytes + voutBytes
        } catch (e: Exception) {
            null
        }
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(length / 2) { i ->
            val idx = i * 2
            ((Character.digit(this[idx], 16) shl 4) + Character.digit(this[idx + 1], 16)).toByte()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}

/**
 * Bech32m encoder/decoder for BIP-352 Silent Payment addresses and P2TR addresses.
 *
 * Implements Bech32m (BIP-350) encoding which is used for:
 * - Silent Payment addresses (sp1.../tsp1...)
 * - P2TR (Taproot) addresses (bc1p.../tb1p...)
 */
object Bech32m {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CONST = 0x2bc830a3L

    data class Bech32Data(val hrp: String, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bech32Data) return false
            return hrp == other.hrp && data.contentEquals(other.data)
        }
        override fun hashCode() = 31 * hrp.hashCode() + data.contentHashCode()
    }

    /**
     * Decode a Bech32m string into HRP and 5-bit data groups.
     */
    fun decode(bech: String): Bech32Data? {
        val lower = bech.lowercase()
        val pos = lower.lastIndexOf('1')
        if (pos < 1 || pos + 7 > lower.length) return null

        val hrp = lower.substring(0, pos)
        val dataStr = lower.substring(pos + 1)

        val data = IntArray(dataStr.length)
        for (i in dataStr.indices) {
            val idx = CHARSET.indexOf(dataStr[i])
            if (idx < 0) return null
            data[i] = idx
        }

        if (!verifyChecksum(hrp, data)) return null

        // Strip 6-byte checksum
        val payload = ByteArray(data.size - 6) { data[it].toByte() }
        return Bech32Data(hrp, payload)
    }

    /**
     * Encode HRP + 5-bit data groups to a Bech32m string.
     */
    fun encode(hrp: String, data: ByteArray): String {
        val values = IntArray(data.size) { data[it].toInt() and 0x1F }
        val checksum = createChecksum(hrp, values)
        val combined = values + checksum
        val sb = StringBuilder(hrp.length + 1 + combined.size)
        sb.append(hrp)
        sb.append('1')
        for (v in combined) sb.append(CHARSET[v])
        return sb.toString()
    }

    /**
     * Encode a segwit address (witness version + program).
     */
    fun encodeSegwitAddress(hrp: String, witnessVersion: Int, program: ByteArray): String? {
        val prog5bit = convertBits(program.toList().map { it.toInt() and 0xFF }, 8, 5, true)
            ?: return null
        val data = ByteArray(1 + prog5bit.size)
        data[0] = witnessVersion.toByte()
        for (i in prog5bit.indices) data[i + 1] = prog5bit[i].toByte()
        return encode(hrp, data)
    }

    /**
     * Convert between bit groups (e.g., 8-bit to 5-bit or 5-bit to 8-bit).
     */
    fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        return convertBits(data.map { it.toInt() and 0xFF }, fromBits, toBits, pad)?.let {
            ByteArray(it.size) { i -> it[i].toByte() }
        }
    }

    private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1

        for (value in data) {
            if (value < 0 || value shr fromBits != 0) return null
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add((acc shr bits) and maxv)
            }
        }

        if (pad) {
            if (bits > 0) ret.add((acc shl (toBits - bits)) and maxv)
        } else {
            if (bits >= fromBits) return null
            if ((acc shl (toBits - bits)) and maxv != 0) return null
        }

        return ret
    }

    private fun polymod(values: IntArray): Long {
        val gen = longArrayOf(
            0x3b6a57b2L, 0x26508e6dL, 0x1ea119faL, 0x3d4233ddL, 0x2a1462b3L
        )
        var chk = 1L
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1FFFFFFL) shl 5) xor v.toLong()
            for (i in 0..4) {
                if ((b shr i) and 1L != 0L) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) result[i] = hrp[i].code shr 5
        result[hrp.length] = 0
        for (i in hrp.indices) result[hrp.length + 1 + i] = hrp[i].code and 31
        return result
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        val values = hrpExpand(hrp) + data
        return polymod(values) == BECH32M_CONST
    }

    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val pmod = polymod(values) xor BECH32M_CONST
        return IntArray(6) { ((pmod shr (5 * (5 - it))) and 31).toInt() }
    }
}
