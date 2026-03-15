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
import net.clench.wallet.domain.model.Address as DomainAddress
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.TransactionItem
import net.clench.wallet.domain.model.TxDirection
import net.clench.wallet.domain.model.WalletBalance
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.Amount
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.Connection
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.ElectrumClient
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Wallet
import org.bitcoindevkit.WordCount
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
        // Normalize input — handle bare zpub/ypub/xpub and full descriptor strings
        val (externalDescriptorStr, changeDescriptorStr) = normalizeDescriptor(descriptor.trim())

        val externalDescriptor = Descriptor(externalDescriptorStr, Network.BITCOIN)
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
        val transactionEntities = transactions.map { canonicalTx ->
            val tx = canonicalTx.transaction
            val sentAndReceived = wallet.sentAndReceived(tx)
            val sent = sentAndReceived.sent.toSat()
            val received = sentAndReceived.received.toSat()

            // Determine direction and amount
            val (direction, amount) = if (received > sent) {
                TxDirection.RECEIVED to (received - sent)
            } else {
                TxDirection.SENT to (sent - received)
            }

            // Get confirmation timestamp from chain position
            val timestampMs = when (val pos = canonicalTx.chainPosition) {
                is ChainPosition.Confirmed -> pos.confirmationBlockTime.confirmationTime.toLong() * 1000L
                else -> null
            }

            TransactionEntity(
                txid = tx.computeTxid(),
                walletId = walletId,
                amountSat = amount.toLong(),
                feeSat = null,
                timestampEpochMs = timestampMs,
                confirmations = 0,
                direction = direction.name,
                address = null
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

    override suspend fun getReceiveAddress(walletId: String): DomainAddress = withContext(Dispatchers.IO) {
        val wallet = loadWallet(walletId)
        val addressInfo = wallet.revealNextAddress(KeychainKind.EXTERNAL)
        DomainAddress(
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
                .addRecipient(recipientAddress.scriptPubkey(), Amount.fromSat(amountSat.toULong()))
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

        // Parse transaction from hex bytes
        val txBytes = txHex.chunked(2).map { it.toInt(16).toUByte() }
        val tx = Transaction(txBytes)

        // Broadcast to network — returns txid string
        return@withContext electrumClient.transactionBroadcast(tx)
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

    // Normalize watch-only input into a pair of (externalDescriptor, changeDescriptor) strings.
    // Handles zpub, ypub, xpub, and full descriptor strings.
    private fun normalizeDescriptor(input: String): Pair<String, String> {
        // Already a full descriptor string — use as-is and derive change
        if (input.startsWith("wpkh(") || input.startsWith("pkh(") ||
            input.startsWith("sh(wpkh(") || input.startsWith("tr(")) {
            val external = if (!input.contains("/0/*") && !input.contains("/*")) {
                // No derivation path — add /0/*
                input.trimEnd(')') + "/0/*)"
            } else input
            val change = external.replace("/0/*", "/1/*")
            return Pair(external, change)
        }

        // Bare extended public key — convert to xpub and wrap in wpkh descriptor
        val (xpub, scriptType) = when {
            input.startsWith("zpub") -> Pair(convertZpubToXpub(input), "wpkh")
            input.startsWith("Zpub") -> Pair(convertZpubToXpub(input), "wpkh")  // mainnet P2WPKH-P2SH
            input.startsWith("ypub") -> Pair(convertZpubToXpub(input), "sh_wpkh")
            input.startsWith("Ypub") -> Pair(convertZpubToXpub(input), "sh_wpkh")
            input.startsWith("xpub") -> Pair(input, "wpkh")
            input.startsWith("tpub") -> Pair(input, "wpkh")  // testnet
            input.startsWith("vpub") -> Pair(convertZpubToXpub(input), "wpkh")  // testnet zpub
            else -> Pair(input, "wpkh")  // try as-is
        }

        return when (scriptType) {
            "sh_wpkh" -> Pair("sh(wpkh($xpub/0/*))", "sh(wpkh($xpub/1/*))")
            else -> Pair("wpkh($xpub/0/*)", "wpkh($xpub/1/*)")
        }
    }

    // Convert zpub/ypub/vpub to standard xpub by swapping version bytes (Base58Check decode, swap, re-encode).
    private fun convertZpubToXpub(zpub: String): String {
        val XPUB_VERSION = byteArrayOf(0x04.toByte(), 0x88.toByte(), 0xB2.toByte(), 0x1E.toByte())

        try {
            val decoded = base58Decode(zpub)
            if (decoded.size < 78) return zpub  // not a valid extended key, return as-is

            // Replace first 4 version bytes with xpub version
            val xpubBytes = XPUB_VERSION + decoded.sliceArray(4 until decoded.size - 4)

            // Re-add checksum
            val checksum = doubleSha256(xpubBytes).sliceArray(0..3)
            return base58Encode(xpubBytes + checksum)
        } catch (e: Exception) {
            // If conversion fails, return original and let BDK give a clear error
            return zpub
        }
    }

    private fun doubleSha256(data: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    private fun base58Decode(input: String): ByteArray {
        val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var result = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (c in input) {
            val digit = ALPHABET.indexOf(c)
            if (digit < 0) throw IllegalArgumentException("Invalid Base58 character: $c")
            result = result.multiply(base).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        val bytes = result.toByteArray()
        // Count leading zeros
        var leadingZeros = 0
        for (c in input) { if (c == '1') leadingZeros++ else break }
        val stripped = if (bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        return ByteArray(leadingZeros) + stripped
    }

    private fun base58Encode(input: ByteArray): String {
        val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var value = java.math.BigInteger(1, input)
        val base = java.math.BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (value > java.math.BigInteger.ZERO) {
            val (quotient, remainder) = value.divideAndRemainder(base)
            sb.append(ALPHABET[remainder.toInt()])
            value = quotient
        }
        for (b in input) { if (b == 0.toByte()) sb.append('1') else break }
        return sb.reverse().toString()
    }
}
