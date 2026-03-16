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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // R7-1: Per-wallet sync mutex to prevent concurrent syncs corrupting BDK wallet DB
    private val syncMutexes = ConcurrentHashMap<String, Mutex>()
    private fun syncMutex(walletId: String) = syncMutexes.getOrPut(walletId) { Mutex() }

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
        // Passphrase is intentionally NOT stored — user must re-enter it for restore

        // Store SECRET descriptors (with xprv) in encrypted Keystore — never in Room DB
        keystoreManager.storeSecretDescriptor(walletId, externalDescriptor.toStringWithSecret())
        keystoreManager.storeSecretChangeDescriptor(walletId, changeDescriptor.toStringWithSecret())

        // Persist wallet metadata to Room DB — PUBLIC descriptors only (xpub, no xprv)
        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val activeNetwork = settingsManager.getNetwork()
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = false,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis(),
            network = activeNetwork,
            hasPassphrase = !passphrase.isNullOrBlank()
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
            createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs),
            network = activeNetwork,
            hasPassphrase = !passphrase.isNullOrBlank()
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
        // Passphrase is intentionally NOT stored — user must re-enter it for restore

        // Store SECRET descriptors (with xprv) in encrypted Keystore — never in Room DB
        keystoreManager.storeSecretDescriptor(walletId, secretDescriptor)
        keystoreManager.storeSecretChangeDescriptor(walletId, secretChangeDescriptor)

        // Persist wallet metadata to Room DB — PUBLIC descriptors only (xpub, no xprv)
        val activeNetwork = settingsManager.getNetwork()
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = false,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis(),
            network = activeNetwork,
            hasPassphrase = !passphrase.isNullOrBlank()
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
            createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs),
            network = activeNetwork,
            hasPassphrase = !passphrase.isNullOrBlank()
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
        val activeNetwork = settingsManager.getNetwork()
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = externalDescriptor.toString(),
            changeDescriptor = changeDescriptor.toString(),
            isWatchOnly = true,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis(),
            network = activeNetwork
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
            createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs),
            network = activeNetwork
        )
    }

    override suspend fun syncWallet(walletId: String, config: ElectrumConfig?): WalletBalance = withContext(Dispatchers.IO) {
        // Offline mode — skip sync entirely, return cached balance
        if (settingsManager.isOfflineMode()) {
            android.util.Log.d("BdkRepo", "Offline mode — skipping sync")
            return@withContext getBalance(walletId)
        }

        // R7-1: Per-wallet mutex — skip if already syncing to prevent concurrent BDK access
        val mutex = syncMutex(walletId)
        if (mutex.isLocked) {
            android.util.Log.d("BdkRepo", "syncWallet: already syncing $walletId, skipping")
            return@withContext getBalance(walletId)
        }

        mutex.withLock {
            // Use provided config or fall back to saved settings
            val effectiveConfig = config ?: settingsManager.loadElectrumConfig()

            // Never silently fall back to public server when custom is configured
            if (effectiveConfig.isCustom && effectiveConfig.serverUrl.isBlank()) {
                throw IllegalStateException("Custom server is enabled but no server address is configured")
            }

            // Build Electrum connection string
            val connectionStr = buildElectrumUrl(effectiveConfig)
            android.util.Log.d("BdkRepo", "syncWallet: url=${effectiveConfig.serverUrl}, port=${effectiveConfig.port}, ssl=${effectiveConfig.useSsl}, custom=${effectiveConfig.isCustom}")
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

            // R7-4: Calculate tip height from confirmed transactions for confirmation count
            val transactions = wallet.transactions()
            var tipHeight: UInt = 0u
            for (canonicalTx in transactions) {
                val pos = canonicalTx.chainPosition
                if (pos is ChainPosition.Confirmed) {
                    val h = pos.confirmationBlockTime.blockId.height
                    if (h > tipHeight) tipHeight = h
                }
            }

            // Cache transactions to Room DB
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

                // R7-4: Get confirmation timestamp and calculate confirmations
                val (timestampMs, confirmations) = when (val pos = canonicalTx.chainPosition) {
                    is ChainPosition.Confirmed -> {
                        val ts = pos.confirmationBlockTime.confirmationTime.toLong() * 1000L
                        val txHeight = pos.confirmationBlockTime.blockId.height
                        val confs = if (tipHeight >= txHeight) (tipHeight - txHeight + 1u).toInt() else 1
                        Pair(ts, confs)
                    }
                    else -> Pair(null, 0)
                }

                // R7-5: Calculate fee if possible (may fail for watch-only wallets)
                val feeSat: Long? = try {
                    wallet.calculateFee(tx).toSat().toLong()
                } catch (_: Exception) {
                    null
                }

                TransactionEntity(
                    txid = tx.computeTxid(),
                    walletId = walletId,
                    amountSat = amount.toLong(),
                    feeSat = feeSat,
                    timestampEpochMs = timestampMs,
                    confirmations = confirmations,
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
        // R7-3: Fixed !! crash — use loadWallet() which always returns a valid entry
        // R7-8: Use nextUnusedAddress() which returns the next unused address without advancing the gap limit
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val addressInfo = wallet.nextUnusedAddress(KeychainKind.EXTERNAL)
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
        if (settingsManager.isOfflineMode()) {
            throw IllegalStateException("Cannot broadcast in offline mode")
        }
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
        val network = settingsManager.getNetwork()
        return walletDao.getAllByNetwork(network).map { entity ->
            WalletData(
                id = entity.id,
                name = entity.name,
                descriptor = entity.descriptor,
                changeDescriptor = entity.changeDescriptor,
                isWatchOnly = entity.isWatchOnly,
                isMultisig = entity.isMultisig,
                createdAt = java.time.Instant.ofEpochMilli(entity.createdAtEpochMs),
                network = entity.network,
                preferredHardwareWallet = entity.preferredHardwareWallet,
                hasPassphrase = entity.hasPassphrase
            )
        }
    }

    override suspend fun deleteWallet(walletId: String) {
        // Remove from cache first
        walletCache.remove(walletId)

        // 1. Delete secrets FIRST — if process is killed mid-delete, private keys are already gone
        keystoreManager.deleteWalletSecrets(walletId)

        // 2. Delete BDK wallet SQLite files (wallet DB + WAL/SHM/journal)
        val dbFile = context.getDatabasePath("wallet_${walletId}.db")
        dbFile.delete()
        java.io.File(dbFile.path + "-wal").delete()
        java.io.File(dbFile.path + "-shm").delete()
        java.io.File(dbFile.path + "-journal").delete()

        // 3. Delete metadata from Room DB last — orphaned metadata without secrets is harmless
        transactionDao.deleteForWallet(walletId)
        walletDao.deleteById(walletId)
    }

    override suspend fun getAddresses(walletId: String, count: Int): List<DomainAddress> = withContext(Dispatchers.IO) {
        getAddresses(walletId, KeychainKind.EXTERNAL, count)
    }

    override suspend fun getAddresses(walletId: String, keychain: KeychainKind, count: Int): List<DomainAddress> = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
        val wallet = entry.wallet

        // Determine "used" addresses by checking BDK's unused address list
        val unusedAddresses = try {
            wallet.listUnusedAddresses(keychain).map { it.address.toString() }.toSet()
        } catch (_: Exception) { emptySet() }

        // Get the last revealed index to know which addresses have been revealed
        val lastRevealedIndex = try {
            wallet.derivationIndex(keychain)?.toInt() ?: 0
        } catch (_: Exception) { 0 }

        val addresses = mutableListOf<DomainAddress>()
        for (i in 0 until count) {
            val addressInfo = wallet.peekAddress(keychain, i.toUInt())
            val addrStr = addressInfo.address.toString()
            // An address is "used" if it has been revealed and is not in the unused list
            val isUsed = i <= lastRevealedIndex && addrStr !in unusedAddresses
            addresses.add(
                DomainAddress(
                    address = addrStr,
                    index = i,
                    used = isUsed
                )
            )
        }
        addresses
    }

    override suspend fun renameWallet(walletId: String, newName: String) {
        walletDao.updateName(walletId, newName)
    }

    override suspend fun getAccountXpub(walletId: String): String = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        val descriptorStr = walletEntity.descriptor
        
        // Parse the xpub from the descriptor: wpkh([fp/path]xpub.../0/*)
        val xpubRegex = Regex("[xt]pub[1-9A-HJ-NP-Za-km-z]+")
        val xpubMatch = xpubRegex.find(descriptorStr)
            ?: return@withContext descriptorStr // fallback: return full descriptor

        val xpub = xpubMatch.value
        val isTestnet = walletEntity.network == "testnet"

        // Convert to the appropriate display format
        if (isTestnet) {
            // xpub → vpub for testnet P2WPKH, or if already tpub → vpub
            convertXpubToZpub(xpub, testnet = true)
        } else {
            // xpub → zpub for mainnet P2WPKH
            convertXpubToZpub(xpub, testnet = false)
        }
    }

    override suspend fun getDerivationPath(walletId: String): String {
        val walletEntity = walletDao.getById(walletId)
            ?: return "Unknown"
        val descriptorStr = walletEntity.descriptor

        // Try to parse from descriptor origin: [fingerprint/84h/0h/0h]
        val originRegex = Regex("""\[([0-9a-fA-F]+)/([\d'h/]+)\]""")
        val match = originRegex.find(descriptorStr)
        if (match != null) {
            val path = match.groupValues[2]
                .replace("h", "'")
            return "m/$path"
        }

        // Fallback based on network
        return if (walletEntity.network == "testnet") "m/84'/1'/0'" else "m/84'/0'/0'"
    }

    override suspend fun getWalletEntity(walletId: String): WalletData? {
        val entity = walletDao.getById(walletId) ?: return null
        return WalletData(
            id = entity.id,
            name = entity.name,
            descriptor = entity.descriptor,
            changeDescriptor = entity.changeDescriptor,
            isWatchOnly = entity.isWatchOnly,
            isMultisig = entity.isMultisig,
            createdAt = java.time.Instant.ofEpochMilli(entity.createdAtEpochMs),
            network = entity.network,
            preferredHardwareWallet = entity.preferredHardwareWallet,
            hasPassphrase = entity.hasPassphrase
        )
    }

    override suspend fun setPreferredHardwareWallet(walletId: String, device: String?) {
        walletDao.updatePreferredHardwareWallet(walletId, device)
    }

    // Convert xpub/tpub to zpub/vpub format for display
    private fun convertXpubToZpub(key: String, testnet: Boolean): String {
        val ZPUB_VERSION = byteArrayOf(0x04.toByte(), 0xB2.toByte(), 0x47.toByte(), 0x46.toByte())
        val VPUB_VERSION = byteArrayOf(0x04.toByte(), 0x5F.toByte(), 0x1C.toByte(), 0xF6.toByte())

        try {
            val decoded = base58Decode(key)
            if (decoded.size < 78) return key

            val targetVersion = if (testnet) VPUB_VERSION else ZPUB_VERSION
            val convertedBytes = targetVersion + decoded.sliceArray(4 until decoded.size - 4)
            val checksum = doubleSha256(convertedBytes).sliceArray(0..3)
            return base58Encode(convertedBytes + checksum)
        } catch (e: Exception) {
            return key
        }
    }

    override suspend fun createPsbt(
        walletId: String,
        toAddress: String,
        amountSat: Long?,
        feeRateSatPerVbyte: Float,
        utxoTxid: String?,
        utxoVout: UInt?
    ): String = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val network = activeNetwork()
        val recipientAddress = org.bitcoindevkit.Address(toAddress, network)
        val feeRate = FeeRate.fromSatPerVb(feeRateSatPerVbyte.toLong().toULong())

        val builder = if (amountSat == null) {
            TxBuilder()
                .drainWallet()
                .drainTo(recipientAddress.scriptPubkey())
                .feeRate(feeRate)
        } else {
            TxBuilder()
                .addRecipient(recipientAddress.scriptPubkey(), Amount.fromSat(amountSat.toULong()))
                .feeRate(feeRate)
        }

        // Optionally restrict to a specific UTXO
        if (utxoTxid != null && utxoVout != null) {
            builder.addUtxo(org.bitcoindevkit.OutPoint(utxoTxid, utxoVout))
            builder.manuallySelectedOnly()
        }

        // Build PSBT (unsigned — do NOT sign)
        val psbt = builder.finish(wallet)

        // Return base64-encoded PSBT
        psbt.serialize()
    }

    override suspend fun applyAndBroadcastPsbt(walletId: String, signedPsbtBase64: String, unsignedPsbtBase64: String): String = withContext(Dispatchers.IO) {
        if (settingsManager.isOfflineMode()) {
            throw IllegalStateException("Cannot broadcast in offline mode")
        }

        // Validate that signed PSBT outputs match the original unsigned PSBT
        validatePsbtOutputsMatch(unsignedPsbtBase64, signedPsbtBase64)

        // Import the signed PSBT
        val signedPsbt = Psbt(signedPsbtBase64)

        // Finalize the PSBT
        val finalizeResult = signedPsbt.finalize()
        if (!finalizeResult.couldFinalize) {
            val errorMsgs = finalizeResult.errors?.joinToString(", ") { it.toString() } ?: "Unknown error"
            throw IllegalStateException("Could not finalize PSBT: $errorMsgs")
        }

        // Extract and broadcast the finalized transaction
        val tx = finalizeResult.psbt.extractTx()
        val config = settingsManager.loadElectrumConfig()
        val connectionStr = buildElectrumUrl(config)
        val electrumClient = ElectrumClient(connectionStr)
        try {
            electrumClient.transactionBroadcast(tx)
        } finally {
            electrumClient.close()
        }
    }

    /**
     * Validate that signed PSBT outputs match the original unsigned PSBT.
     * Prevents a compromised hardware wallet from substituting output addresses/amounts.
     * Compares serialized PSBT bytes: unsigned_tx is immutable across signing,
     * so the serialized bytes of the unsigned portion must match.
     */
    private fun validatePsbtOutputsMatch(unsignedBase64: String, signedBase64: String) {
        val unsigned = Psbt(unsignedBase64)
        val signed = Psbt(signedBase64)

        // BDK 1.1.0: Psbt.serialize() returns base64. Compare the underlying transaction
        // by re-serializing both PSBTs and checking that the signed one is strictly larger
        // (signatures add bytes). More importantly, compare output structure via JSON if available.
        try {
            val unsignedJson = org.json.JSONObject(unsigned.jsonSerialize())
            val signedJson = org.json.JSONObject(signed.jsonSerialize())

            // Try multiple known JSON keys for the outputs array
            val unsignedOutputs = unsignedJson.optJSONArray("outputs")
                ?: unsignedJson.optJSONArray("tx_outputs")
                ?: unsignedJson.optJSONObject("unsigned_tx")?.optJSONArray("output")
            val signedOutputs = signedJson.optJSONArray("outputs")
                ?: signedJson.optJSONArray("tx_outputs")
                ?: signedJson.optJSONObject("unsigned_tx")?.optJSONArray("output")

            if (unsignedOutputs == null || signedOutputs == null) {
                android.util.Log.w("BdkRepo", "PSBT output validation: could not parse outputs from JSON, allowing broadcast")
                return
            }

            if (unsignedOutputs.length() != signedOutputs.length()) {
                throw SecurityException("PSBT tampered: output count changed (${unsignedOutputs.length()} → ${signedOutputs.length()})")
            }

            // Compare each output's value and script
            for (i in 0 until unsignedOutputs.length()) {
                val uOut = unsignedOutputs.getJSONObject(i)
                val sOut = signedOutputs.getJSONObject(i)

                // Amount check
                val uAmt = uOut.optLong("value", -1)
                val sAmt = sOut.optLong("value", -1)
                if (uAmt != sAmt && uAmt != -1L && sAmt != -1L) {
                    throw SecurityException("PSBT tampered: output $i amount changed ($uAmt → $sAmt)")
                }

                // Script pubkey check (hex comparison)
                val uScript = uOut.optString("script_pubkey", "")
                val sScript = sOut.optString("script_pubkey", "")
                if (uScript.isNotEmpty() && sScript.isNotEmpty() && uScript != sScript) {
                    throw SecurityException("PSBT tampered: output $i script_pubkey changed")
                }
            }

            // Fee reasonableness check
            try {
                @Suppress("USELESS_CAST")
                val feeAmount = signed.fee()
                val fee = (feeAmount as? Amount)?.toSat()?.toLong()
                    ?: (feeAmount as? ULong)?.toLong()
                    ?: -1L
                if (fee <= 0L) throw Exception("Cannot determine fee")
                var totalOut = 0L
                for (i in 0 until signedOutputs.length()) {
                    totalOut += signedOutputs.getJSONObject(i).optLong("value", 0)
                }
                if (totalOut > 0 && fee > totalOut / 2) {
                    throw SecurityException("PSBT fee ($fee sat) exceeds 50% of output value ($totalOut sat) — possible fee attack")
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("BdkRepo", "Fee validation skipped: ${e.message}")
            }

            android.util.Log.d("BdkRepo", "PSBT validation passed: ${unsignedOutputs.length()} outputs match")
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // JSON validation unavailable — refuse to broadcast rather than fall back to weak size check [M-5]
            throw SecurityException(
                "PSBT output validation failed: unable to verify outputs match (${e.message}). " +
                "Refusing to broadcast — re-create the PSBT and try again."
            )
        }
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

        // R7-2: Use the wallet's stored network, NOT the global setting
        // The wallet's own network is the truth — prevents loading testnet wallet with mainnet network
        val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
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
                .replace(Regex("upub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
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
            input.startsWith("upub") -> Pair(convertZpubToXpub(input), "sh_wpkh")  // testnet ypub
            else -> Pair(input, "wpkh")  // try as-is
        }

        return when (scriptType) {
            "sh_wpkh" -> Pair("sh(wpkh($xpub/0/*))", "sh(wpkh($xpub/1/*))")
            else -> Pair("wpkh($xpub/0/*)", "wpkh($xpub/1/*)")
        }
    }

    // Convert zpub/ypub/vpub/upub to standard xpub/tpub by swapping version bytes (Base58Check decode, swap, re-encode).
    // R7-17: Testnet keys (vpub/upub) convert to tpub, mainnet keys (zpub/ypub) convert to xpub.
    private fun convertZpubToXpub(key: String): String {
        val XPUB_VERSION = byteArrayOf(0x04.toByte(), 0x88.toByte(), 0xB2.toByte(), 0x1E.toByte())
        val TPUB_VERSION = byteArrayOf(0x04.toByte(), 0x35.toByte(), 0x87.toByte(), 0xCF.toByte())

        try {
            val decoded = base58Decode(key)
            if (decoded.size < 78) return key  // not a valid extended key, return as-is

            // Determine target version based on input prefix
            // vpub (0x045F1CF6) and upub are testnet → tpub
            // zpub (0x04B24746) and ypub (0x049D7CB2) are mainnet → xpub
            val targetVersion = if (key.startsWith("vpub") || key.startsWith("upub")) {
                TPUB_VERSION
            } else {
                XPUB_VERSION
            }

            // Replace first 4 version bytes with target version
            val convertedBytes = targetVersion + decoded.sliceArray(4 until decoded.size - 4)

            // Re-add checksum
            val checksum = doubleSha256(convertedBytes).sliceArray(0..3)
            return base58Encode(convertedBytes + checksum)
        } catch (e: Exception) {
            // If conversion fails, return original and let BDK give a clear error
            return key
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
