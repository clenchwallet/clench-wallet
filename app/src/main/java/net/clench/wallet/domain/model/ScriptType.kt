package net.clench.wallet.domain.model

import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Network

/**
 * Supported single-sig script types for wallet creation.
 */
enum class ScriptType(
    val displayName: String,
    val shortDescription: String,
    val bipNumber: Int,
    val descriptorPrefix: String
) {
    NATIVE_SEGWIT(
        displayName = "Native SegWit (bech32)",
        shortDescription = "Lowest fees",
        bipNumber = 84,
        descriptorPrefix = "wpkh"
    ),
    NESTED_SEGWIT(
        displayName = "Nested SegWit",
        shortDescription = "Wide compatibility",
        bipNumber = 49,
        descriptorPrefix = "sh(wpkh)"
    ),
    LEGACY(
        displayName = "Legacy",
        shortDescription = "Oldest format",
        bipNumber = 44,
        descriptorPrefix = "pkh"
    ),
    TAPROOT(
        displayName = "Taproot",
        shortDescription = "Newest, most private",
        bipNumber = 86,
        descriptorPrefix = "tr"
    );

    companion object {
        /**
         * Create a BDK Descriptor for the given script type.
         * Uses BDK 3.0.0 factory methods: newBip84, newBip49, newBip44, newBip86.
         */
        fun createDescriptor(
            secretKey: DescriptorSecretKey,
            scriptType: ScriptType,
            keychain: KeychainKind,
            network: Network
        ): Descriptor {
            return when (scriptType) {
                NATIVE_SEGWIT -> Descriptor.newBip84(secretKey, keychain, network.toNetworkKind())
                NESTED_SEGWIT -> Descriptor.newBip49(secretKey, keychain, network.toNetworkKind())
                LEGACY -> Descriptor.newBip44(secretKey, keychain, network.toNetworkKind())
                TAPROOT -> Descriptor.newBip86(secretKey, keychain, network.toNetworkKind())
            }
        }

        /**
         * Parse script type from a descriptor string.
         * Extracts the prefix (wpkh, sh(wpkh), pkh, tr) to determine the script type.
         */
        fun fromDescriptor(descriptor: String): ScriptType {
            val lower = descriptor.lowercase()
            return when {
                lower.contains("tr(") -> TAPROOT
                lower.contains("sh(wpkh") -> NESTED_SEGWIT
                lower.contains("wpkh(") -> NATIVE_SEGWIT
                lower.contains("pkh(") -> LEGACY
                else -> NATIVE_SEGWIT // default
            }
        }
    }
}
