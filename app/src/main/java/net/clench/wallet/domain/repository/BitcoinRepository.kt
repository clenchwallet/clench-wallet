package net.clench.wallet.domain.repository

import net.clench.wallet.domain.model.*
import org.bitcoindevkit.KeychainKind

/**
 * Core Bitcoin wallet operations.
 * Implemented by BdkBitcoinRepository (BDK-backed).
 */
interface BitcoinRepository {

    /**
     * Generate a new BIP39 mnemonic + create a wallet descriptor from it.
     * @param wordCount 12 or 24
     * @param passphrase optional BIP39 passphrase
     * @return the mnemonic words and resulting WalletData
     */
    suspend fun createWallet(
        name: String,
        wordCount: Int = 24,
        passphrase: String? = null
    ): Pair<List<String>, WalletData>

    /**
     * Import an existing wallet from a BIP39 mnemonic.
     */
    suspend fun importWallet(
        name: String,
        mnemonic: List<String>,
        passphrase: String? = null
    ): WalletData

    /**
     * Import a watch-only wallet from an xpub/descriptor.
     */
    suspend fun importWatchOnly(
        name: String,
        descriptor: String
    ): WalletData

    /**
     * Sync wallet with Electrum server and return updated balance.
     */
    suspend fun syncWallet(walletId: String, config: ElectrumConfig? = null): WalletBalance

    /**
     * Get the current balance without syncing.
     */
    suspend fun getBalance(walletId: String): WalletBalance

    /**
     * Get transaction history for a wallet.
     */
    suspend fun getTransactions(walletId: String): List<TransactionItem>

    /**
     * Get the last revealed receive address without advancing the index.
     */
    suspend fun getLastAddress(walletId: String): Address

    /**
     * Get the next unused receive address.
     */
    suspend fun getReceiveAddress(walletId: String): Address

    /**
     * Build and sign a transaction.
     * @param toAddress recipient Bitcoin address
     * @param amountSat amount in satoshis (null = send max)
     * @return signed transaction hex ready to broadcast
     */
    suspend fun buildTransaction(
        walletId: String,
        toAddress: String,
        amountSat: Long?,
        feeRateSatPerVbyte: Float = 2.0f
    ): String

    /**
     * Broadcast a signed transaction.
     * @param txHex signed transaction hex
     * @return txid on success
     */
    suspend fun broadcastTransaction(
        config: ElectrumConfig,
        txHex: String
    ): String

    /**
     * List all stored wallets.
     */
    suspend fun listWallets(): List<WalletData>

    /**
     * Delete a wallet by id.
     */
    suspend fun deleteWallet(walletId: String)

    /**
     * Get a list of derived external addresses (for the Addresses screen).
     * @param count how many addresses to derive (default 20)
     * @return list of Address objects with index, address string, and used status
     */
    suspend fun getAddresses(walletId: String, count: Int = 20): List<Address>

    /**
     * Get derived addresses for a specific keychain (external/internal).
     */
    suspend fun getAddresses(walletId: String, keychain: KeychainKind, count: Int = 20): List<Address>

    /**
     * Rename a wallet.
     */
    suspend fun renameWallet(walletId: String, newName: String)

    /**
     * Get the account-level extended public key in the display format (zpub/vpub).
     */
    suspend fun getAccountXpub(walletId: String): String

    /**
     * Get the derivation path for a wallet.
     */
    suspend fun getDerivationPath(walletId: String): String

    /**
     * Get wallet entity data.
     */
    suspend fun getWalletEntity(walletId: String): WalletData?

    /**
     * Set the preferred hardware wallet for a wallet.
     */
    suspend fun setPreferredHardwareWallet(walletId: String, device: String?)

    /**
     * Create an unsigned PSBT for hardware wallet signing.
     * @param walletId wallet to build from
     * @param toAddress recipient Bitcoin address
     * @param amountSat amount in satoshis (null = send max / drain)
     * @param feeRateSatPerVbyte fee rate
     * @param utxoTxid optional: spend only this specific UTXO
     * @param utxoVout optional: vout index of the UTXO
     * @return base64-encoded PSBT string
     */
    suspend fun createPsbt(
        walletId: String,
        toAddress: String,
        amountSat: Long?,
        feeRateSatPerVbyte: Float,
        utxoTxid: String? = null,
        utxoVout: UInt? = null
    ): String

    /**
     * Estimate fee rates for different confirmation targets.
     * Returns FeeEstimates with priority/standard/economy tiers.
     * Falls back to reasonable defaults if estimation fails.
     */
    suspend fun estimateFees(): FeeEstimates

    /**
     * Apply a signed PSBT and broadcast the resulting transaction.
     * Validates that signed PSBT outputs match the original unsigned PSBT before broadcasting.
     * @param walletId wallet that created the original PSBT
     * @param signedPsbtBase64 base64-encoded signed PSBT from hardware wallet
     * @param unsignedPsbtBase64 base64-encoded original unsigned PSBT for output validation
     * @return txid of the broadcast transaction
     */
    suspend fun applyAndBroadcastPsbt(walletId: String, signedPsbtBase64: String, unsignedPsbtBase64: String): String
}
