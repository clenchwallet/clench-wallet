package net.clench.wallet.domain.model

import java.time.Instant

/**
 * Represents a stored wallet (descriptor-based, BDK style).
 */
data class WalletData(
    val id: String,
    val name: String,
    val descriptor: String,          // external (receive) descriptor
    val changeDescriptor: String,    // internal (change) descriptor
    val isWatchOnly: Boolean = false,
    val isMultisig: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val network: String = "mainnet",  // "mainnet" or "testnet"
    val preferredHardwareWallet: String? = null,
    val hasPassphrase: Boolean = false,
    val identiconBytes: ByteArray? = null
)

/**
 * Address info for the Addresses list screen.
 */
data class AddressInfo(val index: Int, val address: String, val isUsed: Boolean)

/**
 * Direction of a transaction relative to this wallet.
 */
enum class TxDirection { RECEIVED, SENT }

/**
 * A single transaction in the wallet's history.
 */
data class TransactionItem(
    val txid: String,
    val amountSat: Long,             // positive = received, negative = sent
    val feeSat: Long?,
    val timestamp: Instant?,
    val confirmations: Int,
    val direction: TxDirection,
    val address: String?
)

/**
 * A derived Bitcoin address.
 */
data class Address(
    val address: String,
    val index: Int,
    val used: Boolean = false
)

/**
 * A UTXO (unspent transaction output) for coin control.
 */
data class UtxoInfo(
    val txid: String,
    val vout: UInt,
    val amountSat: Long,
    val address: String?,
    val confirmations: Int,
    val isSpent: Boolean,
    val keychain: String,  // "EXTERNAL" or "INTERNAL"
    val isFrozen: Boolean = false
)

/**
 * Electrum server configuration.
 */
data class ElectrumConfig(
    val serverUrl: String = "electrum.blockstream.info",
    val port: Int = 50002,
    val useSsl: Boolean = true,
    val isCustom: Boolean = false,
    /** Base64-encoded DER certificate for TLS cert pinning (null = use system trust store) */
    val pinnedCert: String? = null,
    /** Whether to route this connection through Tor SOCKS5 proxy */
    val useTor: Boolean = false
)

/**
 * Fee estimates for different confirmation targets.
 */
data class FeeEstimates(
    val priority: Float,    // sat/vB, ~1 block (~10 min)
    val standard: Float,    // sat/vB, ~3 blocks (~30 min)
    val economy: Float,     // sat/vB, ~6 blocks (~60 min)
    val timestamp: Long     // when fetched (epoch ms)
)

/**
 * Wallet balance in satoshis.
 */
data class WalletBalance(
    val confirmedSat: Long,
    val trustedPendingSat: Long,
    val untrustedPendingSat: Long,
    val immatureSat: Long
) {
    // For watch-only wallets all unconfirmed UTXOs are classified as "untrusted pending"
    // because there are no private keys to verify trust. Including untrustedPendingSat
    // ensures watch-only wallets show a non-zero balance for unconfirmed transactions.
    val totalSat: Long get() = confirmedSat + trustedPendingSat + untrustedPendingSat

    // Strictly spendable (confirmed + trusted unconfirmed only — for send amount validation)
    val spendableSat: Long get() = confirmedSat + trustedPendingSat
}
