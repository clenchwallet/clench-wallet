package net.clench.wallet.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.TransactionDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.entity.TransactionEntity
import net.clench.wallet.data.local.entity.WalletEntity
import net.clench.wallet.domain.model.*
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BDK-backed implementation of BitcoinRepository.
 *
 * Uses BDK Android 1.1.0 for all Bitcoin operations:
 *   - Mnemonic generation and restoration
 *   - BIP84 descriptor derivation (wpkh native segwit)
 *   - SQLite wallet persistence
 *   - Electrum server sync
 *   - Transaction building and broadcasting
 */
@Singleton
class BdkBitcoinRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletDao: WalletDao,
    private val transactionDao: TransactionDao,
    private val keystoreManager: KeystoreManager,
    private val settingsManager: SettingsManager
) : BitcoinRepository {

    // In-memory wallet cache to avoid reopening SQLite on every call
    private val walletCache = mutableMapOf<String, Wallet>()

    override suspend fun createWallet(
        name: String,
        wordCount: Int,
        passphrase: String?
    ): Pair<List<String>, WalletData> = withContext(Dispatchers.IO) {
        // Generate mnemonic
        val wordCountEnum = if (wordCount == 12) WordCount.WORDS12 else WordCount.WORDS24
        val mnemonic = Mnemonic(wordCountEnum)
        val mnemonicWords = mnemonic.toString().split(" ")

        // Derive BIP84 descriptors
        val bip32RootKey = DescriptorSecretKey(Network.BITCOIN, mnemonic, passphrase ?: "")
        val externalDescriptor = Descriptor("wpkh(${bip32RootKey}/84h/0h/0h/0/*)", Network.BITCOIN)
        val changeDescriptor = Descriptor("wpkh(${bip32RootKey}/84h/0h/0h/1/*)", Network.BITCOIN)

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val connection = Connection(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, Network.BITCOIN, connection)
        walletCache[walletId] = wallet

        // Store mnemonic and passphrase in encrypted keystore
        keystoreManager.storeMnemonic(walletId, mnemonicWords.joinToString(" "))
        passphrase?.let { keystoreManager.storePassphrase(walletId, it) }

        // Persist wallet metadata to Room DB
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = externalDescriptor.toString(),
            changeDescriptor = changeDescriptor.toString(),
            isWatchOnly = false,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis()
        )
        walletDao.insert(walletEntity)

        // Return mnemonic words and wallet data
        val walletData = WalletData(
            id = walletId,
            name = name,
            descriptor = externalDescriptor.toString(),
            changeDescriptor = changeDescriptor.toString(),
            isWatchOnly = false,
            isMultisig = false,
            createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs)
        )

        Pair(mnemonicWords, walletData)
    }

    override suspend fun importWallet(
        name: String,
        mnemonic: List<String>,
        passphrase: String?
    ): WalletData = withContext(Dispatchers.IO) {
        // Restore mnemonic from words
        val mnemonicObj = Mnemonic.fromString(mnemonic.joinToString(" "))

        // Derive BIP84 descriptors
        val bip32RootKey = DescriptorSecretKey(Network.BITCOIN, mnemonicObj, passphrase ?: "")
        val externalDescriptor = Descriptor("wpkh(${bip32RootKey}/84h/0h/0h/0/*)", Network.BITCOIN)
        val changeDescriptor = Descriptor("wpkh(${bip32RootKey}/84h/0h/0h/1/*)", Network.BITCOIN)

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val connection = Connection(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, Network.BITCOIN, connection)
        walletCache[walletId] = wallet

        // Store mnemonic and passphrase in encrypted keystore
        keystoreManager.storeMnemonic(walletId, mnemonic.joinToString(" "))
        passphrase?.let { keystoreManager.storePassphrase(walletId, it) }

        // Persist wallet metadata to Room DB
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = externalDescriptor.toString(),
            changeDescriptor = changeDescriptor.toString(),
            isWatchOnly = false,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis()
        )
        walletDao.insert(walletEntity)

        // Return wallet data
        WalletData(
            id = walletId,
            name = name,
            descriptor = externalDescriptor.toString(),
            changeDescriptor = changeDescriptor.toString(),
            isWatchOnly = false,
            isMultisig = false,
            createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs)
        )
    }

    override suspend fun importWatchOnly(
        name: String,
        descriptor: String
    ): WalletData = withContext(Dispatchers.IO) {
        // Parse descriptor (assume it's an external descriptor, derive change from pattern)
        val externalDescriptor = Descriptor(descriptor, Network.BITCOIN)

        // For watch-only, we need a change descriptor too
        // Assume standard BIP84 pattern: change /0/* to /1/*
        val changeDescriptorStr = descriptor.replace("/0/*", "/1/*")
        val changeDescriptor = Descriptor(changeDescriptorStr, Network.BITCOIN)

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence (no signing keys)
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val connection = Connection(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, Network.BITCOIN, connection)
        walletCache[walletId] = wallet

        // Persist wallet metadata to Room DB (isWatchOnly = true)
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = externalDescriptor.toString(),
            changeDescriptor = changeDescriptor.toString(),
            isWatchOnly = true,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis()
        )
        walletDao.insert(walletEntity)

        // Return wallet data
        WalletData(
            id = walletId,
            name = name,
            descriptor = externalDescriptor.toString(),
            changeDescriptor = changeDescriptor.toString(),
            isWatchOnly = true,
            isMultisig = false,
            createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs)
        )
    }

    override suspend fun syncWallet(walletId: String, config: ElectrumConfig?): WalletBalance = withContext(Dispatchers.IO) {
        // Use provided config or fall back to saved settings
        val effectiveConfig = config ?: settingsManager.loadElectrumConfig()

        // Build Electrum connection string
        val connectionStr = buildElectrumUrl(effectiveConfig)
        val electrumClient = ElectrumClient(connectionStr)

        // Load wallet
        val wallet = loadWallet(walletId)

        // Perform full scan
        val fullScanRequest = wallet.startFullScan().build()
        val update = electrumClient.fullScan(
            fullScanRequest,
            stopGap = 20u,
            batchSize = 10u,
            fetchPrevTxouts = true
        )

        // Apply update to wallet
        wallet.applyUpdate(update)

        // Cache transactions to Room DB
        val transactions = wallet.transactions()
        val transactionEntities = transactions.map { tx ->
            val sentAndReceived = wallet.sentAndReceived(tx)
            val sent = sentAndReceived.sent.toSat()
            val received = sentAndReceived.received.toSat()

            // Determine direction and amount
            val (direction, amount) = if (received > sent) {
                TxDirection.RECEIVED to (received - sent)
            } else {
                TxDirection.SENT to (sent - received)
            }

            TransactionEntity(
                txid = tx.txid().toString(),
                walletId = walletId,
                amountSat = amount.toLong(),
                feeSat = null, // BDK doesn't directly provide fee per tx
                timestampEpochMs = null, // Would need to get from blockchain
                confirmations = 0, // Would need chain height info
                direction = direction.name,
                address = null // Could extract from outputs if needed
            )
        }
        transactionDao.insertAll(transactionEntities)

        // Return balance
        val balance = wallet.balance()
        WalletBalance(
            confirmedSat = balance.confirmed.toSat().toLong(),
            trustedPendingSat = balance.trustedPending.toSat().toLong(),
            untrustedPendingSat = balance.untrustedPending.toSat().toLong(),
            immatureSat = balance.immature.toSat().toLong()
        )
    }

    override suspend fun getBalance(walletId: String): WalletBalance = withContext(Dispatchers.IO) {
        val wallet = loadWallet(walletId)
        val balance = wallet.balance()
        WalletBalance(
            confirmedSat = balance.confirmed.toSat().toLong(),
            trustedPendingSat = balance.trustedPending.toSat().toLong(),
            untrustedPendingSat = balance.untrustedPending.toSat().toLong(),
            immatureSat = balance.immature.toSat().toLong()
        )
    }

    override suspend fun getTransactions(walletId: String): List<TransactionItem> {
        // TODO: return from local Room cache (populated after sync)
        return transactionDao.getForWallet(walletId).map { entity ->
            TransactionItem(
                txid = entity.txid,
                amountSat = entity.amountSat,
                feeSat = entity.feeSat,
                timestamp = entity.timestampEpochMs?.let {
                    java.time.Instant.ofEpochMilli(it)
                },
                confirmations = entity.confirmations,
                direction = TxDirection.valueOf(entity.direction),
                address = entity.address
            )
        }
    }

    override suspend fun getReceiveAddress(walletId: String): Address = withContext(Dispatchers.IO) {
        val wallet = loadWallet(walletId)
        val addressInfo = wallet.revealNextAddress(KeychainKind.EXTERNAL)
        Address(
            address = addressInfo.address.toString(),
            index = addressInfo.index.toInt(),
            used = false
        )
    }

    override suspend fun buildTransaction(
        walletId: String,
        toAddress: String,
        amountSat: Long?,
        feeRateSatPerVbyte: Float
    ): String = withContext(Dispatchers.IO) {
        val wallet = loadWallet(walletId)
        val recipientAddress = org.bitcoindevkit.Address(toAddress, Network.BITCOIN)
        val feeRate = FeeRate.fromSatPerVb(feeRateSatPerVbyte.toULong())

        // Build transaction (drain or send specific amount)
        val psbt = if (amountSat == null) {
            // Send max (drain wallet)
            TxBuilder()
                .drainWallet()
                .drainTo(recipientAddress.scriptPubkey())
                .feeRate(feeRate)
                .finish(wallet)
        } else {
            // Send specific amount
            TxBuilder()
                .addRecipient(recipientAddress.scriptPubkey(), amountSat.toULong())
                .feeRate(feeRate)
                .finish(wallet)
        }

        // Sign transaction
        wallet.sign(psbt)

        // Return serialized PSBT hex
        psbt.serialize()
    }

    override suspend fun broadcastTransaction(config: ElectrumConfig, txHex: String): String = withContext(Dispatchers.IO) {
        val connectionStr = buildElectrumUrl(config)
        val electrumClient = ElectrumClient(connectionStr)

        // Parse transaction from hex
        val tx = Transaction(txHex)

        // Broadcast to network
        electrumClient.broadcast(tx)

        // Return txid
        tx.txid().toString()
    }

    override suspend fun listWallets(): List<WalletData> {
        return walletDao.getAll().map { entity ->
            WalletData(
                id = entity.id,
                name = entity.name,
                descriptor = entity.descriptor,
                changeDescriptor = entity.changeDescriptor,
                isWatchOnly = entity.isWatchOnly,
                isMultisig = entity.isMultisig,
                createdAt = java.time.Instant.ofEpochMilli(entity.createdAtEpochMs)
            )
        }
    }

    override suspend fun deleteWallet(walletId: String) {
        // Remove from cache
        walletCache.remove(walletId)

        // Delete from database
        walletDao.deleteById(walletId)
        transactionDao.deleteForWallet(walletId)
        keystoreManager.deleteWalletSecrets(walletId)

        // Delete wallet SQLite file
        val dbFile = context.getDatabasePath("wallet_${walletId}.db")
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    /**
     * Load wallet from cache or SQLite, restoring descriptors from Room DB.
     */
    private suspend fun loadWallet(walletId: String): Wallet {
        // Check cache first
        walletCache[walletId]?.let { return it }

        // Load wallet entity from Room DB
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")

        // Parse descriptors
        val externalDescriptor = Descriptor(walletEntity.descriptor, Network.BITCOIN)
        val changeDescriptor = Descriptor(walletEntity.changeDescriptor, Network.BITCOIN)

        // Load wallet from SQLite (if exists, else create new)
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val connection = Connection(dbPath)

        val wallet = try {
            Wallet.load(externalDescriptor, changeDescriptor, connection)
        } catch (e: Exception) {
            // If load fails, create new wallet
            Wallet(externalDescriptor, changeDescriptor, Network.BITCOIN, connection)
        }

        // Cache and return
        walletCache[walletId] = wallet
        return wallet
    }

    /**
     * Build Electrum connection URL from config.
     * Format: ssl://host:port or tcp://host:port
     */
    private fun buildElectrumUrl(config: ElectrumConfig): String {
        val protocol = if (config.useSsl) "ssl" else "tcp"
        val host = config.serverUrl.removePrefix("ssl://").removePrefix("tcp://")
        return "$protocol://$host:${config.port}"
    }
}
