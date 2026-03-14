package net.clench.wallet.domain.repository

import net.clench.wallet.domain.model.*

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
    suspend fun syncWallet(walletId: String, config: ElectrumConfig): WalletBalance

    /**
     * Get the current balance without syncing.
     */
    suspend fun getBalance(walletId: String): WalletBalance

    /**
     * Get transaction history for a wallet.
     */
    suspend fun getTransactions(walletId: String): List<TransactionItem>

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
}
