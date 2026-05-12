package net.clench.wallet.domain.model

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class ParsedSignerAccountKey(
    val fingerprint: String?,
    val derivationPath: String?,
    val xpub: String,
    val keyWithOrigin: String,
    val network: String?
)

object SignerAccountKeyParser {
    const val SCRIPT_SINGLE_SIG_NATIVE_SEGWIT = "SINGLE_SIG_NATIVE_SEGWIT"
    const val SCRIPT_MULTISIG_NATIVE_SEGWIT = "MULTISIG_NATIVE_SEGWIT"

    private val validPublicPrefixes = listOf(
        "xpub", "ypub", "zpub", "tpub",
        "Zpub", "Ypub", "Vpub", "Upub", "vpub", "upub"
    )
    private val privatePrefixes = listOf("xprv", "yprv", "zprv", "tprv")

    fun expectedMultisigPath(isTestnet: Boolean): String {
        return if (isTestnet) "m/48'/1'/0'/2'" else "m/48'/0'/0'/2'"
    }

    fun expectedSingleSigPath(isTestnet: Boolean): String {
        return if (isTestnet) "m/84'/1'/0'" else "m/84'/0'/0'"
    }

    fun expectedPath(scriptType: String, isTestnet: Boolean): String {
        return when (scriptType) {
            SCRIPT_SINGLE_SIG_NATIVE_SEGWIT -> expectedSingleSigPath(isTestnet)
            else -> expectedMultisigPath(isTestnet)
        }
    }

    fun displayNameForScript(scriptType: String): String {
        return when (scriptType) {
            SCRIPT_SINGLE_SIG_NATIVE_SEGWIT -> "Single-sig native SegWit"
            SCRIPT_MULTISIG_NATIVE_SEGWIT -> "Multisig native SegWit"
            else -> scriptType
        }
    }

    fun normalizeHardwareExportForMultisig(text: String): String {
        return normalizeHardwareExport(text, SCRIPT_MULTISIG_NATIVE_SEGWIT)
    }

    fun normalizeHardwareExport(text: String, scriptType: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return trimmed

        return runCatching {
            val root = JSONObject(trimmed)
            val candidates = jsonCandidatesForScript(scriptType)
            for (key in candidates) {
                val obj = root.optJSONObject(key) ?: continue
                val normalized = xpubWithOriginFromJsonObject(obj, root)
                if (normalized != null) return@runCatching normalized
            }
            xpubWithOriginFromJsonObject(root, root) ?: trimmed
        }.getOrNull()
            ?: xpubWithOriginFromJsonText(trimmed, scriptType)
            ?: trimmed
    }

    fun parse(
        raw: String,
        fallbackFingerprint: String? = null,
        fallbackDerivationPath: String? = null,
        scriptType: String = SCRIPT_MULTISIG_NATIVE_SEGWIT
    ): ParsedSignerAccountKey? {
        val normalized = normalizeHardwareExport(raw, scriptType).trim()
        if (normalized.isBlank()) return null

        val originMatch = Regex("""^\[([0-9a-fA-F]{8})(?:/([^\]]+))?\](.+)$""").find(normalized)
        val originFingerprint = originMatch?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
        val originPath = originMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        val keyPartWithChildren = originMatch?.groupValues?.getOrNull(3) ?: normalized
        val xpub = stripChildSuffix(keyPartWithChildren).trim()
        if (xpub.isBlank()) return null

        val fingerprint = originFingerprint ?: normalizeFingerprint(fallbackFingerprint)
        val derivationPath = normalizeDerivationPath(originPath ?: fallbackDerivationPath)
        val keyWithOrigin = if (!fingerprint.isNullOrBlank() && !derivationPath.isNullOrBlank()) {
            "[$fingerprint/${derivationPath.removePrefix("m/")}]$xpub"
        } else {
            xpub
        }

        return ParsedSignerAccountKey(
            fingerprint = fingerprint,
            derivationPath = derivationPath,
            xpub = xpub,
            keyWithOrigin = keyWithOrigin,
            network = networkForKey(xpub)
        )
    }

    fun validationError(
        key: String,
        isTestnet: Boolean,
        scriptType: String = SCRIPT_MULTISIG_NATIVE_SEGWIT,
        requireOrigin: Boolean = true
    ): String? {
        val normalized = normalizeHardwareExport(key, scriptType).trim()
        if (normalized.startsWith("wsh(") || normalized.startsWith("wpkh(") || normalized.startsWith("sh(")) {
            return "paste the signer public key, not a full descriptor"
        }

        val parsed = parse(normalized, scriptType = scriptType)
            ?: return "extended public key is required"
        val keyPart = parsed.xpub

        if (privatePrefixes.any { keyPart.startsWith(it) }) {
            return "private extended keys are not allowed"
        }
        if (validPublicPrefixes.none { keyPart.startsWith(it) }) {
            return "unrecognized key format. Expected xpub, Zpub, tpub, or similar public extended key"
        }
        validatePrefixForScript(keyPart, scriptType)?.let { return it }
        if (requireOrigin && (parsed.fingerprint.isNullOrBlank() || parsed.derivationPath.isNullOrBlank())) {
            return "missing key origin. Add the master fingerprint and derivation path before using this signer"
        }

        return validateNetwork(parsed.xpub, isTestnet)
            ?: validateOriginPath(parsed.derivationPath, isTestnet, scriptType)
    }

    fun validateNetwork(key: String, isTestnet: Boolean): String? {
        val network = networkForKey(stripChildSuffix(key))
        return when {
            isTestnet && network == "mainnet" -> "mainnet public key used while Clench is set to testnet"
            !isTestnet && network == "testnet" -> "testnet public key used while Clench is set to mainnet"
            else -> null
        }
    }

    fun stableId(fingerprint: String?, derivationPath: String?, xpub: String): String {
        val input = listOf(
            fingerprint.orEmpty().uppercase(Locale.US),
            derivationPath.orEmpty().lowercase(Locale.US),
            stripChildSuffix(xpub.trim())
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    fun normalizeFingerprint(value: String?): String? {
        val cleaned = value
            ?.trim()
            ?.removePrefix("0x")
            ?.uppercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return cleaned.takeIf { Regex("^[0-9A-F]{8}$").matches(it) }
    }

    fun normalizeDerivationPath(value: String?): String? {
        val cleaned = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (cleaned == "m" || cleaned.startsWith("m/")) cleaned else "m/$cleaned"
    }

    private fun xpubWithOriginFromJsonObject(obj: JSONObject, root: JSONObject): String? {
        val xpub = obj.optString("xpub")
            .ifBlank { obj.optString("Zpub") }
            .ifBlank { obj.optString("Ypub") }
            .ifBlank { obj.optString("zpub") }
            .ifBlank { obj.optString("ypub") }
            .ifBlank { obj.optString("pub") }
            .ifBlank { obj.optString("key") }
            .takeIf { it.isNotBlank() }
            ?: return null
        val xfp = obj.optString("xfp")
            .ifBlank { obj.optString("fingerprint") }
            .ifBlank { root.optString("xfp") }
            .ifBlank { root.optString("fingerprint") }
        val deriv = obj.optString("deriv")
            .ifBlank { obj.optString("derivation") }
            .ifBlank { obj.optString("path") }
        return if (xfp.isNotBlank() && deriv.isNotBlank()) {
            "[${xfp.removePrefix("0x").uppercase(Locale.US)}/${deriv.removePrefix("m/")}]$xpub"
        } else xpub
    }

    private fun xpubWithOriginFromJsonText(text: String, scriptType: String): String? {
        val candidates = jsonCandidatesForScript(scriptType)
        for (key in candidates) {
            val objText = jsonObjectForKey(text, key) ?: continue
            xpubWithOriginFromJsonFields(objText, text)?.let { return it }
        }
        return xpubWithOriginFromJsonFields(text, text)
    }

    private fun xpubWithOriginFromJsonFields(objText: String, rootText: String): String? {
        val xpub = jsonStringValue(objText, "xpub", "Zpub", "Ypub", "zpub", "ypub", "pub", "key")
            ?: return null
        val xfp = jsonStringValue(objText, "xfp", "fingerprint")
            ?: jsonStringValue(rootText, "xfp", "fingerprint")
        val deriv = jsonStringValue(objText, "deriv", "derivation", "path")
        return if (!xfp.isNullOrBlank() && !deriv.isNullOrBlank()) {
            "[${xfp.removePrefix("0x").uppercase(Locale.US)}/${deriv.removePrefix("m/")}]$xpub"
        } else xpub
    }

    private fun jsonStringValue(text: String, vararg keys: String): String? {
        for (key in keys) {
            val match = Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]+)"""").find(text)
            val value = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private fun jsonObjectForKey(text: String, key: String): String? {
        val marker = Regex(""""${Regex.escape(key)}"\s*:\s*\{""").find(text) ?: return null
        var index = marker.range.last
        var depth = 0
        var inString = false
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth += 1
                !inString && char == '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(marker.range.last, index + 1)
                }
            }
            index += 1
        }
        return null
    }

    private fun jsonCandidatesForScript(scriptType: String): List<String> {
        val singleSig = listOf("p2wpkh", "bip84", "native_segwit")
        val multisig = listOf("p2wsh", "bip48", "bip48_2", "p2sh_p2wsh", "p2sh-p2wsh")
        return when (scriptType) {
            SCRIPT_SINGLE_SIG_NATIVE_SEGWIT -> singleSig + multisig
            else -> multisig + singleSig
        }
    }

    private fun validatePrefixForScript(key: String, scriptType: String): String? {
        val prefix = stripChildSuffix(key).take(4)
        return when {
            scriptType == SCRIPT_SINGLE_SIG_NATIVE_SEGWIT &&
                prefix in listOf("Zpub", "Ypub", "Vpub", "Upub") ->
                "multisig extended key prefix $prefix belongs in the multisig signer type"
            scriptType == SCRIPT_MULTISIG_NATIVE_SEGWIT &&
                prefix in listOf("zpub", "ypub", "vpub", "upub") ->
                "single-sig extended key prefix $prefix belongs in the single-sig signer type"
            else -> null
        }
    }

    private fun validateOriginPath(path: String?, isTestnet: Boolean, scriptType: String): String? {
        val normalized = normalizeDerivationPath(path) ?: return null
        val parts = normalized.removePrefix("m/").split('/')
        val expectedPurpose = if (scriptType == SCRIPT_SINGLE_SIG_NATIVE_SEGWIT) "84" else "48"
        if (parts.isNotEmpty() && parts[0].removeHardenedSuffix() != expectedPurpose) {
            return when (scriptType) {
                SCRIPT_SINGLE_SIG_NATIVE_SEGWIT -> "single-sig signers should use BIP84 path ${expectedSingleSigPath(isTestnet)}"
                else -> "multisig signers should use BIP48 path ${expectedMultisigPath(isTestnet)}"
            }
        }
        if (parts.size >= 2) {
            val coinType = parts[1].removeHardenedSuffix()
            val expected = if (isTestnet) "1" else "0"
            if (coinType != expected) {
                return "origin path coin type $coinType does not match ${if (isTestnet) "testnet" else "mainnet"}"
            }
        }
        if (scriptType == SCRIPT_MULTISIG_NATIVE_SEGWIT && parts.size >= 4 && parts[3].removeHardenedSuffix() != "2") {
            return "native SegWit multisig signers should use script path 2 at ${expectedMultisigPath(isTestnet)}"
        }
        return null
    }

    private fun networkForKey(key: String): String? {
        val stripped = stripChildSuffix(key)
        val isMainnetKey = listOf("xpub", "ypub", "zpub", "Ypub", "Zpub").any { stripped.startsWith(it) }
        val isTestnetKey = listOf("tpub", "upub", "vpub", "Upub", "Vpub").any { stripped.startsWith(it) }
        return when {
            isMainnetKey -> "mainnet"
            isTestnetKey -> "testnet"
            else -> null
        }
    }

    private fun stripChildSuffix(key: String): String {
        return key
            .removeSuffix("/0/*")
            .removeSuffix("/1/*")
            .removeSuffix("/**")
    }

    private fun String.removeHardenedSuffix(): String {
        return removeSuffix("'").removeSuffix("h").removeSuffix("H")
    }
}
