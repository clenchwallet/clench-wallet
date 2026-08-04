package net.clench.wallet.domain.repository

import net.clench.wallet.domain.model.*
import net.clench.wallet.domain.model.ScriptType
import org.bitcoindevkit.KeychainKind

/**
 * A recipient for batch transactions.
 */
data class Recipient(val address: String, val amountSat: Long)

data class TransactionReviewOutput(
    val index: Int,
    val amountSat: Long,
    val address: String?,
    val belongsToWallet: Boolean
)

data class BuiltTransactionReview(
    val txid: String,
    val feeSat: Long,
    val vsize: Long,
    val feeRateSatPerVbyte: Double,
    val inputs: List<String>,
    val outputs: List<TransactionReviewOutput>,
    val vsizeIsEstimate: Boolean = false
) {
    val externalAmountSat: Long
        get() = saturatingAmountSum(outputs.filterNot { it.belongsToWallet })

    val totalOutputAmountSat: Long
        get() = saturatingAmountSum(outputs)

    private fun saturatingAmountSum(selected: List<TransactionReviewOutput>): Long {
        var total = 0L
        for (output in selected) {
            if (output.amountSat < 0L || total > Long.MAX_VALUE - output.amountSat) {
                return Long.MAX_VALUE
            }
            total += output.amountSat
        }
        return total
    }
}

data class GeneratedMultisigPhoneSigner(
    val mnemonicWords: List<String>,
    val xpubWithOrigin: String,
    val accountXprvWithOrigin: String,
    val fingerprint: String,
    val derivationPath: String
)

data class MultisigPhoneSignerSecret(
    val mnemonicWords: List<String>,
    val accountXprvWithOrigin: String
)

data class PsbtSigningProgress(
    val psbtBase64: String,
    val readyToBroadcast: Boolean,
    val message: String
)

data class WalletStateRecoveryResult(
    val balance: WalletBalance,
    val quarantineId: String,
    val preservedFileCount: Int,
    val stopGap: UInt
)

object WalletStateRecoveryPolicy {
    const val MIN_STOP_GAP = 20
    const val MAX_STOP_GAP = 1_000
    val recommendedStopGaps = listOf(20, 100, 250, 500, 1_000)

    fun normalizeStopGap(value: Int): Int = value.coerceIn(MIN_STOP_GAP, MAX_STOP_GAP)
    fun isValidStopGap(value: UInt): Boolean = value in MIN_STOP_GAP.toUInt()..MAX_STOP_GAP.toUInt()
}

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
        passphrase: String? = null,
        mnemonicWords: List<String>? = null,
        scriptType: ScriptType = ScriptType.NATIVE_SEGWIT
    ): Pair<List<String>, WalletData>

    /**
     * Import an existing wallet from a BIP39 mnemonic.
     */
    suspend fun importWallet(
        name: String,
        mnemonic: List<String>,
        passphrase: String? = null,
        scriptType: ScriptType = ScriptType.NATIVE_SEGWIT
    ): WalletData

    /**
     * Import a watch-only wallet from an xpub/descriptor.
     */
    suspend fun importWatchOnly(
        name: String,
        descriptor: String,
        deviceType: String? = null
    ): WalletData

    /**
     * Convert an existing watch-only wallet to a hot wallet by storing the matching seed phrase.
     * The seed must derive the wallet's current account descriptor; otherwise this throws.
     */
    suspend fun convertWatchOnlyToHot(
        walletId: String,
        mnemonic: List<String>,
        passphrase: String? = null
    )

    /**
     * Import a signing wallet from a private descriptor or private extended key.
     * Secret descriptors must be stored only in encrypted keystore; Room stores public descriptors.
     */
    suspend fun importPrivateDescriptor(
        name: String,
        descriptor: String
    ): WalletData

    /**
     * Sync wallet with Electrum server and return updated balance.
     */
    suspend fun syncWallet(walletId: String, config: ElectrumConfig? = null): WalletBalance

    /**
     * Explicit recovery for an unreadable BDK wallet-state database. Implementations must
     * preserve/quarantine the original files and restore them if the recovery scan fails.
     */
    suspend fun recoverWalletState(walletId: String, stopGap: UInt = 100u): WalletStateRecoveryResult

    /** Permanently remove a successful recovery's preserved pre-recovery state after user verification. */
    suspend fun deleteWalletStateQuarantine(walletId: String, quarantineId: String): Int

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
     * @param feeRateSatPerVbyte fee rate
     * @param utxoTxid optional: spend only this specific UTXO
     * @param utxoVout optional: vout index of the UTXO
     * @param selectedOutpoints optional: list of specific UTXO outpoints to spend
     * @return signed transaction hex ready to broadcast
     */
    suspend fun buildTransaction(
        walletId: String,
        toAddress: String,
        amountSat: Long?,
        feeRateSatPerVbyte: Float = 2.0f,
        utxoTxid: String? = null,
        utxoVout: UInt? = null,
        selectedOutpoints: List<String> = emptyList()
    ): String

    /** Parse a built transaction against wallet state for immutable user review. */
    suspend fun inspectBuiltTransaction(walletId: String, txHex: String): BuiltTransactionReview

    /** Parse an unsigned or partially signed PSBT for mandatory signer review. */
    suspend fun inspectPsbt(walletId: String, psbtBase64: String): BuiltTransactionReview

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
        utxoVout: UInt? = null,
        selectedOutpoints: List<String> = emptyList()
    ): String

    /**
     * Build and sign a batch transaction with multiple recipients.
     * @param recipients list of address/amount pairs
     * @param feeRateSatPerVbyte fee rate
     * @param selectedOutpoints optional: list of specific UTXO outpoints to spend
     * @return signed transaction hex ready to broadcast
     */
    suspend fun buildBatchTransaction(
        walletId: String,
        recipients: List<Recipient>,
        feeRateSatPerVbyte: Float,
        selectedOutpoints: List<String> = emptyList()
    ): String

    /**
     * Create an unsigned PSBT with multiple recipients for hardware wallet signing.
     * @param recipients list of address/amount pairs
     * @param feeRateSatPerVbyte fee rate
     * @param selectedOutpoints optional: list of specific UTXO outpoints to spend
     * @return base64-encoded PSBT string
     */
    suspend fun createBatchPsbt(
        walletId: String,
        recipients: List<Recipient>,
        feeRateSatPerVbyte: Float,
        selectedOutpoints: List<String> = emptyList()
    ): String

    /**
     * Estimate fee rates for different confirmation targets.
     * Returns FeeEstimates with priority/standard/economy tiers.
     * Falls back to reasonable defaults if estimation fails.
     */
    suspend fun estimateFees(): FeeEstimates

    /**
     * List unspent transaction outputs (UTXOs) for the wallet.
     */
    suspend fun listUnspent(walletId: String): List<net.clench.wallet.domain.model.UtxoInfo>

    /**
     * Create a fee-bumped (RBF) replacement transaction.
     * BDK uses BIP125 RBF signaling by default (sequence 0xFFFFFFFD).
     * @param walletId wallet that sent the original transaction
     * @param txid transaction ID to bump
     * @param newFeeRate new fee rate in sat/vB
     * @return signed transaction hex ready to broadcast
     */
    suspend fun bumpFee(walletId: String, txid: String, newFeeRate: Float): String

    /**
     * Attempt to cancel an unconfirmed RBF transaction by replacing it with a
     * higher-fee transaction that sends the original inputs back to this wallet.
     * Bitcoin transactions cannot be reversed after confirmation, and even an
     * unconfirmed replacement can race with miners accepting the original.
     * @return signed transaction hex ready to broadcast
     */
    suspend fun cancelTransaction(walletId: String, txid: String, newFeeRate: Float): String

    /**
     * Apply a signed PSBT and broadcast the resulting transaction.
     * Validates that signed PSBT outputs match the original unsigned PSBT before broadcasting.
     * @param walletId wallet that created the original PSBT
     * @param signedPsbtBase64 base64-encoded signed PSBT from hardware wallet
     * @param unsignedPsbtBase64 base64-encoded original unsigned PSBT for output validation
     * @param assertBroadcastAuthorized fail-closed authorization check invoked
     * immediately before the validated transaction is handed to the network
     * transport. Callers coordinating an external signing session must use this
     * boundary check to prevent a stale or replaced session from broadcasting.
     * @return txid of the broadcast transaction
     */
    suspend fun applyAndBroadcastPsbt(
        walletId: String,
        signedPsbtBase64: String,
        unsignedPsbtBase64: String,
        assertBroadcastAuthorized: () -> Unit
    ): String

    /**
     * Merge returned signer data into the current PSBT and report whether the
     * policy now has enough signatures to broadcast.
     */
    suspend fun mergeSignedPsbt(
        unsignedPsbtBase64: String,
        currentPsbtBase64: String,
        signedPsbtPayload: String
    ): PsbtSigningProgress

    /**
     * Unlock a passphrase wallet by deriving secret descriptors from stored mnemonic + passphrase.
     * Caches the fully-functional wallet (with signing capability).
     * @throws IllegalArgumentException if wallet not found or passphrase is incorrect
     */
    suspend fun unlockPassphraseWallet(walletId: String, passphrase: String)

    /**
     * Lock a passphrase wallet by evicting the cached secret wallet.
     */
    suspend fun lockPassphraseWallet(walletId: String)

    /**
     * Check if a passphrase wallet is currently unlocked (secret wallet cached).
     */
    fun isPassphraseWalletUnlocked(walletId: String): Boolean

    /**
     * Set a label/note for a transaction.
     * @param walletId wallet that owns the transaction
     * @param txid transaction ID
     * @param label the label text (empty string removes the label)
     */
    suspend fun setTransactionLabel(walletId: String, txid: String, label: String)

    /**
     * Get the label/note for a transaction.
     * @return the label text, or null if no label is set
     */
    suspend fun getTransactionLabel(walletId: String, txid: String): String?

    /**
     * Create a multisig wallet from cosigner xpubs.
     * Builds a wsh(sortedmulti(M, ...)) descriptor and creates the wallet.
     *
     * @param name wallet display name
     * @param threshold M-of-N threshold (minimum signatures required)
     * @param signerXpubs list of xpubs with origin info, e.g. [fingerprint/path]xpub...
     * @return the created WalletData
     */
    suspend fun createMultisigWallet(
        name: String,
        threshold: Int,
        signerXpubs: List<String>,
        localSignerSecrets: Map<Int, MultisigPhoneSignerSecret> = emptyMap()
    ): WalletData

    /**
     * Generate a BIP-48 account signer for multisig use on this phone.
     * The returned mnemonic is not persisted until the multisig wallet is created.
     */
    suspend fun generateMultisigPhoneSigner(): GeneratedMultisigPhoneSigner

    /**
     * True when this multisig wallet has encrypted phone signer secret descriptors available.
     */
    suspend fun hasMultisigPhoneSigner(walletId: String): Boolean

    /**
     * Partially sign a PSBT with encrypted multisig phone signer keys.
     * Returns the signed PSBT base64; it may still require more signatures.
     */
    suspend fun signMultisigPsbtWithPhoneKeys(walletId: String, psbtBase64: String): String
}
