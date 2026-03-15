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
    val createdAt: Instant = Instant.now()
)

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
 * Electrum server configuration.
 */
data class ElectrumConfig(
    val serverUrl: String = "electrum.blockstream.info",
    val port: Int = 50002,
    val useSsl: Boolean = true,
    val isCustom: Boolean = false
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
    val totalSat: Long get() = confirmedSat + trustedPendingSat
}
