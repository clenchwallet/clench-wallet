package net.clench.wallet.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.ConcurrentHashMap
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

    // Wallet entry with connection for persistence
    private data class WalletEntry(val wallet: Wallet, val connection: Connection)

    // In-memory wallet cache to avoid reopening SQLite on every call
    private val walletCache = ConcurrentHashMap<String, WalletEntry>()

    /** Resolve the active BDK Network from settings. */
    private fun activeNetwork(): Network =
        if (settingsManager.isTestnet()) Network.TESTNET else Network.BITCOIN

    override suspend fun createWallet(
        name: String,
        wordCount: Int,
        passphrase: String?
    ): Pair<List<String>, WalletData> = withContext(Dispatchers.IO) {
        // Generate mnemonic
        val wordCountEnum = if (wordCount == 12) WordCount.WORDS12 else WordCount.WORDS24
        val mnemonic = Mnemonic(wordCountEnum)
        val mnemonicWords = mnemonic.toString().split(" ")

        // BDK 1.1.0: use Descriptor.newBip84() factory for correct BIP84 wpkh derivation
        val network = activeNetwork()
        val secretKey = DescriptorSecretKey(network, mnemonic, passphrase ?: "")
        val externalDescriptor = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
        val changeDescriptor = Descriptor.newBip84(secretKey, KeychainKind.INTERNAL, network)

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val connection = Connection(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, network, connection)
        walletCache[walletId] = WalletEntry(wallet, connection)

        // Store mnemonic and passphrase in encrypted keystore
        // NOTE: JVM String is immutable — mnemonic cannot be securely zeroed. This is a known JVM limitation.
        // For production, consider using a native library that handles key material in off-heap memory.
        keystoreManager.storeMnemonic(walletId, mnemonicWords.joinToString(" "))
        passphrase?.let { keystoreManager.storePassphrase(walletId, it) }

        // Store SECRET descriptors (with xprv) in encrypted Keystore — never in Room DB
        keystoreManager.storeSecretDescriptor(walletId, externalDescriptor.toStringWithSecret())
        keystoreManager.storeSecretChangeDescriptor(walletId, changeDescriptor.toStringWithSecret())

        // Persist wallet metadata to Room DB — PUBLIC descriptors only (xpub, no xprv)
        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = false,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis()
        )
        walletDao.insert(walletEntity)

        // Return mnemonic words and wallet data (public descriptors only)
        val walletData = WalletData(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
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

        // BDK 1.1.0: use Descriptor.newBip84() factory for correct BIP84 wpkh derivation
        val network = activeNetwork()
        val secretKey = DescriptorSecretKey(network, mnemonicObj, passphrase ?: "")
        val externalDescriptor = Descriptor.newBip84(secretKey, KeychainKind.EXTERNAL, network)
        val changeDescriptor = Descriptor.newBip84(secretKey, KeychainKind.INTERNAL, network)

        // Prevent duplicate imports — compare using public descriptor
        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val secretDescriptor = externalDescriptor.toStringWithSecret()
        val secretChangeDescriptor = changeDescriptor.toStringWithSecret()
        val existing = walletDao.getAll()
        if (existing.any { it.descriptor == publicDescriptor }) {
            throw IllegalArgumentException("This seed phrase is already imported in your wallet list.")
        }

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val connection = Connection(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, network, connection)
        walletCache[walletId] = WalletEntry(wallet, connection)

        // Store mnemonic and passphrase in encrypted keystore
        // NOTE: JVM String is immutable — mnemonic cannot be securely zeroed. This is a known JVM limitation.
        // For production, consider using a native library that handles key material in off-heap memory.
        keystoreManager.storeMnemonic(walletId, mnemonic.joinToString(" "))
        passphrase?.let { keystoreManager.storePassphrase(walletId, it) }

        // Store SECRET descriptors (with xprv) in encrypted Keystore — never in Room DB
        keystoreManager.storeSecretDescriptor(walletId, secretDescriptor)
        keystoreManager.storeSecretChangeDescriptor(walletId, secretChangeDescriptor)

        // Persist wallet metadata to Room DB — PUBLIC descriptors only (xpub, no xprv)
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = false,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis()
        )
        walletDao.insert(walletEntity)

        // Return wallet data (public descriptors only)
        WalletData(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
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

        val network = activeNetwork()
        val externalDescriptor = try {
            Descriptor(externalDescriptorStr, network)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid descriptor or extended public key. Please check the format and try again.")
        }
        val changeDescriptor = try {
            Descriptor(changeDescriptorStr, network)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid descriptor or extended public key. Please check the format and try again.")
        }

        // Prevent duplicate imports
        val existing = walletDao.getAll()
        if (existing.any { it.descriptor == externalDescriptor.toString() }) {
            throw IllegalArgumentException("A wallet with this descriptor is already in your wallet list.")
        }

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence (no signing keys)
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val connection = Connection(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, network, connection)
        walletCache[walletId] = WalletEntry(wallet, connection)

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
        val entry = loadWallet(walletId)
        val wallet = entry.wallet

        // Perform full scan with timeout to prevent hanging on bad servers
        // ElectrumClient must always be closed — resource leak causes silent failures on retry
        // Use longer timeout for custom/private nodes which may be slower
        val timeoutMs = if (effectiveConfig.isCustom) 60_000L else 30_000L
        try {
            withTimeout(timeoutMs) {
                val fullScanRequest = wallet.startFullScan().build()
                val update = electrumClient.fullScan(
                    fullScanRequest,
                    stopGap = 20u,
                    batchSize = 10u,
                    fetchPrevTxouts = true
                )

                // Apply update to wallet
                wallet.applyUpdate(update)

                // Persist wallet state after sync
                wallet.persist(entry.connection)
            }
        } finally {
            electrumClient.close()
        }

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
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
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

    override suspend fun getLastAddress(walletId: String): DomainAddress = withContext(Dispatchers.IO) {
        val entry = walletCache[walletId] ?: run {
            loadWallet(walletId)
            walletCache[walletId]!!
        }
        val wallet = entry.wallet
        // peekAddress does not advance the index — safe to call repeatedly
        val addressInfo = wallet.peekAddress(KeychainKind.EXTERNAL, 0u)
        DomainAddress(
            address = addressInfo.address.toString(),
            index = addressInfo.index.toInt(),
            used = false
        )
    }

    override suspend fun getReceiveAddress(walletId: String): DomainAddress = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val addressInfo = wallet.revealNextAddress(KeychainKind.EXTERNAL)

        // Persist wallet state after revealing address
        wallet.persist(entry.connection)

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
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val network = activeNetwork()
        val recipientAddress = org.bitcoindevkit.Address(toAddress, network)
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

        // Extract final transaction and serialize
        val finalTx = psbt.extractTx()
        return@withContext finalTx.serialize().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    override suspend fun broadcastTransaction(config: ElectrumConfig, txHex: String): String = withContext(Dispatchers.IO) {
        val connectionStr = buildElectrumUrl(config)
        val electrumClient = ElectrumClient(connectionStr)

        // Parse transaction from hex bytes
        val txBytes = txHex.chunked(2).map { it.toInt(16).toUByte() }
        val tx = Transaction(txBytes)

        // Broadcast to network — returns txid string
        // ElectrumClient must always be closed after use
        try {
            return@withContext electrumClient.transactionBroadcast(tx)
        } finally {
            electrumClient.close()
        }
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

        // Delete wallet SQLite file + WAL/SHM journal files
        val dbFile = context.getDatabasePath("wallet_${walletId}.db")
        dbFile.delete()
        java.io.File(dbFile.path + "-wal").delete()
        java.io.File(dbFile.path + "-shm").delete()
        java.io.File(dbFile.path + "-journal").delete()
    }

    override suspend fun getAddresses(walletId: String, count: Int): List<DomainAddress> = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
        val wallet = entry.wallet

        // Get known transactions to determine "used" status
        val txAddresses = mutableSetOf<String>()
        try {
            val transactions = wallet.transactions()
            // We can't easily get per-address usage from BDK, so we'll mark all as unknown
            // The sync would need to track this; for now, leave used=false
        } catch (_: Exception) {}

        val addresses = mutableListOf<DomainAddress>()
        for (i in 0 until count) {
            val addressInfo = wallet.peekAddress(KeychainKind.EXTERNAL, i.toUInt())
            addresses.add(
                DomainAddress(
                    address = addressInfo.address.toString(),
                    index = i,
                    used = false
                )
            )
        }
        addresses
    }

    /**
     * Load wallet from cache or SQLite.
     * For signing wallets: retrieves secret descriptors (xprv) from encrypted Keystore.
     * For watch-only wallets: uses public descriptors from Room DB.
     */
    private suspend fun loadWallet(walletId: String): WalletEntry {
        // Check cache first
        walletCache[walletId]?.let { return it }

        // Load wallet entity from Room DB
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")

        // For non-watch-only wallets, use secret descriptors from Keystore (contains xprv for signing)
        // Fall back to public descriptors from Room DB (watch-only or migration case)
        val network = activeNetwork()
        val externalDescriptorStr = if (!walletEntity.isWatchOnly) {
            keystoreManager.getSecretDescriptor(walletId) ?: walletEntity.descriptor
        } else {
            walletEntity.descriptor
        }
        val changeDescriptorStr = if (!walletEntity.isWatchOnly) {
            keystoreManager.getSecretChangeDescriptor(walletId) ?: walletEntity.changeDescriptor
        } else {
            walletEntity.changeDescriptor
        }
        val externalDescriptor = Descriptor(externalDescriptorStr, network)
        val changeDescriptor = Descriptor(changeDescriptorStr, network)

        // Load wallet from SQLite (if exists, else create new)
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val connection = Connection(dbPath)

        val wallet = try {
            Wallet.load(externalDescriptor, changeDescriptor, connection)
        } catch (e: org.bitcoindevkit.LoadWithPersistException.CouldNotLoad) {
            // Wallet DB exists but has no persisted state yet — create fresh BDK wallet
            Wallet(externalDescriptor, changeDescriptor, network, connection)
        } catch (e: org.bitcoindevkit.LoadWithPersistException.Persist) {
            // SQLite not found / unable to open — create fresh BDK wallet
            Wallet(externalDescriptor, changeDescriptor, network, connection)
        } catch (e: Exception) {
            // Descriptor mismatch, InvalidChangeSet, or other BDK error
            // This can happen after network switch or DB corruption
            // Delete the stale wallet DB and create fresh
            android.util.Log.w("BdkBitcoinRepository", "Wallet load failed (${e.javaClass.simpleName}: ${e.message}), recreating wallet DB for $walletId")
            try {
                val dbFile = context.getDatabasePath("wallet_${walletId}.db")
                dbFile.delete()
                java.io.File(dbFile.path + "-wal").delete()
                java.io.File(dbFile.path + "-shm").delete()
                java.io.File(dbFile.path + "-journal").delete()
            } catch (_: Exception) {}
            val freshConnection = Connection(dbPath)
            Wallet(externalDescriptor, changeDescriptor, network, freshConnection)
        }

        // Cache and return
        val entry = WalletEntry(wallet, connection)
        walletCache[walletId] = entry
        return entry
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
        // Already a full descriptor string — but may still contain a zpub/ypub that needs converting
        if (input.startsWith("wpkh(") || input.startsWith("pkh(") ||
            input.startsWith("sh(wpkh(") || input.startsWith("tr(")) {
            // Extract and convert any non-xpub extended key inside the descriptor
            val converted = input
                .replace(Regex("zpub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("Zpub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("ypub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("Ypub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("vpub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
            val external = if (!converted.contains("/0/*") && !converted.contains("/*")) {
                // No derivation path — add /0/*
                converted.trimEnd(')') + "/0/*)"
            } else converted
            val change = external.replace("/0/*", "/1/*")
            return Pair(external, change)
        }

        // Bare extended public key — convert to xpub and wrap in wpkh descriptor
        // Zpub (capital Z) = P2WSH multisig and Ypub (capital Y) = P2SH-P2WSH multisig
        // These are not supported as single-sig watch-only imports
        if (input.startsWith("Zpub") || input.startsWith("Ypub")) {
            throw IllegalArgumentException(
                "Multisig extended keys (Zpub/Ypub) are not supported. " +
                "Please provide a full descriptor for multisig wallets, e.g. wsh(multi(2,xpub1.../0/*,xpub2.../0/*))"
            )
        }

        val (xpub, scriptType) = when {
            input.startsWith("zpub") -> Pair(convertZpubToXpub(input), "wpkh")  // P2WPKH
            input.startsWith("ypub") -> Pair(convertZpubToXpub(input), "sh_wpkh")  // P2SH-P2WPKH
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
