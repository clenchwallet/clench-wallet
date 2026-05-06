package net.clench.wallet.domain.model

import org.bitcoindevkit.Address
import org.bitcoindevkit.Network

data class AddressVerificationResult(
    val normalizedAddress: String,
    val networkLabel: String,
    val scriptLabel: String,
    val warning: String? = null
) {
    val displayText: String
        get() = "$networkLabel $scriptLabel address verified"
}

data class ParsedBitcoinUri(
    val address: String,
    val amountBtc: Double? = null,
    val label: String? = null,
    val warning: String? = null
)

object BitcoinAddressVerifier {

    fun parseBip21(input: String): ParsedBitcoinUri {
        val trimmed = input.trim()
        if (!trimmed.startsWith("bitcoin:", ignoreCase = true)) {
            return ParsedBitcoinUri(address = normalizeBech32Case(trimmed))
        }

        val withoutScheme = trimmed.substringAfter(":")
        val address = withoutScheme.substringBefore("?")
        val queryString = withoutScheme.substringAfter("?", "")
        var amount: Double? = null
        var label: String? = null
        val warnings = mutableListOf<String>()

        if (queryString.isNotBlank()) {
            queryString.split("&")
                .filter { it.isNotBlank() }
                .forEach { param ->
                    val key = param.substringBefore("=").lowercase()
                    val value = param.substringAfter("=", "")
                    when (key) {
                        "amount" -> {
                            amount = value.toDoubleOrNull()
                            if (amount == null || amount!! < 0.0) {
                                error("BIP-21 amount is invalid")
                            }
                        }
                        "label" -> label = decodeUriComponent(value).take(80)
                        "message", "time", "exp" -> Unit
                        "pj", "pjos" -> warnings += "Payjoin parameters are ignored by Clench."
                        else -> {
                            if (key.startsWith("req-")) {
                                error("Unsupported required BIP-21 parameter: $key")
                            }
                        }
                    }
                }
        }

        return ParsedBitcoinUri(
            address = normalizeBech32Case(address),
            amountBtc = amount,
            label = label,
            warning = warnings.firstOrNull()
        )
    }

    fun verify(address: String, isTestnet: Boolean): AddressVerificationResult {
        val trimmed = normalizeBech32Case(address.trim())
        if (trimmed.isBlank()) error("Please enter a recipient address")

        val expected = if (isTestnet) Network.TESTNET else Network.BITCOIN
        val parsed = try {
            Address(trimmed, expected)
        } catch (e: Exception) {
            val otherNetwork = if (isTestnet) Network.BITCOIN else Network.TESTNET
            val parsesForOtherNetwork = runCatching { Address(trimmed, otherNetwork) }.isSuccess
            if (parsesForOtherNetwork) {
                val expectedLabel = if (isTestnet) "testnet" else "mainnet"
                val otherLabel = if (isTestnet) "mainnet" else "testnet"
                error("This is a $otherLabel address, but this wallet is on $expectedLabel.")
            }
            error("Invalid Bitcoin address: ${e.message ?: "checksum or format failed"}")
        }

        if (!parsed.isValidForNetwork(expected)) {
            val expectedLabel = if (isTestnet) "testnet" else "mainnet"
            error("This address is not valid for $expectedLabel.")
        }

        return AddressVerificationResult(
            normalizedAddress = parsed.toString(),
            networkLabel = if (isTestnet) "Testnet" else "Mainnet",
            scriptLabel = scriptLabelFor(parsed.toString())
        )
    }

    private fun normalizeBech32Case(address: String): String {
        return if (address.startsWith("bc1", ignoreCase = true) ||
            address.startsWith("tb1", ignoreCase = true) ||
            address.startsWith("bcrt1", ignoreCase = true)) {
            address.lowercase()
        } else address
    }

    private fun scriptLabelFor(address: String): String {
        return when {
            address.startsWith("bc1p") || address.startsWith("tb1p") || address.startsWith("bcrt1p") -> "Taproot"
            address.startsWith("bc1q") || address.startsWith("tb1q") || address.startsWith("bcrt1q") -> "Native SegWit"
            address.startsWith("3") || address.startsWith("2") -> "P2SH"
            address.startsWith("1") || address.startsWith("m") || address.startsWith("n") -> "Legacy"
            else -> "Bitcoin"
        }
    }

    private fun decodeUriComponent(value: String): String {
        return java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}
