package net.clench.wallet.data.repository

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.domain.model.ScriptType
import net.clench.wallet.data.local.dao.TransactionDao
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.entity.TransactionEntity
import net.clench.wallet.data.local.entity.TransactionLabelEntity
import net.clench.wallet.data.local.entity.WalletEntity
import net.clench.wallet.domain.model.Address as DomainAddress
import net.clench.wallet.domain.model.ElectrumConfig
import net.clench.wallet.domain.model.TransactionItem
import net.clench.wallet.domain.model.FeeEstimates
import net.clench.wallet.domain.model.TxDirection
import net.clench.wallet.domain.model.WalletBalance
import net.clench.wallet.domain.model.WalletData
import net.clench.wallet.domain.repository.BitcoinRepository
import org.bitcoindevkit.Amount
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.Persister
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
    private val transactionLabelDao: TransactionLabelDao,
    private val utxoMetadataDao: UtxoMetadataDao,
    private val keystoreManager: KeystoreManager,
    private val settingsManager: SettingsManager,
    private val electrumConnectionFactory: net.clench.wallet.data.network.ElectrumConnectionFactory,
    private val torAwareHttpClient: TorAwareHttpClient
) : BitcoinRepository {

    private data class TransactionFingerprint(
        val inputs: List<String>,
        val outputs: List<OutputFingerprint>
    )

    private data class OutputFingerprint(
        val valueSat: Long,
        val scriptPubkeyHex: String
    )

    // [S-4] SECURITY: Gate sensitive debug logging in release builds.
    // In release, suppress logs that would expose wallet metadata, addresses, txids,
    // connection details, or recovery internals. Keep non-sensitive operational logs.
    private val logSensitive = android.util.Log.isLoggable("BdkRepo", android.util.Log.DEBUG)
        && (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // Wallet entry with persister for persistence
    private data class WalletEntry(val wallet: Wallet, val persister: Persister)

    /**
     * Result of normalizing a descriptor input, including extracted key origin info.
     */
    private data class NormalizedDescriptor(
        val externalDescriptor: String,
        val changeDescriptor: String,
        val masterFingerprint: String? = null,  // e.g., "D3E95C19"
        val derivationPath: String? = null       // e.g., "84'/0'/0'"
    )

    // In-memory wallet cache to avoid reopening SQLite on every call
    private val walletCache = ConcurrentHashMap<String, WalletEntry>()

    // Tracks passphrase wallets that have been explicitly unlocked via unlockPassphraseWallet().
    // walletCache is NOT a reliable unlock signal — loadWallet() pre-populates it with a
    // public-xpub in-memory wallet even in the locked state. This set is the authoritative
    // source of truth for whether a passphrase wallet is unlocked.
    private val unlockedPassphraseWallets = ConcurrentHashMap.newKeySet<String>()

    // R7-1: Per-wallet sync mutex to prevent concurrent syncs corrupting BDK wallet DB
    private val syncMutexes = ConcurrentHashMap<String, Mutex>()
    private fun syncMutex(walletId: String) = syncMutexes.getOrPut(walletId) { Mutex() }

    /** Resolve the active BDK Network from settings. */
    private fun activeNetwork(): Network =
        if (settingsManager.isTestnet()) Network.TESTNET else Network.BITCOIN

    override suspend fun createWallet(
        name: String,
        wordCount: Int,
        passphrase: String?,
        mnemonicWords: List<String>?,
        scriptType: ScriptType
    ): Pair<List<String>, WalletData> = withContext(Dispatchers.IO) {
        // Use the already-displayed/verified mnemonic when provided. Otherwise preserve the
        // legacy repository behavior of generating a fresh mnemonic for direct repository callers.
        val mnemonic = if (mnemonicWords != null) {
            Mnemonic.fromString(mnemonicWords.joinToString(" "))
        } else {
            val wordCountEnum = if (wordCount == 12) WordCount.WORDS12 else WordCount.WORDS24
            Mnemonic(wordCountEnum)
        }
        val walletMnemonicWords = mnemonicWords ?: mnemonic.toString().split(" ")

        // Derive descriptors for the selected script type.
        val network = activeNetwork()
        val secretKey = DescriptorSecretKey(network, mnemonic, passphrase ?: "")
        val externalDescriptor: Descriptor
        val changeDescriptor: Descriptor
        try {
            externalDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.EXTERNAL, network)
            changeDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.INTERNAL, network)
        } finally {
            // H-1: Destroy sensitive BDK objects after descriptor derivation
            try { mnemonic.destroy() } catch (_: Exception) {}
            try { secretKey.destroy() } catch (_: Exception) {}
        }

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, network, persister)
        walletCache[walletId] = WalletEntry(wallet, persister)

        // Store mnemonic in encrypted keystore
        // NOTE: JVM String is immutable — mnemonic cannot be securely zeroed. This is a known JVM limitation.
        // For production, consider using a native library that handles key material in off-heap memory.
        keystoreManager.storeMnemonic(walletId, walletMnemonicWords.joinToString(" "))

        // For passphrase wallets: store ONLY the mnemonic in keystore, NOT secret descriptors.
        // The secret descriptors are derived on-the-fly when the user enters their passphrase to unlock.
        // For non-passphrase wallets: store secret descriptors for normal operation.
        if (passphrase.isNullOrBlank()) {
            keystoreManager.storeSecretDescriptor(walletId, externalDescriptor.toStringWithSecret())
            keystoreManager.storeSecretChangeDescriptor(walletId, changeDescriptor.toStringWithSecret())
        }

        // Persist wallet metadata to Room DB — PUBLIC descriptors only (xpub, no xprv)
        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val activeNetwork = settingsManager.getNetwork()
        // Compute identicon bytes from master fingerprint + passphrase
        // This is stored so wallet info screen can show the same visual fingerprint
        // that was displayed during creation (passphrase is NOT stored)
        val identiconBytes = computeIdenticonBytes(publicDescriptor, passphrase)

        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = false,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis(),
            network = activeNetwork,
            hasPassphrase = !passphrase.isNullOrBlank(),
            identiconBytes = identiconBytes
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

        Pair(walletMnemonicWords, walletData)
    }

    override suspend fun importWallet(
        name: String,
        mnemonic: List<String>,
        passphrase: String?,
        scriptType: ScriptType
    ): WalletData = withContext(Dispatchers.IO) {
        // Restore mnemonic from words
        val mnemonicObj = Mnemonic.fromString(mnemonic.joinToString(" "))

        // Use the selected script type's BIP derivation
        val network = activeNetwork()
        val secretKey = DescriptorSecretKey(network, mnemonicObj, passphrase ?: "")
        val externalDescriptor: Descriptor
        val changeDescriptor: Descriptor
        try {
            externalDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.EXTERNAL, network)
            changeDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.INTERNAL, network)
        } finally {
            // H-1: Destroy sensitive BDK objects after descriptor derivation
            try { mnemonicObj.destroy() } catch (_: Exception) {}
            try { secretKey.destroy() } catch (_: Exception) {}
        }

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
        val persister = Persister.newSqlite(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, network, persister)
        walletCache[walletId] = WalletEntry(wallet, persister)

        // Store mnemonic in encrypted keystore
        // NOTE: JVM String is immutable — mnemonic cannot be securely zeroed. This is a known JVM limitation.
        // For production, consider using a native library that handles key material in off-heap memory.
        keystoreManager.storeMnemonic(walletId, mnemonic.joinToString(" "))

        // For passphrase wallets: store ONLY the mnemonic in keystore, NOT secret descriptors.
        // The secret descriptors are derived on-the-fly when the user enters their passphrase to unlock.
        // For non-passphrase wallets: store secret descriptors for normal operation.
        if (passphrase.isNullOrBlank()) {
            keystoreManager.storeSecretDescriptor(walletId, secretDescriptor)
            keystoreManager.storeSecretChangeDescriptor(walletId, secretChangeDescriptor)
        }

        // Persist wallet metadata to Room DB — PUBLIC descriptors only (xpub, no xprv)
        val activeNetwork = settingsManager.getNetwork()
        val identiconBytes = computeIdenticonBytes(publicDescriptor, passphrase)
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = false,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis(),
            network = activeNetwork,
            hasPassphrase = !passphrase.isNullOrBlank(),
            identiconBytes = identiconBytes
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
        descriptor: String,
        deviceType: String?
    ): WalletData = withContext(Dispatchers.IO) {
        // [S-4] Gate: wallet name and import details
        if (logSensitive) {
            android.util.Log.d("BdkRepo", "importWatchOnly: name=$name input=(redacted)")
        }
        // Normalize input — handle bare zpub/ypub/xpub and full descriptor strings
        val normalized = normalizeDescriptor(descriptor.trim())
        val externalDescriptorStr = normalized.externalDescriptor
        val changeDescriptorStr = normalized.changeDescriptor
        if (logSensitive) {
            android.util.Log.d("BdkRepo", "importWatchOnly: normalized external descriptor (redacted)")
            android.util.Log.d("BdkRepo", "importWatchOnly: origin fingerprint=${normalized.masterFingerprint} path=${normalized.derivationPath} device=$deviceType")
        }

        val network = activeNetwork()
        if (logSensitive) {
            android.util.Log.d("BdkRepo", "importWatchOnly: network=$network")
        }
        val externalDescriptor = try {
            Descriptor(externalDescriptorStr, network)
        } catch (e: Exception) {
            android.util.Log.e("BdkRepo", "importWatchOnly: external descriptor invalid")
            throw IllegalArgumentException("Invalid descriptor or extended public key. Please check the format and try again.\n\nDetails: ${e.message}")
        }
        val changeDescriptor = try {
            Descriptor(changeDescriptorStr, network)
        } catch (e: Exception) {
            android.util.Log.e("BdkRepo", "importWatchOnly: change descriptor invalid")
            throw IllegalArgumentException("Invalid descriptor or extended public key. Please check the format and try again.\n\nDetails: ${e.message}")
        }

        // Prevent duplicate imports — check current network only
        val existing = walletDao.getAllByNetwork(settingsManager.getNetwork())
        if (existing.any { it.descriptor == externalDescriptor.toString() }) {
            android.util.Log.w("BdkRepo", "importWatchOnly: duplicate descriptor found")
            throw IllegalArgumentException("A wallet with this descriptor is already in your wallet list.")
        }

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence (no signing keys)
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, network, persister)
        walletCache[walletId] = WalletEntry(wallet, persister)

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
            network = activeNetwork,
            masterFingerprint = normalized.masterFingerprint,
            derivationPath = normalized.derivationPath,
            importedViaDevice = deviceType,
            preferredHardwareWallet = deviceType  // Set per-wallet HW preference at import time
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
            network = activeNetwork,
            preferredHardwareWallet = deviceType,
            masterFingerprint = normalized.masterFingerprint,
            derivationPath = normalized.derivationPath,
            importedViaDevice = deviceType
        )
    }

    override suspend fun convertWatchOnlyToHot(
        walletId: String,
        mnemonic: List<String>,
        passphrase: String?
    ): Unit = withContext(Dispatchers.IO) {
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found")
        if (!walletEntity.isWatchOnly) {
            throw IllegalArgumentException("This wallet already has signing capability")
        }
        if (mnemonic.size != 12 && mnemonic.size != 24) {
            throw IllegalArgumentException("Enter a 12 or 24 word seed phrase")
        }

        val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
        val mnemonicObj = Mnemonic.fromString(mnemonic.joinToString(" "))
        var secretKey: DescriptorSecretKey? = null
        try {
            val passphraseValue = passphrase.orEmpty()
            secretKey = DescriptorSecretKey(network, mnemonicObj, passphraseValue)
            val scriptType = ScriptType.fromDescriptor(walletEntity.descriptor)
            val externalDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.EXTERNAL, network)
            val changeDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.INTERNAL, network)

            val expectedXpub = Regex("[xt]pub[1-9A-HJ-NP-Za-km-z]+").find(walletEntity.descriptor)?.value
            val derivedXpub = Regex("[xt]pub[1-9A-HJ-NP-Za-km-z]+").find(externalDescriptor.toString())?.value
            val descriptorsMatch = if (expectedXpub != null && derivedXpub != null) {
                expectedXpub == derivedXpub
            } else {
                walletEntity.descriptor.trim().substringBefore("#") == externalDescriptor.toString().trim().substringBefore("#")
            }

            if (!descriptorsMatch) {
                throw IllegalArgumentException("That seed phrase does not match this watch-only wallet")
            }

            keystoreManager.storeMnemonic(walletId, mnemonic.joinToString(" "))
            val hasPassphrase = !passphrase.isNullOrBlank()
            if (!hasPassphrase) {
                keystoreManager.storeSecretDescriptor(walletId, externalDescriptor.toStringWithSecret())
                keystoreManager.storeSecretChangeDescriptor(walletId, changeDescriptor.toStringWithSecret())
            }

            walletDao.setWatchOnlyAndPassphrase(walletId, isWatchOnly = false, hasPassphrase = hasPassphrase)

            // Evict the public-only cached wallet so future signing loads the secret descriptors.
            walletCache.remove(walletId)
            if (hasPassphrase) {
                val persister = Persister.newInMemory()
                val wallet = Wallet(externalDescriptor, changeDescriptor, network, persister)
                walletCache[walletId] = WalletEntry(wallet, persister)
                unlockedPassphraseWallets.add(walletId)
            }
        } finally {
            try { mnemonicObj.destroy() } catch (_: Exception) {}
            try { secretKey?.destroy() } catch (_: Exception) {}
        }
    }

    override suspend fun importPrivateDescriptor(
        name: String,
        descriptor: String
    ): WalletData = withContext(Dispatchers.IO) {
        if (!containsPrivateKeyMaterial(descriptor)) {
            throw IllegalArgumentException("Private descriptor import requires a private descriptor or private extended key.")
        }

        val normalized = normalizeDescriptor(descriptor.trim())
        val externalDescriptorStr = normalized.externalDescriptor
        val changeDescriptorStr = normalized.changeDescriptor

        if (!containsPrivateKeyMaterial(externalDescriptorStr) || !containsPrivateKeyMaterial(changeDescriptorStr)) {
            throw IllegalArgumentException("Private descriptor import requires private key material for both receive and change descriptors.")
        }

        val network = activeNetwork()
        val externalDescriptor = try {
            Descriptor(externalDescriptorStr, network)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid private descriptor. Please check the format and try again.\n\nDetails: ${e.message}")
        }
        val changeDescriptor = try {
            Descriptor(changeDescriptorStr, network)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid private change descriptor. Please check the format and try again.\n\nDetails: ${e.message}")
        }

        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val secretDescriptor = externalDescriptor.toStringWithSecret()
        val secretChangeDescriptor = changeDescriptor.toStringWithSecret()

        // Prevent duplicate imports — compare public descriptors on the current network only.
        val activeNetwork = settingsManager.getNetwork()
        val existing = walletDao.getAllByNetwork(activeNetwork)
        if (existing.any { it.descriptor == publicDescriptor }) {
            throw IllegalArgumentException("A wallet with this descriptor is already in your wallet list.")
        }

        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with secret descriptors so this wallet can sign.
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, network, persister)
        walletCache[walletId] = WalletEntry(wallet, persister)

        // Store secret descriptors only in encrypted keystore. Room receives public descriptors below.
        keystoreManager.storeSecretDescriptor(walletId, secretDescriptor)
        keystoreManager.storeSecretChangeDescriptor(walletId, secretChangeDescriptor)

        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = false,
            isMultisig = false,
            createdAtEpochMs = System.currentTimeMillis(),
            network = activeNetwork,
            masterFingerprint = normalized.masterFingerprint,
            derivationPath = normalized.derivationPath,
            hasPassphrase = false,
            identiconBytes = computeIdenticonBytes(publicDescriptor, null)
        )
        walletDao.insert(walletEntity)

        WalletData(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = false,
            isMultisig = false,
            createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs),
            network = activeNetwork,
            masterFingerprint = normalized.masterFingerprint,
            derivationPath = normalized.derivationPath,
            hasPassphrase = false
        )
    }

    override suspend fun syncWallet(walletId: String, config: ElectrumConfig?): WalletBalance = withContext(Dispatchers.IO) {
        // Offline mode — skip sync entirely, return cached balance
        if (settingsManager.isOfflineMode()) {
            android.util.Log.d("BdkRepo", "Offline mode — skipping sync")
            return@withContext getBalance(walletId)
        }

        // Passphrase wallet guard — never sync using the public descriptor (xpub) wallet.
        // Syncing the xpub against Electrum reveals real UTXO/tx history in the locked state,
        // which leaks wallet activity before the passphrase is entered. Only sync after unlock.
        val walletEntityForPassphraseCheck = walletDao.getById(walletId)
        if (walletEntityForPassphraseCheck?.hasPassphrase == true && !unlockedPassphraseWallets.contains(walletId)) {
            android.util.Log.d("BdkRepo", "syncWallet: SKIPPING $walletId — passphrase wallet is locked")
            return@withContext WalletBalance(0, 0, 0, 0)
        }

        // Cross-network guard — don't sync a wallet that belongs to a different network
        val walletEntity = walletEntityForPassphraseCheck
        val currentNetwork = settingsManager.getNetwork()
        if (walletEntity != null && walletEntity.network != currentNetwork) {
            android.util.Log.w("BdkRepo", "syncWallet: SKIPPING $walletId — wallet is ${walletEntity.network} but current network is $currentNetwork")
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

            // Build Electrum connection
            val connectionStr = buildElectrumUrl(effectiveConfig)
            // [S-4] Gate: server connection details are sensitive (host reconnaissance)
            if (logSensitive) {
                android.util.Log.d("BdkRepo", "syncWallet: url=${effectiveConfig.serverUrl}, port=${effectiveConfig.port}, ssl=${effectiveConfig.useSsl}, custom=${effectiveConfig.isCustom}, tor=${effectiveConfig.useTor}, pinnedCert=${effectiveConfig.pinnedCert != null}")
            }

            // Load wallet first (fast, local operation)
            // [S-4] Gate: wallet ID exposure
            if (logSensitive) {
                android.util.Log.d("BdkRepo", "syncWallet: loading wallet $walletId")
            }
            val entry = loadWallet(walletId)
            val wallet = entry.wallet
            android.util.Log.d("BdkRepo", "syncWallet: wallet loaded OK")

            // ElectrumClient constructor is a blocking native call (TCP+SSL handshake).
            // withTimeout cannot interrupt native/blocking calls, so we use a Future with hard timeout.
            val timeoutMs = if (effectiveConfig.isCustom) 60_000L else 30_000L
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            var activeConnection: net.clench.wallet.data.network.ActiveElectrumConnection? = null
            try {
                // Create ElectrumClient via connection factory (handles TLS pinning + Tor relay)
                val resolved = electrumConnectionFactory.resolveConnection(effectiveConfig)
                // [S-4] Gate: connection mode details
                if (logSensitive) {
                    android.util.Log.d("BdkRepo", "syncWallet: creating ElectrumClient mode=${resolved.mode} (timeout=${timeoutMs}ms)")
                }
                val connectFuture = executor.submit(java.util.concurrent.Callable {
                    electrumConnectionFactory.createConnection(effectiveConfig)
                })
                try {
                    activeConnection = connectFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    connectFuture.cancel(true)
                    android.util.Log.e("BdkRepo", "syncWallet: ElectrumClient connect TIMEOUT after ${timeoutMs}ms")
                    throw java.util.concurrent.TimeoutException("ElectrumClient connection timed out after ${timeoutMs}ms")
                } catch (e: java.util.concurrent.ExecutionException) {
                    android.util.Log.e("BdkRepo", "syncWallet: ElectrumClient connect ERROR: ${e.cause?.message}")
                    throw e.cause ?: e
                }
                val electrumClient = activeConnection.client
                android.util.Log.d("BdkRepo", "syncWallet: ElectrumClient created OK (mode=${activeConnection.mode})")

                // Full scan with coroutine timeout (fullScan is also blocking but generally completes)
                withTimeout(timeoutMs) {
                    android.util.Log.d("BdkRepo", "syncWallet: building fullScan request for $walletId")
                    val fullScanRequest = wallet.startFullScan().build()
                    android.util.Log.d("BdkRepo", "syncWallet: starting fullScan (stopGap=20, batch=10)")
                    val update = electrumClient.fullScan(
                        fullScanRequest,
                        stopGap = 20uL,
                        batchSize = 10uL,
                        fetchPrevTxouts = true
                    )
                    android.util.Log.d("BdkRepo", "syncWallet: fullScan complete, applying update")

                    wallet.applyUpdate(update)
                    android.util.Log.d("BdkRepo", "syncWallet: update applied, persisting")

                    wallet.persist(entry.persister)
                    android.util.Log.d("BdkRepo", "syncWallet: persisted OK")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("BdkRepo", "syncWallet: TIMEOUT for $walletId: ${e.message}")
                throw e
            } catch (e: java.util.concurrent.TimeoutException) {
                android.util.Log.e("BdkRepo", "syncWallet: CONNECT TIMEOUT for $walletId: ${e.message}")
                throw e
            } catch (e: Exception) {
                android.util.Log.e("BdkRepo", "syncWallet: ERROR for $walletId: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally {
                try { activeConnection?.close() } catch (_: Exception) {}
                executor.shutdownNow()
                android.util.Log.d("BdkRepo", "syncWallet: cleanup done")
            }

            android.util.Log.d("BdkRepo", "syncWallet: starting tx caching phase")
            // R7-4: Calculate tip height for confirmation count
            // IMPORTANT: Wallet's own transactions are stale for wallets that haven't received funds recently
            // (e.g. a 2022 wallet shows 66 confs instead of ~184,000). Always fetch current tip first.
            val transactions = wallet.transactions()
            // [S-4] Gate: tx count reveals wallet activity level
            if (logSensitive) {
                android.util.Log.d("BdkRepo", "syncWallet: got ${transactions.size} transactions")
            }
            
            // Priority 1: Try mempool.space API for current tip height
            var tipHeight: UInt = 0u
            if (!settingsManager.isOfflineMode()) {
                try {
                    val baseUrl = mempoolApiBaseUrlForActiveNetwork()
                    val heightStr = torAwareHttpClient.fetchText("$baseUrl/api/blocks/tip/height")
                    tipHeight = heightStr.trim().toUInt()
                    android.util.Log.d("BdkRepo", "tipHeight from mempool API: $tipHeight")
                } catch (e: Exception) {
                    android.util.Log.w("BdkRepo", "Failed to get tip height from mempool API: ${e.message}")
                }
            }
            
            // Priority 2: Fall back to wallet's own confirmed transaction heights
            // (last resort - these are stale for old wallets)
            if (tipHeight == 0u) {
                for (canonicalTx in transactions) {
                    val pos = canonicalTx.chainPosition
                    if (pos is ChainPosition.Confirmed) {
                        val h = pos.confirmationBlockTime.blockId.height
                        if (h > tipHeight) tipHeight = h
                    }
                }
                android.util.Log.d("BdkRepo", "tipHeight from wallet txs (fallback): $tipHeight")
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
                        // [S-4] Gate: txid fragments expose wallet activity
                        if (logSensitive) {
                            android.util.Log.d("BdkRepo", "tx ${tx.computeTxid().toString().take(12)}... CONFIRMED height=$txHeight confs=$confs")
                        }
                        Pair(ts, confs)
                    }
                    is ChainPosition.Unconfirmed -> {
                        if (logSensitive) {
                            android.util.Log.d("BdkRepo", "tx ${tx.computeTxid().toString().take(12)}... UNCONFIRMED lastSeen=${pos.timestamp}")
                        }
                        Pair(pos.timestamp?.let { it.toLong() * 1000L }, 0)
                    }
                    else -> {
                        if (logSensitive) {
                            android.util.Log.d("BdkRepo", "tx ${tx.computeTxid().toString().take(12)}... UNKNOWN pos=${pos.javaClass.simpleName}")
                        }
                        Pair(null, 0)
                    }
                }

                // R7-5: Calculate fee if possible (may fail for watch-only wallets)
                val feeSat: Long? = try {
                    wallet.calculateFee(tx).toSat().toLong()
                } catch (_: Exception) {
                    null
                }

                TransactionEntity(
                    txid = tx.computeTxid().toString(),
                    walletId = walletId,
                    amountSat = amount.toLong(),
                    feeSat = feeSat,
                    timestampEpochMs = timestampMs,
                    confirmations = confirmations,
                    direction = direction.name,
                    address = null
                )
            }
            // For watch-only wallets, BDK may report confirmed transactions as Unconfirmed.
            // Fix up using Electrum server batch query (single TCP connection) or cached Room data.
            val walletEntity = walletDao.getById(walletId)
            val isWatchOnly = walletEntity?.isWatchOnly == true
            val unconfirmedTxs = transactionEntities.filter { it.confirmations == 0 }
            val fixedEntities = if (isWatchOnly && unconfirmedTxs.isNotEmpty() && !settingsManager.isOfflineMode()) {
                // Check Room DB first — skip txs we already know are confirmed
                val cachedTxs = transactionDao.getForWallet(walletId).associateBy { it.txid }
                val trulyUnknown = unconfirmedTxs.filter { tx ->
                    val cached = cachedTxs[tx.txid]
                    cached == null || cached.confirmations == 0
                }

                if (trulyUnknown.isNotEmpty()) {
                    // [S-4] Gate: tx count exposure
                    if (logSensitive) {
                        android.util.Log.d("BdkRepo", "Watch-only: ${unconfirmedTxs.size} unconfirmed, ${trulyUnknown.size} need lookup")
                    }
                    // Batch query via raw Electrum protocol (single socket, all txs)
                    val txConfirmations = batchElectrumTxLookup(trulyUnknown.map { it.txid }, connectionStr, tipHeight)
                    transactionEntities.map { txEntity ->
                        val cached = cachedTxs[txEntity.txid]
                        if (txEntity.confirmations == 0 && cached != null && cached.confirmations > 0) {
                            // Use cached confirmation data, update confs relative to current tip
                            txEntity.copy(
                                confirmations = cached.confirmations,
                                timestampEpochMs = cached.timestampEpochMs ?: txEntity.timestampEpochMs
                            )
                        } else if (txEntity.confirmations == 0 && txConfirmations.containsKey(txEntity.txid)) {
                            val (blockHeight, blockTime) = txConfirmations[txEntity.txid]!!
                            val confs = if (tipHeight > 0u && blockHeight > 0L) {
                                (tipHeight.toLong() - blockHeight + 1).toInt().coerceAtLeast(1)
                            } else 1
                            txEntity.copy(
                                confirmations = confs,
                                timestampEpochMs = if (blockTime > 0L) blockTime * 1000L else txEntity.timestampEpochMs
                            )
                        } else txEntity
                    }
                } else {
                    // All unconfirmed txs have cached confirmation data
                    transactionEntities.map { txEntity ->
                        val cached = cachedTxs[txEntity.txid]
                        if (txEntity.confirmations == 0 && cached != null && cached.confirmations > 0) {
                            txEntity.copy(
                                confirmations = cached.confirmations,
                                timestampEpochMs = cached.timestampEpochMs ?: txEntity.timestampEpochMs
                            )
                        } else txEntity
                    }
                }
            } else transactionEntities

            transactionDao.insertAll(fixedEntities)

            // Return balance
            val balance = wallet.balance()
            val txCount = transactions.size
            // [S-4] Gate: balance + tx count expose wallet activity and funds
            if (logSensitive) {
                android.util.Log.d("BdkRepo", "syncWallet: balance confirmed=${balance.confirmed.toSat()} trustedPending=${balance.trustedPending.toSat()} untrustedPending=${balance.untrustedPending.toSat()} immature=${balance.immature.toSat()} txCount=$txCount")
            }
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
        // Passphrase wallets: never return Room-cached transactions when in the locked/in-memory state.
        // The Room transaction cache contains data from previous syncs of the real wallet, which must
        // not be visible before the passphrase is entered (same reason we use in-memory BDK wallets).
        // When unlocked, the cache is repopulated by syncWallet() and we return it normally.
        val walletEntity = walletDao.getById(walletId)
        if (walletEntity?.hasPassphrase == true && !unlockedPassphraseWallets.contains(walletId)) {
            return emptyList()
        }
        // Load labels for this wallet
        val labels = transactionLabelDao.getForWallet(walletId).associateBy { it.txid }
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
                address = entity.address,
                label = labels[entity.txid]?.label
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
        wallet.persist(entry.persister)

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
        feeRateSatPerVbyte: Float,
        utxoTxid: String?,
        utxoVout: UInt?,
        selectedOutpoints: List<String>
    ): String = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val network = activeNetwork()
        val recipientAddress = org.bitcoindevkit.Address(toAddress, network)
        val feeRate = FeeRate.fromSatPerVb(feeRateSatPerVbyte.toULong())

        // BDK 2.x: TxBuilder is immutable — every method returns a NEW builder.
        // Must capture return values or chain calls. Never call methods without reassignment.
        val walletEntity = walletDao.getById(walletId)
        val isPassphraseWallet = walletEntity?.hasPassphrase == true
        val hasManualUtxos = selectedOutpoints.isNotEmpty() || (utxoTxid != null && utxoVout != null)

        // Build transaction - handle drain single UTXO, drain selected UTXOs, drain wallet, or send specific amount
        var builder = when {
            // Drain a specific UTXO only
            amountSat == null && utxoTxid != null && utxoVout != null -> {
                TxBuilder()
                    .addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(utxoTxid), utxoVout))
                    .drainTo(recipientAddress.scriptPubkey())
                    .feeRate(feeRate)
                    .manuallySelectedOnly()
            }
            // Drain specific selected UTXOs
            amountSat == null && selectedOutpoints.isNotEmpty() -> {
                var b = TxBuilder()
                    .drainTo(recipientAddress.scriptPubkey())
                    .feeRate(feeRate)
                for (op in selectedOutpoints) {
                    val parts = op.split(":")
                    if (parts.size == 2) {
                        val txid = parts[0]
                        val vout = parts[1].toUIntOrNull() ?: continue
                        b = b.addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(txid), vout))
                    }
                }
                b.manuallySelectedOnly()
            }
            // Drain whole wallet
            amountSat == null -> {
                TxBuilder()
                    .drainWallet()
                    .drainTo(recipientAddress.scriptPubkey())
                    .feeRate(feeRate)
            }
            // Send specific amount
            else -> {
                TxBuilder()
                    .addRecipient(recipientAddress.scriptPubkey(), Amount.fromSat(amountSat.toULong()))
                    .feeRate(feeRate)
            }
        }

        // If specific UTXOs were selected (coin control), restrict to only those UTXOs
        // Must reassign builder since TxBuilder is immutable in BDK 2.x
        if (amountSat != null && selectedOutpoints.isNotEmpty()) {
            for (op in selectedOutpoints) {
                val parts = op.split(":")
                if (parts.size == 2) {
                    val txid = parts[0]
                    val vout = parts[1].toUIntOrNull() ?: continue
                    builder = builder.addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(txid), vout))
                }
            }
            builder = builder.manuallySelectedOnly()
        } else if (amountSat != null && utxoTxid != null && utxoVout != null) {
            builder = builder.addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(utxoTxid), utxoVout))
            builder = builder.manuallySelectedOnly()
        }

        // Passphrase wallets use in-memory BDK persisters (no persisted chain history),
        // so BDK classifies all their UTXOs as untrustedPending. TxBuilder's default coin
        // selection ignores untrustedPending UTXOs, causing "insufficient funds: 0 btc".
        // Apply the same addUtxo() workaround used for watch-only wallets in createPsbt().
        // Also filter frozen UTXOs when no explicit coin control is active.
        if (!hasManualUtxos) {
            val frozenOutpoints = try {
                utxoMetadataDao.getFrozenForWallet(walletId).map { it.outpoint }.toSet()
            } catch (_: Exception) { emptySet() }
            val needsManualSelection = isPassphraseWallet || frozenOutpoints.isNotEmpty()
            if (needsManualSelection) {
                val utxos = wallet.listUnspent()
                android.util.Log.d("BdkRepo", "buildTransaction: manual UTXO selection (passphrase=$isPassphraseWallet, ${frozenOutpoints.size} frozen)")
                for (utxo in utxos) {
                    val opStr = "${utxo.outpoint.txid}:${utxo.outpoint.vout}"
                    if (!utxo.isSpent && opStr !in frozenOutpoints) {
                        builder = builder.addUtxo(utxo.outpoint)
                    }
                }
                builder = builder.manuallySelectedOnly()
            }
        }

        // Build and sign transaction
        val psbt = builder.finish(wallet)
        wallet.sign(psbt)

        // Extract final transaction and serialize
        val finalTx = psbt.extractTx()
        return@withContext finalTx.serialize().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    override suspend fun broadcastTransaction(config: ElectrumConfig, txHex: String): String = withContext(Dispatchers.IO) {
        if (settingsManager.isOfflineMode()) {
            throw IllegalStateException("Cannot broadcast in offline mode")
        }
        val activeConnection = electrumConnectionFactory.createConnection(config)

        // Parse transaction from hex bytes — BDK 2.x takes ByteArray
        val txBytes = txHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val tx = Transaction(txBytes)

        // Broadcast to network — BDK 2.x returns Txid object
        try {
            return@withContext activeConnection.client.transactionBroadcast(tx).toString()
        } finally {
            activeConnection.close()
        }
    }

    override suspend fun listWallets(): List<WalletData> {
        val network = settingsManager.getNetwork()
        val allWallets = walletDao.getAll()
        val networkWallets = walletDao.getAllByNetwork(network)
        if (logSensitive) {
            android.util.Log.d("BdkRepo", "listWallets: network=$network total=${allWallets.size} forNetwork=${networkWallets.size}")
            allWallets.forEach { w ->
                android.util.Log.d("BdkRepo", "  wallet: id=${w.id.take(8)} name=${w.name} network=${w.network} watchOnly=${w.isWatchOnly}")
            }
        }
        return networkWallets.map { entity ->
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
                hasPassphrase = entity.hasPassphrase,
                masterFingerprint = entity.masterFingerprint,
                derivationPath = entity.derivationPath,
                importedViaDevice = entity.importedViaDevice
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

        // [S-4] Gate: keychain and index info exposure
        if (logSensitive) {
            android.util.Log.d("BdkRepo", "getAddresses: keychain=$keychain lastRevealed=$lastRevealedIndex unused=${unusedAddresses.size}")
        }

        val addresses = mutableListOf<DomainAddress>()
        for (i in 0 until count) {
            val addressInfo = wallet.peekAddress(keychain, i.toUInt())
            val addrStr = addressInfo.address.toString()
            // An address is "used" if it has been revealed and is not in BDK's unused list
            // Edge cases:
            //   - lastRevealedIndex==0 + empty unused = fresh wallet, nothing revealed → not used
            //   - lastRevealedIndex>0 + empty unused = all revealed addresses are used
            //   - address in unusedAddresses = definitively not used
            val nothingRevealed = lastRevealedIndex == 0 && unusedAddresses.isEmpty()
            val isUsed = !nothingRevealed && i <= lastRevealedIndex && addrStr !in unusedAddresses
            // [S-4] Gate: address exposure (even partial addresses reveal wallet activity)
            if (logSensitive && i < 5) {
                android.util.Log.d("BdkRepo", "  addr[$i]=$addrStr revealed=${i <= lastRevealedIndex} inUnused=${addrStr in unusedAddresses} used=$isUsed")
            }
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
            hasPassphrase = entity.hasPassphrase,
            identiconBytes = entity.identiconBytes,
            masterFingerprint = entity.masterFingerprint,
            derivationPath = entity.derivationPath,
            importedViaDevice = entity.importedViaDevice
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

    override suspend fun estimateFees(): FeeEstimates = withContext(Dispatchers.IO) {
        if (settingsManager.isOfflineMode()) {
            // Return conservative defaults in offline mode
            return@withContext FeeEstimates(
                priority = 10f,
                standard = 5f,
                economy = 2f,
                timestamp = System.currentTimeMillis()
            )
        }

        // Try Electrum first
        val electrumFees = try {
            estimateFeesFromElectrum()
        } catch (e: Exception) {
            android.util.Log.w("BdkRepo", "Electrum fee estimation failed: ${e.message}")
            null
        }

        // If Electrum failed, try mempool.space API as fallback
        if (electrumFees == null) {
            try {
                return@withContext estimateFeesFromMempoolSpace()
            } catch (e: Exception) {
                android.util.Log.w("BdkRepo", "Mempool.space fee estimation failed: ${e.message}")
            }
        }

        electrumFees ?: FeeEstimates(
            priority = 10f,
            standard = 5f,
            economy = 2f,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Estimate fees using Electrum's estimatefee method.
     */
    private suspend fun estimateFeesFromElectrum(): FeeEstimates? {
        val config = settingsManager.loadElectrumConfig()
        val activeConnection = electrumConnectionFactory.createConnection(config)
        val electrumClient = activeConnection.client
        return try {
            // BDK's ElectrumClient.estimateFee() takes a target (ULong blocks)
            // and returns a Double representing BTC/kvB.
            // Convert BTC/kvB to sat/vB: multiply by 100_000 (1e8 / 1000).
            val priorityBtcKvb = try {
                electrumClient.estimateFee(1uL)
            } catch (_: Exception) { null }

            val standardBtcKvb = try {
                electrumClient.estimateFee(3uL)
            } catch (_: Exception) { null }

            val economyBtcKvb = try {
                electrumClient.estimateFee(6uL)
            } catch (_: Exception) { null }

            // Convert BTC/kvB → sat/vB: (btc_per_kvb * 1e8) / 1000 = btc_per_kvb * 1e5
            fun btcKvbToSatVb(btcKvb: Double?): Float? {
                if (btcKvb == null || btcKvb <= 0.0) return null
                return (btcKvb * 100_000.0).toFloat()
            }

            val prioritySatVb = btcKvbToSatVb(priorityBtcKvb)
            val standardSatVb = btcKvbToSatVb(standardBtcKvb)
            val economySatVb = btcKvbToSatVb(economyBtcKvb)

            // If all estimates failed, return null to trigger fallback
            if (prioritySatVb == null && standardSatVb == null && economySatVb == null) {
                null
            } else {
                FeeEstimates(
                    priority = (prioritySatVb ?: standardSatVb ?: 10f).coerceAtLeast(1f),
                    standard = (standardSatVb ?: economySatVb ?: 5f).coerceAtLeast(1f),
                    economy = (economySatVb ?: 2f).coerceAtLeast(1f),
                    timestamp = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("BdkRepo", "Electrum fee estimation error: ${e.message}")
            null
        } finally {
            activeConnection.close()
        }
    }

    /**
     * Estimate fees using mempool.space API as fallback.
     * GET https://mempool.space/api/v1/fees/recommended
     * Returns: { fastestFee, halfHourFee, hourFee, economyFee } in sat/vB
     */
    private suspend fun estimateFeesFromMempoolSpace(): FeeEstimates {
        val baseUrl = mempoolApiBaseUrlForActiveNetwork()
        val url = "$baseUrl/api/v1/fees/recommended"

        android.util.Log.d("BdkRepo", "Fetching fees from mempool API: $url")

        val json = torAwareHttpClient.fetchText(url)
        val obj = org.json.JSONObject(json)

        val fastestFee = obj.getDouble("fastestFee").toFloat()
        val halfHourFee = obj.getDouble("halfHourFee").toFloat()
        val hourFee = obj.getDouble("hourFee").toFloat()
        val economyFee = obj.getDouble("economyFee").toFloat()

        // Map mempool.space tiers to our tiers:
        // fastestFee -> priority (next block)
        // halfHourFee -> standard (~30 min)
        // economyFee -> economy (~1 hour)
        // hourFee is ~1 hour but less aggressive than economy
        return FeeEstimates(
            priority = fastestFee.coerceAtLeast(1f),
            standard = halfHourFee.coerceAtLeast(1f),
            economy = economyFee.coerceAtLeast(1f),
            timestamp = System.currentTimeMillis()
        )
    }

    override suspend fun bumpFee(walletId: String, txid: String, newFeeRate: Float): String = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val feeRate = FeeRate.fromSatPerVb(newFeeRate.toLong().toULong())

        val psbt = org.bitcoindevkit.BumpFeeTxBuilder(org.bitcoindevkit.Txid.fromString(txid), feeRate)
            .finish(wallet)

        // Sign the bumped transaction
        wallet.sign(psbt)

        // Persist wallet state
        wallet.persist(entry.persister)

        // Extract and serialize
        val finalTx = psbt.extractTx()
        finalTx.serialize().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    override suspend fun listUnspent(walletId: String): List<net.clench.wallet.domain.model.UtxoInfo> = withContext(Dispatchers.IO) {
        // Passphrase wallet guard — same as getTransactions().
        // Never expose UTXOs from the public descriptor (xpub) wallet in the locked state.
        val walletEntity = walletDao.getById(walletId)
        if (walletEntity?.hasPassphrase == true && !unlockedPassphraseWallets.contains(walletId)) {
            android.util.Log.d("BdkRepo", "listUnspent: passphrase wallet $walletId is locked — returning empty list")
            return@withContext emptyList()
        }

        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val utxos = wallet.listUnspent()
        // [S-4] Gate: UTXO count and unlock status expose wallet balance info
        if (logSensitive) {
            android.util.Log.d("BdkRepo", "listUnspent: walletId=${walletId.take(8)} rawUtxoCount=${utxos.size} unlocked=${unlockedPassphraseWallets.contains(walletId)}")
        }

        // Calculate tip height for confirmation count
        // Priority 1: Try mempool.space API for current tip height
        var tipHeight: UInt = 0u
        if (!settingsManager.isOfflineMode()) {
            try {
                val baseUrl = mempoolApiBaseUrlForActiveNetwork()
                val heightStr = torAwareHttpClient.fetchText("$baseUrl/api/blocks/tip/height")
                tipHeight = heightStr.trim().toUInt()
                android.util.Log.d("BdkRepo", "listUnspent: tipHeight from mempool API: $tipHeight")
            } catch (e: Exception) {
                android.util.Log.w("BdkRepo", "listUnspent: Failed to get tip height from mempool API: ${e.message}")
            }
        }
        
        // Priority 2: Fall back to wallet's own confirmed transaction heights
        if (tipHeight == 0u) {
            val transactions = wallet.transactions()
            for (canonicalTx in transactions) {
                val pos = canonicalTx.chainPosition
                if (pos is ChainPosition.Confirmed) {
                    val h = pos.confirmationBlockTime.blockId.height
                    if (h > tipHeight) tipHeight = h
                }
            }
            android.util.Log.d("BdkRepo", "listUnspent: tipHeight from wallet txs (fallback): $tipHeight")
        }

        // Load frozen outpoints for this wallet
        val frozenOutpoints = try {
            utxoMetadataDao.getFrozenForWallet(walletId).map { it.outpoint }.toSet()
        } catch (e: Exception) {
            android.util.Log.w("BdkRepo", "listUnspent: failed to get frozen UTXOs: ${e.message}")
            emptySet()
        }

        utxos.map { localOutput ->
            val outpoint = localOutput.outpoint
            val txout = localOutput.txout
            val amountSat = txout.value.toSat().toLong()  // Amount → sat → Long

            // Derive address from script pubkey
            val address = try {
                org.bitcoindevkit.Address.Companion.fromScript(txout.scriptPubkey, wallet.network()).toString()
            } catch (_: Exception) { null }

            // Calculate confirmations from chain position
            val confirmations = when (val pos = localOutput.chainPosition) {
                is ChainPosition.Confirmed -> {
                    val txHeight = pos.confirmationBlockTime.blockId.height
                    if (tipHeight >= txHeight) (tipHeight - txHeight + 1u).toInt() else 1
                }
                else -> 0
            }

            val outpointStr = "${outpoint.txid.toString()}:${outpoint.vout}"
            net.clench.wallet.domain.model.UtxoInfo(
                txid = outpoint.txid.toString(),
                vout = outpoint.vout.toUInt(),
                amountSat = amountSat,
                address = address,
                confirmations = confirmations,
                isSpent = localOutput.isSpent,
                keychain = localOutput.keychain.name,
                isFrozen = outpointStr in frozenOutpoints
            )
        }
    }

    override suspend fun createPsbt(
        walletId: String,
        toAddress: String,
        amountSat: Long?,
        feeRateSatPerVbyte: Float,
        utxoTxid: String?,
        utxoVout: UInt?,
        selectedOutpoints: List<String>
    ): String = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val network = activeNetwork()
        val recipientAddress = org.bitcoindevkit.Address(toAddress, network)
        val feeRate = FeeRate.fromSatPerVb(feeRateSatPerVbyte.toLong().toULong())
        val walletEntity = walletDao.getById(walletId)
        val isWatchOnly = walletEntity?.isWatchOnly == true

        // BDK 2.x: TxBuilder is immutable — every method returns a NEW builder.
        // Keep this branch structure in sync with buildTransaction(): selected-UTXO
        // drains must drain only the selected inputs, not start from drainWallet().
        var builder = when {
            // Drain a specific UTXO only
            amountSat == null && utxoTxid != null && utxoVout != null -> {
                TxBuilder()
                    .addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(utxoTxid), utxoVout))
                    .drainTo(recipientAddress.scriptPubkey())
                    .feeRate(feeRate)
                    .manuallySelectedOnly()
            }
            // Drain specific selected UTXOs
            amountSat == null && selectedOutpoints.isNotEmpty() -> {
                var b = TxBuilder()
                    .drainTo(recipientAddress.scriptPubkey())
                    .feeRate(feeRate)
                for (op in selectedOutpoints) {
                    val parts = op.split(":")
                    if (parts.size == 2) {
                        val txid = parts[0]
                        val vout = parts[1].toUIntOrNull() ?: continue
                        b = b.addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(txid), vout))
                    }
                }
                b.manuallySelectedOnly()
            }
            // Drain whole wallet
            amountSat == null -> {
                TxBuilder()
                    .drainWallet()
                    .drainTo(recipientAddress.scriptPubkey())
                    .feeRate(feeRate)
            }
            // Send specific amount
            else -> {
                TxBuilder()
                    .addRecipient(recipientAddress.scriptPubkey(), Amount.fromSat(amountSat.toULong()))
                    .feeRate(feeRate)
            }
        }

        // For watch-only wallets, BDK classifies all UTXOs as untrustedPending
        // which makes them invisible to the default coin selection.
        // Explicitly add all unspent outputs so TxBuilder can use them.
        // Also filter out frozen UTXOs.
        if (isWatchOnly && selectedOutpoints.isEmpty() && utxoTxid == null) {
            val utxos = wallet.listUnspent()
            val frozenOutpoints = try {
                utxoMetadataDao.getFrozenForWallet(walletId).map { it.outpoint }.toSet()
            } catch (e: Exception) {
                android.util.Log.w("BdkRepo", "createPsbt: failed to get frozen UTXOs: ${e.message}")
                emptySet()
            }
            android.util.Log.d("BdkRepo", "createPsbt: watch-only wallet, adding ${utxos.size} UTXOs (${frozenOutpoints.size} frozen/excluded)")
            for (utxo in utxos) {
                val outpointStr = "${utxo.outpoint.txid.toString()}:${utxo.outpoint.vout}"
                if (!utxo.isSpent && outpointStr !in frozenOutpoints) {
                    builder = builder.addUtxo(utxo.outpoint)
                }
            }
        }

        // Optionally restrict to specific UTXOs (coin control)
        // Also filter out frozen UTXOs
        val frozenOutpointsForCoinControl = try {
            if (selectedOutpoints.isEmpty() && utxoTxid == null) {
                // Only fetch frozen list when not using explicit UTXO selection
                utxoMetadataDao.getFrozenForWallet(walletId).map { it.outpoint }.toSet()
            } else emptySet()
        } catch (e: Exception) {
            android.util.Log.w("BdkRepo", "createPsbt: failed to get frozen UTXOs for coin control: ${e.message}")
            emptySet()
        }
        
        if (amountSat != null && selectedOutpoints.isNotEmpty()) {
            for (op in selectedOutpoints) {
                val parts = op.split(":")
                if (parts.size == 2) {
                    val txid = parts[0]
                    val vout = parts[1].toUIntOrNull() ?: continue
                    val outpointStr = "$txid:$vout"
                    // Skip frozen UTXOs
                    if (outpointStr in frozenOutpointsForCoinControl) {
                        android.util.Log.d("BdkRepo", "createPsbt: skipping frozen UTXO $outpointStr")
                        continue
                    }
                    builder = builder.addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(txid), vout))
                }
            }
            builder = builder.manuallySelectedOnly()
        } else if (amountSat != null && utxoTxid != null && utxoVout != null) {
            val outpointStr = "$utxoTxid:$utxoVout"
            if (outpointStr in frozenOutpointsForCoinControl) {
                android.util.Log.w("BdkRepo", "createPsbt: attempted to spend frozen UTXO $outpointStr")
                throw IllegalArgumentException("Cannot spend frozen UTXO")
            }
            builder = builder.addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(utxoTxid), utxoVout))
            builder = builder.manuallySelectedOnly()
        }

        // Include global xpubs in PSBT — hardware wallets use these to verify
        // derivation paths and identify which keys belong to the signing device.
        // Some watch-only descriptors lack key origin info (e.g. bare xpub without
        // [fingerprint/path] prefix), causing MissingKeyOrigin at finish().
        // Try with global xpubs first; if finish() fails, retry without them.
        // Hardware wallets fall back to per-input bip32_derivation fields.
        val psbt = try {
            val builderWithXpubs = builder.addGlobalXpubs()
            builderWithXpubs.finish(wallet)
        } catch (e: Exception) {
            android.util.Log.w("BdkRepo", "createPsbt: build with globalXpubs failed, retrying without: ${e.message?.take(80)}")
            builder.finish(wallet)
        }

        android.util.Log.d("BdkRepo", "createPsbt: built PSBT for ${if (isWatchOnly) "watch-only" else "full"} wallet, base64 len=${psbt.serialize().length}")

        // Log PSBT origin info for debugging — helps verify hardware wallet compatibility
        try {
            val psbtJson = org.json.JSONObject(psbt.jsonSerialize())
            val inputs = psbtJson.optJSONArray("inputs")
            if (inputs != null && inputs.length() > 0) {
                val firstInput = inputs.getJSONObject(0)
                val bip32 = firstInput.optJSONArray("bip32_derivation")
                    ?: firstInput.optJSONArray("bip32_derivations")
                if (bip32 != null && bip32.length() > 0) {
                    android.util.Log.d("BdkRepo", "createPsbt: bip32_derivation[0] = ${bip32.getJSONObject(0)}")
                } else {
                    android.util.Log.d("BdkRepo", "createPsbt: no bip32_derivation in first input")
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("BdkRepo", "createPsbt: could not log bip32_derivation: ${e.message}")
        }

        // Return base64-encoded PSBT
        psbt.serialize()
    }

    override suspend fun buildBatchTransaction(
        walletId: String,
        recipients: List<net.clench.wallet.domain.repository.Recipient>,
        feeRateSatPerVbyte: Float,
        selectedOutpoints: List<String>
    ): String = withContext(Dispatchers.IO) {
        require(recipients.isNotEmpty()) { "At least one recipient is required" }
        // B-3: Defense-in-depth — toULong() wraps negatives to huge numbers; guard here
        recipients.forEach { r ->
            require(r.amountSat > 0) { "Recipient amount must be positive, got ${r.amountSat}" }
        }
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val network = activeNetwork()
        val feeRate = FeeRate.fromSatPerVb(feeRateSatPerVbyte.toLong().toULong())

        // Chain multiple addRecipient() calls — BDK 2.x TxBuilder is immutable
        var builder = TxBuilder().feeRate(feeRate)
        for (r in recipients) {
            val addr = org.bitcoindevkit.Address(r.address, network)
            builder = builder.addRecipient(addr.scriptPubkey(), Amount.fromSat(r.amountSat.toULong()))
        }

        // Coin control: restrict to selected UTXOs if specified
        if (selectedOutpoints.isNotEmpty()) {
            for (op in selectedOutpoints) {
                val parts = op.split(":")
                if (parts.size == 2) {
                    val txid = parts[0]
                    val vout = parts[1].toUIntOrNull() ?: continue
                    builder = builder.addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(txid), vout))
                }
            }
            builder = builder.manuallySelectedOnly()
        }

        // Passphrase wallet workaround + frozen UTXO filtering (B-2)
        // If it's a passphrase wallet OR there are frozen UTXOs, we must iterate
        // and manually select only the non-frozen UTXOs to avoid spending either.
        val walletEntity = walletDao.getById(walletId)
        if (selectedOutpoints.isEmpty()) {
            val frozenOutpoints = try {
                utxoMetadataDao.getFrozenForWallet(walletId).map { it.outpoint }.toSet()
            } catch (_: Exception) { emptySet() }

            val needsManualSelection = (walletEntity?.hasPassphrase == true) || frozenOutpoints.isNotEmpty()
            if (needsManualSelection) {
                val utxos = wallet.listUnspent()
                for (utxo in utxos) {
                    val opStr = "${utxo.outpoint.txid}:${utxo.outpoint.vout}"
                    if (!utxo.isSpent && opStr !in frozenOutpoints) {
                        builder = builder.addUtxo(utxo.outpoint)
                    }
                }
                builder = builder.manuallySelectedOnly()
            }
        }

        val psbt = builder.finish(wallet)
        wallet.sign(psbt)
        val finalTx = psbt.extractTx()
        finalTx.serialize().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    override suspend fun createBatchPsbt(
        walletId: String,
        recipients: List<net.clench.wallet.domain.repository.Recipient>,
        feeRateSatPerVbyte: Float,
        selectedOutpoints: List<String>
    ): String = withContext(Dispatchers.IO) {
        require(recipients.isNotEmpty()) { "At least one recipient is required" }
        // B-3: Defense-in-depth — toULong() wraps negatives to huge numbers; guard here
        recipients.forEach { r ->
            require(r.amountSat > 0) { "Recipient amount must be positive, got ${r.amountSat}" }
        }
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val network = activeNetwork()
        val feeRate = FeeRate.fromSatPerVb(feeRateSatPerVbyte.toLong().toULong())
        val walletEntity = walletDao.getById(walletId)
        val isWatchOnly = walletEntity?.isWatchOnly == true

        var builder = TxBuilder().feeRate(feeRate)
        for (r in recipients) {
            val addr = org.bitcoindevkit.Address(r.address, network)
            builder = builder.addRecipient(addr.scriptPubkey(), Amount.fromSat(r.amountSat.toULong()))
        }

        // Watch-only: explicitly add all unspent outputs
        if (isWatchOnly && selectedOutpoints.isEmpty()) {
            val utxos = wallet.listUnspent()
            val frozenOutpoints = try {
                utxoMetadataDao.getFrozenForWallet(walletId).map { it.outpoint }.toSet()
            } catch (_: Exception) { emptySet() }
            for (utxo in utxos) {
                val opStr = "${utxo.outpoint.txid}:${utxo.outpoint.vout}"
                if (!utxo.isSpent && opStr !in frozenOutpoints) {
                    builder = builder.addUtxo(utxo.outpoint)
                }
            }
        }

        // Coin control
        if (selectedOutpoints.isNotEmpty()) {
            for (op in selectedOutpoints) {
                val parts = op.split(":")
                if (parts.size == 2) {
                    val txid = parts[0]
                    val vout = parts[1].toUIntOrNull() ?: continue
                    builder = builder.addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(txid), vout))
                }
            }
            builder = builder.manuallySelectedOnly()
        }

        val psbt = try {
            val builderWithXpubs = builder.addGlobalXpubs()
            builderWithXpubs.finish(wallet)
        } catch (e: Exception) {
            android.util.Log.w("BdkRepo", "createBatchPsbt: build with globalXpubs failed, retrying without: ${e.message?.take(80)}")
            builder.finish(wallet)
        }
        psbt.serialize()
    }

    override suspend fun applyAndBroadcastPsbt(walletId: String, signedPsbtBase64: String, unsignedPsbtBase64: String): String = withContext(Dispatchers.IO) {
        if (settingsManager.isOfflineMode()) {
            throw IllegalStateException("Cannot broadcast in offline mode")
        }

        // Hardware wallets do not all return the same payload after signing:
        // SeedSigner/Keystone/Passport/Jade generally return a signed PSBT, while
        // COLDCARD can return either a signed PSBT or a finalized transaction
        // (BBQr file type T / .txn), depending on the export path and settings.
        val signedPsbt = parseSignedPsbtPayload(signedPsbtBase64)

        val tx = if (signedPsbt != null) {
            // Finalize the PSBT before comparing the resulting transaction to
            // the original unsigned PSBT. This is stricter than comparing PSBT
            // metadata and covers QR, NFC, and file-import paths uniformly.
            val finalizeResult = signedPsbt.finalize()
            if (!finalizeResult.couldFinalize) {
                val errorMsgs = finalizeResult.errors?.joinToString(", ") { it.toString() } ?: "Unknown error"
                throw IllegalStateException("Could not finalize PSBT: $errorMsgs")
            }
            finalizeResult.psbt.extractTx()
        } else {
            // Not a PSBT; treat it as a finalized raw transaction payload.
            Transaction(decodeTransactionPayload(signedPsbtBase64))
        }

        validateTransactionMatchesUnsignedPsbt(unsignedPsbtBase64, tx)

        val config = settingsManager.loadElectrumConfig()
        val activeConnection = electrumConnectionFactory.createConnection(config)
        try {
            activeConnection.client.transactionBroadcast(tx).toString()
        } finally {
            activeConnection.close()
        }
    }

    private fun decodeTransactionPayload(payload: String): ByteArray {
        val trimmed = payload.trim()
        if (trimmed.matches(Regex("^[0-9a-fA-F]+$")) && trimmed.length % 2 == 0) {
            return trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val decoded = Base64.decode(trimmed, Base64.DEFAULT)
        val decodedText = decoded.toString(Charsets.UTF_8).trim()
        if (decodedText.matches(Regex("^[0-9a-fA-F]+$")) && decodedText.length % 2 == 0) {
            return decodedText.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        if (decodedText.startsWith("02000000") || decodedText.startsWith("01000000") || decodedText.startsWith("psbt", ignoreCase = true)) {
            return Base64.decode(decodedText, Base64.DEFAULT)
        }
        return decoded
    }

    private fun parseSignedPsbtPayload(payload: String): Psbt? {
        for (candidate in psbtBase64Candidates(payload)) {
            try {
                return Psbt(candidate)
            } catch (_: Exception) {
                // Try the next representation. Coldcard/Keystone/Passport file
                // imports may be binary PSBT, base64 text, or a base64-wrapped
                // text file depending on how Android delivered the file/NFC data.
            }
        }
        return null
    }

    private fun psbtBase64Candidates(payload: String): List<String> {
        val candidates = linkedSetOf(payload.trim())
        try {
            val decoded = Base64.decode(payload.trim(), Base64.DEFAULT)
            if (decoded.size >= 5 && decoded[0] == 0x70.toByte() && decoded[1] == 0x73.toByte() && decoded[2] == 0x62.toByte() && decoded[3] == 0x74.toByte()) {
                candidates.add(Base64.encodeToString(decoded, Base64.NO_WRAP))
            }
            val text = decoded.toString(Charsets.UTF_8).trim()
            if (text.isNotBlank()) {
                candidates.add(text)
                if (text.matches(Regex("^[0-9a-fA-F]+$")) && text.length % 2 == 0) {
                    val bytes = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    candidates.add(Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            }
        } catch (_: Exception) {
            // payload was not base64; the original string remains as a candidate
        }
        return candidates.toList()
    }

    /**
     * Validate that the transaction produced by a hardware wallet matches the
     * original unsigned PSBT before broadcasting. Prevents a compromised signer
     * or transport from substituting recipient addresses, amounts, or inputs.
     */
    private fun validateTransactionMatchesUnsignedPsbt(unsignedBase64: String, signedTx: Transaction) {
        val unsigned = Psbt(unsignedBase64)

        val expected = try {
            fingerprintTransaction(unsigned.extractTx())
        } catch (e: Exception) {
            // Some BDK/rust-bitcoin versions refuse to extract a non-final PSBT.
            // Fall back to JSON output validation rather than broadcasting blind.
            android.util.Log.w("BdkRepo", "Unsigned PSBT transaction extraction failed, falling back to JSON output validation: ${e.message}")
            null
        }

        val actual = fingerprintTransaction(signedTx)

        if (expected != null) {
            if (expected.inputs != actual.inputs) {
                throw SecurityException("PSBT tampered: transaction inputs changed")
            }
            compareOutputs(expected.outputs, actual.outputs)
            android.util.Log.d("BdkRepo", "PSBT validation passed: ${actual.outputs.size} outputs and ${actual.inputs.size} inputs match")
            return
        }

        // Last-resort fallback for environments where unsigned.extractTx() is not
        // available. This preserves the old safety behavior: compare output count,
        // amounts, and scriptPubKeys from BDK's PSBT JSON and refuse if unavailable.
        try {
            val unsignedJson = org.json.JSONObject(unsigned.jsonSerialize())
            val unsignedOutputs = unsignedJson.optJSONArray("outputs")
                ?: unsignedJson.optJSONArray("tx_outputs")
                ?: unsignedJson.optJSONObject("unsigned_tx")?.optJSONArray("output")

            if (unsignedOutputs == null) {
                throw SecurityException(
                    "PSBT output validation failed: unable to parse transaction outputs. " +
                    "Refusing to broadcast — re-create the PSBT and try again."
                )
            }

            val expectedOutputs = mutableListOf<OutputFingerprint>()

            for (i in 0 until unsignedOutputs.length()) {
                val uOut = unsignedOutputs.getJSONObject(i)
                val amount = uOut.optLong("value", -1)
                val script = uOut.optString("script_pubkey", "")
                if (amount < 0 || script.isBlank()) {
                    throw SecurityException("PSBT output validation failed: output $i missing amount or script")
                }
                expectedOutputs.add(OutputFingerprint(amount, script.lowercase()))
            }

            compareOutputs(expectedOutputs, actual.outputs)
            android.util.Log.d("BdkRepo", "PSBT validation passed: ${actual.outputs.size} outputs match")
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

    private fun fingerprintTransaction(tx: Transaction): TransactionFingerprint {
        val inputs = tx.input().map { input ->
            val previousOutput = input.previousOutput
            "${previousOutput.txid}:${previousOutput.vout}"
        }
        val outputs = tx.output().map { output ->
            OutputFingerprint(
                valueSat = output.value.toSat().toLong(),
                scriptPubkeyHex = output.scriptPubkey.toBytes().toHexString()
            )
        }
        return TransactionFingerprint(inputs, outputs)
    }

    private fun compareOutputs(expected: List<OutputFingerprint>, actual: List<OutputFingerprint>) {
        if (expected.size != actual.size) {
            throw SecurityException("PSBT tampered: output count changed (${expected.size} → ${actual.size})")
        }
        expected.zip(actual).forEachIndexed { i, (uOut, sOut) ->
            if (uOut.valueSat != sOut.valueSat) {
                throw SecurityException("PSBT tampered: output $i amount changed (${uOut.valueSat} → ${sOut.valueSat})")
            }
            if (uOut.scriptPubkeyHex.lowercase() != sOut.scriptPubkeyHex.lowercase()) {
                throw SecurityException("PSBT tampered: output $i script_pubkey changed")
            }
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Load wallet from cache or SQLite.
     * For signing wallets: retrieves secret descriptors (xprv) from encrypted Keystore.
     * For passphrase wallets (not yet unlocked): uses public descriptors only (watch-only mode).
     * For passphrase wallets (unlocked): uses the cached secret wallet.
     */
    private suspend fun loadWallet(walletId: String): WalletEntry {
        // Check cache first - for unlocked passphrase wallets, the cached entry has secret descriptors
        walletCache[walletId]?.let { return it }

        // Load wallet entity from Room DB
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")

        // R7-2: Use the wallet's stored network, NOT the global setting
        // The wallet's own network is the truth — prevents loading testnet wallet with mainnet network
        val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
        
        // Determine if we can use secret descriptors:
        // - For watch-only: never use secrets
        // - For passphrase wallets: use secrets ONLY if the wallet is unlocked (cached)
        //   If not cached, fall back to public descriptors (user needs to enter passphrase to unlock)
        // - For regular wallets: use secrets if available in keystore
        val isPassphraseWallet = walletEntity.hasPassphrase
        val isUnlocked = walletCache.containsKey(walletId)
        
        val canUseSecrets = !walletEntity.isWatchOnly && (!isPassphraseWallet || isUnlocked)
        
        val externalDescriptorStr = if (canUseSecrets) {
            keystoreManager.getSecretDescriptor(walletId) ?: walletEntity.descriptor
        } else {
            walletEntity.descriptor
        }
        val changeDescriptorStr = if (canUseSecrets) {
            keystoreManager.getSecretChangeDescriptor(walletId) ?: walletEntity.changeDescriptor
        } else {
            walletEntity.changeDescriptor
        }
        val externalDescriptor = Descriptor(externalDescriptorStr, network)
        val changeDescriptor = Descriptor(changeDescriptorStr, network)

        // Passphrase wallets are ALWAYS in-memory only — never load from disk.
        // This prevents stale UTXO/tx data from the real wallet being visible in the locked state
        // (before the passphrase is entered). The public descriptor wallet shares the same walletId
        // as the passphrase-derived wallet, so if we loaded from disk here, real wallet data
        // would be visible without any passphrase. In-memory guarantees a clean slate every time.
        if (isPassphraseWallet) {
            val persister = Persister.newInMemory()
            val wallet = Wallet(externalDescriptor, changeDescriptor, network, persister)
            android.util.Log.d("BdkRepo", "loadWallet: passphrase wallet $walletId — using in-memory persister (locked state)")
            val entry = WalletEntry(wallet, persister)
            walletCache[walletId] = entry
            return entry
        }

        // Non-passphrase wallets: load from SQLite (if exists, else create new)
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)

        val wallet = try {
            Wallet.load(externalDescriptor, changeDescriptor, persister)
        } catch (e: org.bitcoindevkit.LoadWithPersistException.CouldNotLoad) {
            // Wallet DB exists but has no persisted state yet — create fresh BDK wallet
            Wallet(externalDescriptor, changeDescriptor, network, persister)
        } catch (e: org.bitcoindevkit.LoadWithPersistException.Persist) {
            // SQLite not found / unable to open — create fresh BDK wallet
            Wallet(externalDescriptor, changeDescriptor, network, persister)
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
            val freshPersister = Persister.newSqlite(dbPath)
            Wallet(externalDescriptor, changeDescriptor, network, freshPersister)
        }

        // Debug: log descriptor and first address
        // [S-4] Gate: wallet ID and address exposure
        if (logSensitive) {
            android.util.Log.d("BdkRepo", "loadWallet: id=$walletId network=${walletEntity.network} watchOnly=${walletEntity.isWatchOnly}")
            android.util.Log.d("BdkRepo", "loadWallet: descriptor=(redacted)")
            android.util.Log.d("BdkRepo", "loadWallet: changeDesc=(redacted)")
            try {
                val addr0 = wallet.revealAddressesTo(org.bitcoindevkit.KeychainKind.EXTERNAL, 0u)
                android.util.Log.d("BdkRepo", "loadWallet: addr[0]=${addr0}")
            } catch (e: Exception) {
                android.util.Log.w("BdkRepo", "loadWallet: could not derive addr[0]: ${e.message}")
            }
        }

        // Cache and return
        val entry = WalletEntry(wallet, persister)
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

    // Normalize watch-only input into descriptors + extracted origin info.
    // Handles zpub, ypub, xpub, and full descriptor strings.
    private fun normalizeDescriptor(input: String): NormalizedDescriptor {
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
            // Extract origin info from full descriptor
            val originRegexInner = Regex("""\[([0-9a-fA-F]{8})/([^\]]+)\]""")
            val originMatchInner = originRegexInner.find(external)
            return NormalizedDescriptor(
                externalDescriptor = external,
                changeDescriptor = change,
                masterFingerprint = originMatchInner?.groupValues?.get(1)?.uppercase(),
                derivationPath = originMatchInner?.groupValues?.get(2)?.replace("h", "'")
            )
        }

        // Key with origin info: [fingerprint/path]zpub... or [fingerprint/path]xpub...
        // Standard format exported by hardware wallets (Coldcard, SeedSigner, Keystone, etc.)
        val originRegex = Regex("^(\\[[0-9a-fA-F]{8}/[^\\]]+\\])(.+)")
        val originMatch = originRegex.find(input)
        if (originMatch != null) {
            val origin = originMatch.groupValues[1]  // e.g., [D3E95C19/84'/0'/0']
            val keyPart = originMatch.groupValues[2]  // e.g., zpub6qvYv2g...

            // Reject multisig keys with origin — need full descriptor
            if (keyPart.startsWith("Zpub") || keyPart.startsWith("Ypub")) {
                throw IllegalArgumentException(
                    "Multisig extended keys (Zpub/Ypub) are not supported. " +
                    "Please provide a full descriptor for multisig wallets, e.g. wsh(multi(2,xpub1.../0/*,xpub2.../0/*))"
                )
            }

            // Convert key and determine script type
            val (originXpub, originScriptType) = when {
                keyPart.startsWith("zpub") -> Pair(convertZpubToXpub(keyPart), "wpkh")
                keyPart.startsWith("ypub") -> Pair(convertZpubToXpub(keyPart), "sh_wpkh")
                keyPart.startsWith("xpub") -> Pair(keyPart, "wpkh")
                keyPart.startsWith("tpub") -> Pair(keyPart, "wpkh")
                keyPart.startsWith("vpub") -> Pair(convertZpubToXpub(keyPart), "wpkh")
                keyPart.startsWith("upub") -> Pair(convertZpubToXpub(keyPart), "sh_wpkh")
                else -> Pair(keyPart, "wpkh")
            }

            // Extract fingerprint and path from the origin bracket
            val fpPathRegex = Regex("""\[([0-9a-fA-F]{8})/([^\]]+)\]""")
            val fpPathMatch = fpPathRegex.find(origin)
            val extractedFp = fpPathMatch?.groupValues?.get(1)?.uppercase()
            val extractedPath = fpPathMatch?.groupValues?.get(2)?.replace("h", "'")

            return when (originScriptType) {
                "sh_wpkh" -> NormalizedDescriptor(
                    externalDescriptor = "sh(wpkh(${origin}${originXpub}/0/*))",
                    changeDescriptor = "sh(wpkh(${origin}${originXpub}/1/*))",
                    masterFingerprint = extractedFp,
                    derivationPath = extractedPath
                )
                else -> NormalizedDescriptor(
                    externalDescriptor = "wpkh(${origin}${originXpub}/0/*)",
                    changeDescriptor = "wpkh(${origin}${originXpub}/1/*)",
                    masterFingerprint = extractedFp,
                    derivationPath = extractedPath
                )
            }
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

        // Bare keys don't have origin info (no fingerprint/path)
        return when (scriptType) {
            "sh_wpkh" -> NormalizedDescriptor(
                externalDescriptor = "sh(wpkh($xpub/0/*))",
                changeDescriptor = "sh(wpkh($xpub/1/*))"
            )
            else -> NormalizedDescriptor(
                externalDescriptor = "wpkh($xpub/0/*)",
                changeDescriptor = "wpkh($xpub/1/*)"
            )
        }
    }

    private fun containsPrivateKeyMaterial(input: String): Boolean {
        val lower = input.lowercase()
        return listOf("xprv", "yprv", "zprv", "tprv", "uprv", "vprv").any { lower.contains(it) }
    }

    private fun mempoolApiBaseUrlForActiveNetwork(): String {
        val baseUrl = settingsManager.getMempoolUrl().trim().trimEnd('/')
        if (!settingsManager.isTestnet()) return baseUrl

        val lower = baseUrl.lowercase()
        return if (lower.endsWith("/testnet") || lower.contains("/testnet/")) {
            baseUrl
        } else {
            "$baseUrl/testnet"
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

    /**
     * Compute the 8-byte identicon hash for a wallet.
     * Uses SHA-256(masterFingerprint + passphrase) — same algorithm as
     * CreateWalletViewModel.computeFingerprint(), so the visual matches
     * what the user saw during wallet creation.
     *
     * Stored in Room so the wallet info screen can reproduce the identicon
     * without needing the passphrase (which is intentionally never stored).
     */
    private fun computeIdenticonBytes(publicDescriptor: String, passphrase: String?): ByteArray? {
        val masterFpMatch = Regex("\\[([0-9a-fA-F]{8})/").find(publicDescriptor) ?: return null
        val hex = masterFpMatch.groupValues[1]
        val masterFpBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val input = masterFpBytes + (passphrase ?: "").toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input)
        return digest.sliceArray(0 until 8)
    }

    /**
     * Batch lookup transaction confirmations via raw Electrum protocol.
     * Uses a single TCP/SSL connection to query all txids at once.
     * Returns a map of txid -> Pair(blockHeight, blockTimestamp) for confirmed txs.
     */
    private fun batchElectrumTxLookup(
        txids: List<String>,
        connectionStr: String,
        tipHeight: UInt
    ): Map<String, Pair<Long, Long>> {
        if (txids.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, Pair<Long, Long>>()
        try {
            val config = settingsManager.loadElectrumConfig()
            android.util.Log.d("BdkRepo", "batchElectrumTxLookup: connecting for ${txids.size} txids")
            android.util.Log.d("BdkRepo", "batchElectrumTxLookup: first txid=${txids.firstOrNull()} len=${txids.firstOrNull()?.length}")

            val socket = electrumConnectionFactory.createRawSocket(config)
            socket.soTimeout = 30_000

            val writer = java.io.PrintWriter(java.io.BufferedWriter(java.io.OutputStreamWriter(socket.getOutputStream())), true)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))

            // Send all requests at once (batch)
            for ((idx, txid) in txids.withIndex()) {
                val request = """{"id":$idx,"method":"blockchain.transaction.get","params":["$txid",true]}"""
                writer.println(request)
            }

            // Read all responses
            val responses = mutableMapOf<Int, org.json.JSONObject>()
            repeat(txids.size) {
                val line = reader.readLine() ?: return@repeat
                try {
                    val obj = org.json.JSONObject(line)
                    responses[obj.getInt("id")] = obj
                } catch (_: Exception) {}
            }

            android.util.Log.d("BdkRepo", "batchElectrumTxLookup: got ${responses.size} responses for ${txids.size} requests")
            // Parse results
            for ((idx, txid) in txids.withIndex()) {
                val resp = responses[idx]
                if (resp == null) {
                    android.util.Log.w("BdkRepo", "batchElectrumTxLookup: no response for idx=$idx txid=${txid.take(12)}")
                    continue
                }
                val error = resp.optJSONObject("error")
                if (error != null) {
                    android.util.Log.w("BdkRepo", "batchElectrumTxLookup: error for ${txid.take(12)}: ${error}")
                    continue
                }
                val txResult = resp.optJSONObject("result")
                if (txResult == null) {
                    android.util.Log.w("BdkRepo", "batchElectrumTxLookup: no result object for ${txid.take(12)}: ${resp.toString().take(200)}")
                    continue
                }
                // Electrum verbose tx: "confirmations", "blocktime"/"time", no "blockheight"
                val confs = txResult.optInt("confirmations", 0)
                val blockTime = txResult.optLong("blocktime", 0L)
                    .let { if (it == 0L) txResult.optLong("time", 0L) else it }
                // Derive block height from tip - confirmations + 1
                val blockHeight = if (confs > 0 && tipHeight > 0u) {
                    (tipHeight.toLong() - confs + 1)
                } else 0L
                if (confs > 0) {
                    android.util.Log.d("BdkRepo", "Electrum batch: ${txid.take(12)}... confs=$confs height=$blockHeight time=$blockTime")
                    result[txid] = Pair(blockHeight, blockTime)
                }
            }

            socket.close()
        } catch (e: Exception) {
            android.util.Log.w("BdkRepo", "batchElectrumTxLookup failed: ${e.message}")
        }
        return result
    }

    /**
     * Simple HTTP GET helper for mempool.space and price API queries.
     */
    private fun fetchUrl(url: String, connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 10_000): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    // ========== Multisig Wallet Methods ==========

    override suspend fun createMultisigWallet(
        name: String,
        threshold: Int,
        signerXpubs: List<String>
    ): WalletData = withContext(Dispatchers.IO) {
        val network = activeNetwork()
        require(threshold in 1..signerXpubs.size) {
            "Threshold must be between 1 and the number of signers (${signerXpubs.size})"
        }
        require(signerXpubs.size in 2..7) {
            "Number of signers must be between 2 and 7"
        }

        // Build the sortedmulti descriptor fragments for external (receive) and change
        // Each xpub should already include origin info: [fingerprint/48'/0'/0'/2']xpub...
        // We append /0/* for external and /1/* for change
        val externalKeys = signerXpubs.joinToString(",") { xpub ->
            val trimmed = xpub.trim()
            // If the xpub already ends with /0/* or /1/*, use as-is for external
            if (trimmed.endsWith("/0/*") || trimmed.endsWith("/1/*")) {
                trimmed.replace("/1/*", "/0/*")
            } else {
                "$trimmed/0/*"
            }
        }
        val changeKeys = signerXpubs.joinToString(",") { xpub ->
            val trimmed = xpub.trim()
            if (trimmed.endsWith("/0/*") || trimmed.endsWith("/1/*")) {
                trimmed.replace("/0/*", "/1/*")
            } else {
                "$trimmed/1/*"
            }
        }

        val externalDescriptorStr = "wsh(sortedmulti($threshold,$externalKeys))"
        val changeDescriptorStr = "wsh(sortedmulti($threshold,$changeKeys))"

        android.util.Log.d("BdkRepo", "createMultisigWallet: external=$externalDescriptorStr")
        android.util.Log.d("BdkRepo", "createMultisigWallet: change=$changeDescriptorStr")

        // Parse descriptors through BDK to validate
        val externalDescriptor = try {
            Descriptor(externalDescriptorStr, network)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid multisig descriptor: ${e.message}")
        }
        val changeDescriptor = try {
            Descriptor(changeDescriptorStr, network)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid multisig change descriptor: ${e.message}")
        }

        // Prevent duplicate imports
        val activeNetwork = if (network == Network.TESTNET) "testnet" else "mainnet"
        val existing = walletDao.getAllByNetwork(activeNetwork)
        if (existing.any { it.descriptor == externalDescriptor.toString() }) {
            throw IllegalArgumentException("A wallet with this multisig configuration already exists.")
        }

        // Generate wallet ID and create BDK wallet
        val walletId = UUID.randomUUID().toString()
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)
        val wallet = Wallet(externalDescriptor, changeDescriptor, network, persister)
        walletCache[walletId] = WalletEntry(wallet, persister)

        // Persist wallet metadata — multisig wallets are always watch-only (no private keys)
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = externalDescriptor.toString(),
            changeDescriptor = changeDescriptor.toString(),
            isWatchOnly = true,
            isMultisig = true,
            createdAtEpochMs = System.currentTimeMillis(),
            network = activeNetwork
        )
        walletDao.insert(walletEntity)

        WalletData(
            id = walletId,
            name = name,
            descriptor = externalDescriptor.toString(),
            changeDescriptor = changeDescriptor.toString(),
            isWatchOnly = true,
            isMultisig = true,
            createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs),
            network = activeNetwork
        )
    }

    // ========== Passphrase Wallet Methods ==========

    /**
     * Unlock a passphrase wallet by deriving secret descriptors from stored mnemonic + passphrase.
     * Caches the fully-functional wallet (with signing capability) in walletCache.
     * 
     * @throws IllegalArgumentException if wallet not found or passphrase is incorrect
     */
    override suspend fun unlockPassphraseWallet(walletId: String, passphrase: String): Unit = withContext(Dispatchers.IO) {
        android.util.Log.d("BdkRepo", "unlockPassphraseWallet: starting for $walletId")
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        
        if (!walletEntity.hasPassphrase) {
            throw IllegalArgumentException("This wallet does not use a passphrase")
        }

        // Get mnemonic from keystore
        val mnemonicStr = keystoreManager.getMnemonic(walletId)
        android.util.Log.d("BdkRepo", "unlockPassphraseWallet: mnemonic ${if (mnemonicStr != null) "found" else "NOT FOUND"}")
        if (mnemonicStr == null) throw IllegalStateException("Mnemonic not found for wallet: $walletId")
        
        val mnemonic = Mnemonic.fromString(mnemonicStr)
        val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
        
        // Derive secret descriptors with the passphrase
        val secretKey = DescriptorSecretKey(network, mnemonic, passphrase)
        val scriptType = ScriptType.fromDescriptor(walletEntity.descriptor)
        android.util.Log.d("BdkRepo", "unlockPassphraseWallet: scriptType=$scriptType network=$network")
        val externalDescriptor: Descriptor
        val changeDescriptor: Descriptor
        try {
            externalDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.EXTERNAL, network)
            changeDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.INTERNAL, network)
        } finally {
            // H-1: Destroy sensitive BDK objects after descriptor derivation
            try { mnemonic.destroy() } catch (_: Exception) {}
            try { secretKey.destroy() } catch (_: Exception) {}
        }
        
        // Duress wallet design: any passphrase is silently accepted.
        // We derive whatever wallet the passphrase produces and open it.
        // No comparison against stored descriptor — comparing would break plausible deniability.
        // The user identifies the correct wallet by recognising the fingerprint/identicon.
        //
        // Option C — In-memory only wallet for passphrase sessions:
        // Passphrase-derived wallets are NEVER persisted to disk. Each session uses a fresh
        // in-memory SQLite persister via Persister.newInMemory(). This means:
        //   - A decoy wallet (wrong passphrase) starts empty and stays empty unless synced
        //   - No on-disk DB file exists that could reveal whether the real wallet was accessed
        //   - All session data is discarded when lockPassphraseWallet() is called
        //   - A full Electrum sync is required each session (correct — no cached state)
        // This is the correct threat model for a duress/plausible-deniability wallet.
        val persister = Persister.newInMemory()
        val wallet = Wallet(externalDescriptor, changeDescriptor, network, persister)

        // Cache the in-memory wallet for this session and mark as explicitly unlocked.
        // unlockedPassphraseWallets is the authoritative unlock signal — walletCache alone
        // is not sufficient because loadWallet() pre-populates it with the public-xpub wallet.
        walletCache[walletId] = WalletEntry(wallet, persister)
        unlockedPassphraseWallets.add(walletId)
        
        android.util.Log.d("BdkRepo", "unlockPassphraseWallet: unlocked wallet $walletId")
    }

    /**
     * Lock a passphrase wallet by evicting the cached secret wallet.
     * The public-key-only version remains available for viewing balance/addresses.
     */
    override fun lockPassphraseWallet(walletId: String) {
        // Remove from unlock tracking set first
        unlockedPassphraseWallets.remove(walletId)
        // Close and discard the in-memory wallet — all session data is destroyed
        walletCache.remove(walletId)?.let { entry ->
            // BDK 2.x: Wallet and Persister resources released by GC/Drop
        }
        // Wipe Room transaction cache — it contains real wallet tx history which must not be
        // visible before the passphrase is entered next session.
        kotlinx.coroutines.runBlocking {
            try { transactionDao.deleteForWallet(walletId) } catch (_: Exception) {}
        }
        // Delete the on-disk wallet DB so the locked state shows no cached UTXOs or balance.
        // The public descriptor (xpub) is preserved in Room — addresses can always be re-derived.
        // On next unlock, a fresh in-memory wallet is created and synced from Electrum.
        // This prevents stale UTXO data from leaking through the public descriptor wallet
        // which could reveal real wallet activity even before the passphrase is entered.
        try {
            val dbFile = context.getDatabasePath("wallet_${walletId}.db")
            dbFile.delete()
            java.io.File(dbFile.path + "-wal").delete()
            java.io.File(dbFile.path + "-shm").delete()
            java.io.File(dbFile.path + "-journal").delete()
            android.util.Log.d("BdkRepo", "lockPassphraseWallet: deleted on-disk DB for $walletId")
        } catch (e: Exception) {
            android.util.Log.w("BdkRepo", "lockPassphraseWallet: failed to delete DB: ${e.message}")
        }
        android.util.Log.d("BdkRepo", "lockPassphraseWallet: locked and discarded in-memory wallet $walletId")
    }

    /**
     * Check if a passphrase wallet is currently unlocked (secret wallet cached).
     */
    override fun isPassphraseWalletUnlocked(walletId: String): Boolean {
        return unlockedPassphraseWallets.contains(walletId)
    }

    // ========== Transaction Label Methods ==========

    override suspend fun setTransactionLabel(walletId: String, txid: String, label: String) {
        if (label.isBlank()) {
            transactionLabelDao.delete(walletId, txid)
        } else {
            transactionLabelDao.upsert(
                TransactionLabelEntity(
                    key = "$walletId:$txid",
                    walletId = walletId,
                    txid = txid,
                    label = label.trim(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun getTransactionLabel(walletId: String, txid: String): String? {
        return transactionLabelDao.getByTxid(walletId, txid)?.label
    }

    /**
     * Compute the fingerprint bytes for a given passphrase (without unlocking).
     * Used for live fingerprint display during passphrase entry.
     * 
     * @return Pair(identiconBytes [8], masterFingerprintBytes [4]) or null on error
     */
    suspend fun getPassphraseFingerprint(walletId: String, passphrase: String): Pair<ByteArray, ByteArray>? = withContext(Dispatchers.IO) {
        try {
            val walletEntity = walletDao.getById(walletId)
                ?: return@withContext null
            
            val mnemonicStr = keystoreManager.getMnemonic(walletId)
                ?: return@withContext null
            
            val mnemonic = Mnemonic.fromString(mnemonicStr)
            val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
            
            val secretKey = DescriptorSecretKey(network, mnemonic, passphrase)
            val scriptType = ScriptType.fromDescriptor(walletEntity.descriptor)
            val descriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.EXTERNAL, network)
            
            // Extract master fingerprint from descriptor
            val masterFpMatch = Regex("\\[([0-9a-fA-F]{8})/").find(descriptor.toString())
                ?: return@withContext null
            val hex = masterFpMatch.groupValues[1]
            val masterFpBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            
            // Compute identicon bytes
            val input = masterFpBytes + passphrase.toByteArray(Charsets.UTF_8)
            val identiconBytes = java.security.MessageDigest.getInstance("SHA-256").digest(input).sliceArray(0 until 8)
            
            Pair(identiconBytes, masterFpBytes)
        } catch (e: Exception) {
            android.util.Log.w("BdkRepo", "getPassphraseFingerprint failed: ${e.message}")
            null
        }
    }

}
