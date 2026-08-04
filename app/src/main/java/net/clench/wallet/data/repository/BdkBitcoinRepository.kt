package net.clench.wallet.data.repository

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.BoundedBlockingCall
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.domain.model.ScriptType
import net.clench.wallet.data.local.dao.TransactionDao
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.dao.AddressBookDao
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
import net.clench.wallet.domain.repository.BuiltTransactionReview
import net.clench.wallet.domain.repository.GeneratedMultisigPhoneSigner
import net.clench.wallet.domain.repository.MultisigPhoneSignerSecret
import net.clench.wallet.domain.repository.PsbtSigningProgress
import net.clench.wallet.domain.repository.TransactionReviewOutput
import net.clench.wallet.domain.repository.WalletStateRecoveryResult
import net.clench.wallet.domain.repository.WalletStateRecoveryPolicy
import net.clench.wallet.security.ExternalSignaturePolicy
import net.clench.wallet.security.PsbtSafety
import net.clench.wallet.security.WalletMnemonicGenerator
import net.clench.wallet.security.readTextBounded
import org.bitcoindevkit.Amount
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.DerivationPath
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class WalletStateRecoveryRequiredException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)

internal object WalletStateQuarantinePolicy {
    fun validateId(walletId: String, quarantineId: String) {
        require(quarantineId.startsWith("${walletId.take(12)}-")) {
            "Quarantine does not belong to this wallet"
        }
        require(Regex("^[A-Za-z0-9-]{1,80}$").matches(quarantineId)) {
            "Invalid quarantine identifier"
        }
    }

    fun matches(quarantineId: String, fileName: String): Boolean =
        fileName.startsWith("$quarantineId-")
}

internal object MultisigAccountKeyPolicy {
    fun normalizeGeneratedAccountKey(rawKey: String): String = rawKey.trim()
        .removeSuffix("/0/*")
        .removeSuffix("/1/*")
        .removeSuffix("/**")
        .removeSuffix("/*")
}

internal data class MultisigPsbtInputSize(
    val witnessScript: ByteArray,
    val partialSignatureSizes: List<Int>
)

internal object MultisigPsbtVsizeEstimator {
    private const val MAX_ECDSA_SIGNATURE_WITH_SIGHASH_BYTES = 73

    fun estimateFinalVsize(unsignedWeight: Long, inputs: List<MultisigPsbtInputSize>): Long? {
        val witnessSizes = inputs.map(::estimateSortedMultiWitnessSize)
        if (witnessSizes.any { it == null }) return null

        val finalWeight = unsignedWeight + 2L + witnessSizes.sumOf { it!! }
        return (finalWeight + 3L) / 4L
    }

    private fun estimateSortedMultiWitnessSize(input: MultisigPsbtInputSize): Long? {
        val requiredSignatures = requiredSignatureCount(input.witnessScript) ?: return null
        val signatureSizes = input.partialSignatureSizes.take(requiredSignatures) +
            List((requiredSignatures - input.partialSignatureSizes.size).coerceAtLeast(0)) {
                MAX_ECDSA_SIGNATURE_WITH_SIGHASH_BYTES
            }
        val itemSizes = buildList {
            add(0) // CHECKMULTISIG's historical dummy stack item.
            addAll(signatureSizes)
            add(input.witnessScript.size)
        }
        return serializedWitnessSize(itemSizes)
    }

    private fun requiredSignatureCount(script: ByteArray): Int? {
        if (script.size < 3 || script.last().toInt() and 0xff != 0xae) return null
        val opcode = script.first().toInt() and 0xff
        return if (opcode in 0x51..0x60) opcode - 0x50 else null
    }

    private fun serializedWitnessSize(itemSizes: List<Int>): Long =
        compactSizeLength(itemSizes.size.toLong()) + itemSizes.sumOf { itemSize ->
            compactSizeLength(itemSize.toLong()) + itemSize.toLong()
        }

    private fun compactSizeLength(value: Long): Long = when {
        value < 0xfd -> 1L
        value <= 0xffff -> 3L
        value <= 0xffff_ffffL -> 5L
        else -> 9L
    }
}

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
    private val addressBookDao: AddressBookDao,
    private val keystoreManager: KeystoreManager,
    private val settingsManager: SettingsManager,
    private val electrumConnectionFactory: net.clench.wallet.data.network.ElectrumConnectionFactory,
    private val torAwareHttpClient: TorAwareHttpClient,
    private val walletMnemonicGenerator: WalletMnemonicGenerator,
    private val operationBarrier: SensitiveWalletOperationBarrier
) : BitcoinRepository {

    private val maxHttpResponseChars = 2 * 1024 * 1024
    private val recoveryScanTimeoutMs = 5 * 60_000L

    private data class TransactionFingerprint(
        val version: Int,
        val lockTime: Long,
        val inputs: List<String>,
        val sequences: List<Long>,
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
    private class WalletEntry(val wallet: Wallet, val persister: Persister) {
        val closeState = NativeWalletResourceCleanup.CloseState(this)
    }

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
    private val walletCacheGuard = Any()
    private val evictionTicketGuard = Any()
    private var currentEvictionTicket: SensitiveWalletOperationBarrier.Ticket? = null
    private val failedNativeCloseEntries: MutableSet<WalletEntry> =
        Collections.newSetFromMap(IdentityHashMap())

    private suspend fun <T> withSensitiveWalletOperation(
        block: suspend (SensitiveWalletOperationBarrier.Lease) -> T
    ): T = operationBarrier.withLease(block)

    /**
     * Fail closed on destruction of any secret-bearing native wrapper.
     *
     * Every action is attempted. Failed wrappers are kept strongly reachable and never retried;
     * all later sensitive admission is permanently denied until Android starts a new process.
     */
    private fun closeSecretNativeResources(
        vararg actions: NativeWalletResourceCleanup.CloseAction?
    ) {
        operationBarrier.closeNativeResourcesOrFail(actions.filterNotNull())
    }

    /** Construct a pair without leaking the first descriptor if the second constructor fails. */
    private inline fun createDescriptorPair(
        createExternal: () -> Descriptor,
        createChange: () -> Descriptor
    ): Pair<Descriptor, Descriptor> {
        var external: Descriptor? = null
        var change: Descriptor? = null
        try {
            external = createExternal()
            change = createChange()
            return checkNotNull(external) to checkNotNull(change)
        } catch (failure: Throwable) {
            closeSecretNativeResources(
                nativeCloseAction(change) { it.close() },
                nativeCloseAction(external) { it.close() }
            )
            throw failure
        }
    }

    /**
     * Build a Wallet/Persister pair while retaining ownership locally until descriptor cleanup
     * has succeeded. Any partial-construction or first-close failure attempts every later close.
     */
    private inline fun createWalletEntryFromDescriptors(
        descriptors: Pair<Descriptor, Descriptor>,
        createPersister: () -> Persister,
        createWallet: (Descriptor, Descriptor, Persister) -> Wallet
    ): WalletEntry {
        val (external, change) = descriptors
        var persister: Persister? = null
        var wallet: Wallet? = null
        try {
            persister = createPersister()
            wallet = createWallet(external, change, persister)
        } catch (failure: Throwable) {
            closeSecretNativeResources(
                nativeCloseAction(wallet) { it.close() },
                nativeCloseAction(persister) { it.close() },
                nativeCloseAction(change) { it.close() },
                nativeCloseAction(external) { it.close() }
            )
            throw failure
        }

        if (!operationBarrier.attemptCloseNativeResources(
                listOfNotNull(
                    nativeCloseAction(change) { it.close() },
                    nativeCloseAction(external) { it.close() }
                )
            )
        ) {
            // The entry was not transferred to cache because descriptor cleanup was unverified.
            // Attempt and quarantine both remaining wrappers before exposing the fatal outcome.
            operationBarrier.attemptCloseNativeResources(
                listOfNotNull(
                    nativeCloseAction(wallet) { it.close() },
                    nativeCloseAction(persister) { it.close() }
                )
            )
            throw WalletCacheRestartRequiredException()
        }
        return WalletEntry(checkNotNull(wallet), checkNotNull(persister))
    }

    private fun closeDescriptorPair(descriptors: Pair<Descriptor, Descriptor>) {
        closeSecretNativeResources(
            nativeCloseAction(descriptors.second) { it.close() },
            nativeCloseAction(descriptors.first) { it.close() }
        )
    }

    private fun closeWalletEntry(
        entry: WalletEntry,
        lease: SensitiveWalletOperationBarrier.Lease
    ) {
        operationBarrier.assertActive(lease)
        val closeFailures = NativeWalletResourceCleanup.closeAll(
            entries = listOf(entry.closeState),
            closeWallet = { it.wallet.close() },
            closePersister = { it.persister.close() }
        )
        synchronized(walletCacheGuard) {
            if (entry.closeState.fullyClosed) failedNativeCloseEntries.remove(entry)
            else failedNativeCloseEntries.add(entry)
        }
        if (closeFailures != 0 || !entry.closeState.fullyClosed) {
            operationBarrier.markFailedRestartRequiredFromOperation()
            throw WalletCacheRestartRequiredException()
        }
    }

    private fun cacheWallet(
        walletId: String,
        entry: WalletEntry,
        lease: SensitiveWalletOperationBarrier.Lease
    ) {
        operationBarrier.assertActive(lease)
        synchronized(walletCacheGuard) {
            operationBarrier.assertActive(lease)
            walletCache.put(walletId, entry)?.let { closeWalletEntry(it, lease) }
        }
    }

    private fun cacheWalletIfAbsent(
        walletId: String,
        entry: WalletEntry,
        lease: SensitiveWalletOperationBarrier.Lease
    ): WalletEntry {
        operationBarrier.assertActive(lease)
        return synchronized(walletCacheGuard) {
            operationBarrier.assertActive(lease)
            val existing = walletCache.putIfAbsent(walletId, entry)
            if (existing != null) closeWalletEntry(entry, lease)
            existing ?: entry
        }
    }

    private fun evictWallet(
        walletId: String,
        lease: SensitiveWalletOperationBarrier.Lease
    ) {
        operationBarrier.assertActive(lease)
        synchronized(walletCacheGuard) {
            operationBarrier.assertActive(lease)
            walletCache.remove(walletId)?.let { closeWalletEntry(it, lease) }
        }
    }

    private fun cachedWallet(
        walletId: String,
        lease: SensitiveWalletOperationBarrier.Lease
    ): WalletEntry? = synchronized(walletCacheGuard) {
        operationBarrier.assertActive(lease)
        walletCache[walletId]
    }

    private fun isWalletCached(
        walletId: String,
        lease: SensitiveWalletOperationBarrier.Lease
    ): Boolean = synchronized(walletCacheGuard) {
        operationBarrier.assertActive(lease)
        walletCache.containsKey(walletId)
    }

    private fun markPassphraseWalletUnlocked(
        walletId: String,
        lease: SensitiveWalletOperationBarrier.Lease
    ) = synchronized(walletCacheGuard) {
        operationBarrier.assertActive(lease)
        unlockedPassphraseWallets.add(walletId)
    }

    private fun markPassphraseWalletLocked(
        walletId: String,
        lease: SensitiveWalletOperationBarrier.Lease
    ) = synchronized(walletCacheGuard) {
        operationBarrier.assertActive(lease)
        unlockedPassphraseWallets.remove(walletId)
    }

    private fun isPassphraseWalletMarkedUnlocked(walletId: String): Boolean =
        synchronized(walletCacheGuard) {
            unlockedPassphraseWallets.contains(walletId)
        }

    /** Immediately close admission before waiting on any wallet, DAO, or filesystem operation. */
    fun beginSensitiveSessionEviction() {
        val ticket = operationBarrier.beginDrain()
        synchronized(evictionTicketGuard) {
            currentEvictionTicket = ticket
        }
    }

    /** Re-open access only after every native and privacy-cache cleanup pass succeeded. */
    fun allowSensitiveSessionAccess() {
        val ticket = synchronized(evictionTicketGuard) {
            checkNotNull(currentEvictionTicket)
        }
        val emptyStateVerified = synchronized(walletCacheGuard) {
            walletCache.isEmpty() &&
                unlockedPassphraseWallets.isEmpty() &&
                failedNativeCloseEntries.isEmpty() &&
                !operationBarrier.hasQuarantinedNativeResources()
        }
        if (!emptyStateVerified) {
            operationBarrier.markFailedRestartRequired(ticket)
            throw WalletCacheRestartRequiredException()
        }
        try {
            operationBarrier.reopen(ticket)
        } catch (_: Throwable) {
            operationBarrier.markFailedRestartRequired(ticket)
            throw WalletCacheRestartRequiredException()
        }
        synchronized(evictionTicketGuard) { currentEvictionTicket = null }
    }

    /**
     * Dispose every cached native wallet/persister when the app leaves the foreground.
     *
     * This security boundary is deliberately non-cancellable. Cache insertions are rejected
     * while eviction is active, all previously admitted operations drain before native handles
     * are detached, and unlock markers are cleared in the same critical section as the cache.
     * Every wallet and persister close is attempted before a sanitized failure is exposed.
     */
    suspend fun completeSensitiveSessionEviction() = withContext(NonCancellable + Dispatchers.IO) {
        val ticket = synchronized(evictionTicketGuard) {
            checkNotNull(currentEvictionTicket)
        }
        val restartAlreadyRequired = operationBarrier.awaitAndMarkEvicting(ticket)

        val entries = synchronized(walletCacheGuard) {
            val detached = (walletCache.values + failedNativeCloseEntries).distinct()
            walletCache.clear()
            failedNativeCloseEntries.clear()
            unlockedPassphraseWallets.clear()
            detached
        }
        val closeFailures = NativeWalletResourceCleanup.closeAll(
            entries = entries.map { it.closeState },
            closeWallet = { it.wallet.close() },
            closePersister = { it.persister.close() }
        )
        val remaining = entries.filterNot { it.closeState.fullyClosed }
        if (remaining.isNotEmpty()) {
            synchronized(walletCacheGuard) { failedNativeCloseEntries.addAll(remaining) }
        }
        val nativeRestartRequired = restartAlreadyRequired ||
            closeFailures != 0 || remaining.isNotEmpty()
        if (nativeRestartRequired) {
            operationBarrier.markFailedRestartRequired(ticket)
        } else {
            operationBarrier.markSecured(ticket)
        }

        // Room rows and on-disk passphrase caches are independent cleanup passes. Attempt them
        // even after an unverifiable native close, then preserve restart-required as the dominant
        // result so this process can never retry the failed native resource.
        var failureCount = 0
        val allWallets = runCatching {
            walletDao.getAll()
        }.getOrElse {
            failureCount++
            emptyList()
        }

        val passphraseWallets = allWallets.filter { it.hasPassphrase }

        passphraseWallets.forEach { wallet ->
            runCatching { transactionDao.deleteForWallet(wallet.id) }
                .onFailure { failureCount++ }
            runCatching {
                val database = context.getDatabasePath("wallet_${wallet.id}.db")
                check(PassphraseWalletCacheCleanup.deleteAndFindRemaining(database).isEmpty())
            }.onFailure { failureCount++ }
        }

        // Constructor/process-death orphans are not represented in Room and otherwise survive
        // forever with public descriptors, owned scripts, balances, and transaction history.
        // Run this on every cold/background cleanup while admission is closed.
        if (allWallets.isNotEmpty() || failureCount == 0) {
            runCatching {
                val databaseDirectory = checkNotNull(
                    context.getDatabasePath("wallet_probe.db").parentFile
                )
                check(
                    WalletDatabaseOrphanCleanup.deleteAndFindRemaining(
                        databaseDirectory = databaseDirectory,
                        knownWalletIds = allWallets.mapTo(mutableSetOf()) { it.id }
                    ).isEmpty()
                )
            }.onFailure { failureCount++ }
        }

        if (nativeRestartRequired) throw WalletCacheRestartRequiredException()
        if (failureCount != 0) throw WalletCacheSecurityCleanupException()
    }

    private fun discardFailedWalletCreation(
        walletId: String,
        lease: SensitiveWalletOperationBarrier.Lease
    ) {
        var nativeRestartRequired = false
        var cleanupFailed = false
        try {
            evictWallet(walletId, lease)
        } catch (_: WalletCacheRestartRequiredException) {
            // Continue attempting every independent secret/file cleanup, but never reopen this
            // process after an unverifiable native close.
            nativeRestartRequired = true
        }
        runCatching { keystoreManager.deleteWalletSecrets(walletId) }
            .onFailure { cleanupFailed = true }
        val dbFile = context.getDatabasePath("wallet_${walletId}.db")
        listOf(
            dbFile,
            java.io.File(dbFile.path + "-wal"),
            java.io.File(dbFile.path + "-shm"),
            java.io.File(dbFile.path + "-journal")
        ).forEach { file ->
            runCatching { check(!file.exists() || file.delete()) }
                .onFailure { cleanupFailed = true }
        }
        if (nativeRestartRequired) throw WalletCacheRestartRequiredException()
        if (cleanupFailed) throw WalletCacheSecurityCleanupException()
    }

    // Tracks passphrase wallets that have been explicitly unlocked via unlockPassphraseWallet().
    // walletCache is NOT a reliable unlock signal — loadWallet() pre-populates it with a
    // public-xpub in-memory wallet even in the locked state. This set is the authoritative
    // source of truth for whether a passphrase wallet is unlocked.
    private val unlockedPassphraseWallets = ConcurrentHashMap.newKeySet<String>()

    // R7-1: Per-wallet operation mutex. BDK Wallet/Persister objects are native,
    // mutable, and shared by sync, address, review, signing, and recovery paths.
    // Serialize every operation that reads or mutates those objects.
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
    ): Pair<List<String>, WalletData> = withSensitiveWalletOperation { lease ->
        withContext(Dispatchers.IO) {
        // Use the already-displayed/verified mnemonic when provided. Otherwise preserve the
        // legacy repository behavior of generating a fresh mnemonic for direct repository callers.
        var mnemonic: Mnemonic? = null
        var secretKey: DescriptorSecretKey? = null
        var descriptors: Pair<Descriptor, Descriptor>? = null
        val network = activeNetwork()
        try {
            mnemonic = if (mnemonicWords != null) {
                Mnemonic.fromString(mnemonicWords.joinToString(" "))
            } else {
                walletMnemonicGenerator.generate(wordCount)
            }
            val walletMnemonicWords = mnemonicWords ?: mnemonic.toString().split(" ")
            secretKey = DescriptorSecretKey(network, mnemonic, passphrase ?: "")
            descriptors = createDescriptorPair(
                createExternal = {
                    ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.EXTERNAL, network)
                },
                createChange = {
                    ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.INTERNAL, network)
                }
            )
            val externalDescriptor = descriptors.first
            val changeDescriptor = descriptors.second
            val publicDescriptor = externalDescriptor.toString()
            val publicChangeDescriptor = changeDescriptor.toString()
            val secretDescriptor = if (passphrase.isNullOrBlank()) externalDescriptor.toStringWithSecret() else null
            val secretChangeDescriptor = if (passphrase.isNullOrBlank()) changeDescriptor.toStringWithSecret() else null

            // Generate wallet ID
            val walletId = UUID.randomUUID().toString()

            // Create BDK wallet with SQLite persistence. This helper consumes/closes descriptors.
            val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
            val ownedDescriptors = checkNotNull(descriptors)
            descriptors = null
            val entry = createWalletEntryFromDescriptors(
                descriptors = ownedDescriptors,
                createPersister = { Persister.newSqlite(dbPath) },
                createWallet = { external, change, persister ->
                    Wallet(external, change, network, persister)
                }
            )
            cacheWallet(walletId, entry, lease)

            try {
            // Commit the mnemonic and, for non-passphrase wallets, both private
            // descriptors in one durable encrypted-preferences transaction.
            keystoreManager.storeWalletSecrets(
                walletId = walletId,
                mnemonic = walletMnemonicWords.joinToString(" "),
                secretDescriptor = secretDescriptor,
                secretChangeDescriptor = secretChangeDescriptor
            )

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
            } catch (e: Exception) {
                discardFailedWalletCreation(walletId, lease)
                throw e
            }
        } finally {
            descriptors?.let(::closeDescriptorPair)
            closeSecretNativeResources(
                nativeCloseAction(secretKey) { it.destroy() },
                nativeCloseAction(mnemonic) { it.destroy() }
            )
        }
        }
    }

    override suspend fun importWallet(
        name: String,
        mnemonic: List<String>,
        passphrase: String?,
        scriptType: ScriptType
    ): WalletData = withSensitiveWalletOperation { lease ->
        withContext(Dispatchers.IO) {
        val network = activeNetwork()
        var mnemonicObj: Mnemonic? = null
        var secretKey: DescriptorSecretKey? = null
        var descriptors: Pair<Descriptor, Descriptor>? = null
        try {
            mnemonicObj = Mnemonic.fromString(mnemonic.joinToString(" "))
            secretKey = DescriptorSecretKey(network, mnemonicObj, passphrase ?: "")
            descriptors = createDescriptorPair(
                createExternal = {
                    ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.EXTERNAL, network)
                },
                createChange = {
                    ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.INTERNAL, network)
                }
            )
            val externalDescriptor = descriptors.first
            val changeDescriptor = descriptors.second

            // Prevent duplicate imports — compare using public descriptor
            val publicDescriptor = externalDescriptor.toString()
            val publicChangeDescriptor = changeDescriptor.toString()
            val secretDescriptor = externalDescriptor.toStringWithSecret()
            val secretChangeDescriptor = changeDescriptor.toStringWithSecret()
            val existing = walletDao.getAll()
            if (existing.any { it.descriptor == publicDescriptor }) {
                throw IllegalArgumentException("This seed phrase is already imported in your wallet list.")
            }

            val walletId = UUID.randomUUID().toString()

            val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
            val ownedDescriptors = checkNotNull(descriptors)
            descriptors = null
            val entry = createWalletEntryFromDescriptors(
                descriptors = ownedDescriptors,
                createPersister = { Persister.newSqlite(dbPath) },
                createWallet = { external, change, persister ->
                    Wallet(external, change, network, persister)
                }
            )
            cacheWallet(walletId, entry, lease)

            try {
            keystoreManager.storeWalletSecrets(
                walletId = walletId,
                mnemonic = mnemonic.joinToString(" "),
                secretDescriptor = secretDescriptor.takeIf { passphrase.isNullOrBlank() },
                secretChangeDescriptor = secretChangeDescriptor.takeIf { passphrase.isNullOrBlank() }
            )

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
            } catch (e: Exception) {
                discardFailedWalletCreation(walletId, lease)
                throw e
            }
        } finally {
            descriptors?.let(::closeDescriptorPair)
            closeSecretNativeResources(
                nativeCloseAction(secretKey) { it.destroy() },
                nativeCloseAction(mnemonicObj) { it.destroy() }
            )
        }
        }
    }

    override suspend fun importWatchOnly(
        name: String,
        descriptor: String,
        deviceType: String?
    ): WalletData = withSensitiveWalletOperation { lease ->
        withContext(Dispatchers.IO) {
        // [S-4] Gate: wallet name and import details
        if (logSensitive) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "importWatchOnly: name=$name input=(redacted)")
        }
        // Normalize input — handle bare zpub/ypub/xpub and full descriptor strings
        val normalized = normalizeDescriptor(descriptor.trim())
        val externalDescriptorStr = normalized.externalDescriptor
        val changeDescriptorStr = normalized.changeDescriptor
        val isMultisigDescriptor = isMultisigDescriptor(externalDescriptorStr)
        if (logSensitive) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "importWatchOnly: normalized external descriptor (redacted)")
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "importWatchOnly: origin fingerprint=${normalized.masterFingerprint} path=${normalized.derivationPath} device=$deviceType")
        }

        val network = activeNetwork()
        if (logSensitive) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "importWatchOnly: network=$network")
        }
        var descriptors: Pair<Descriptor, Descriptor>? = try {
            createDescriptorPair(
                createExternal = { Descriptor(externalDescriptorStr, network) },
                createChange = { Descriptor(changeDescriptorStr, network) }
            )
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "importWatchOnly: descriptor invalid")
            throw IllegalArgumentException("Invalid descriptor or extended public key. Please check the format and try again.\n\nDetails: ${e.message}")
        }
        try {
        val externalDescriptor = checkNotNull(descriptors).first
        val changeDescriptor = checkNotNull(descriptors).second
        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()

        // Prevent duplicate imports — check current network only
        val existing = walletDao.getAllByNetwork(settingsManager.getNetwork())
        if (existing.any { it.descriptor == publicDescriptor }) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "importWatchOnly: duplicate descriptor found")
            closeDescriptorPair(checkNotNull(descriptors))
            descriptors = null
            throw IllegalArgumentException("A wallet with this descriptor is already in your wallet list.")
        }

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence (no signing keys)
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val ownedDescriptors = checkNotNull(descriptors)
        descriptors = null
        val entry = createWalletEntryFromDescriptors(
            descriptors = ownedDescriptors,
            createPersister = { Persister.newSqlite(dbPath) },
            createWallet = { external, change, persister ->
                Wallet(external, change, network, persister)
            }
        )
        cacheWallet(walletId, entry, lease)

        // Persist wallet metadata to Room DB (isWatchOnly = true)
        val activeNetwork = settingsManager.getNetwork()
        val walletEntity = WalletEntity(
            id = walletId,
            name = name,
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = true,
            isMultisig = isMultisigDescriptor,
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
            descriptor = publicDescriptor,
            changeDescriptor = publicChangeDescriptor,
            isWatchOnly = true,
            isMultisig = isMultisigDescriptor,
            createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs),
            network = activeNetwork,
            preferredHardwareWallet = deviceType,
            masterFingerprint = normalized.masterFingerprint,
            derivationPath = normalized.derivationPath,
            importedViaDevice = deviceType
        )
        } finally {
            descriptors?.let(::closeDescriptorPair)
        }
        }
    }

    override suspend fun convertWatchOnlyToHot(
        walletId: String,
        mnemonic: List<String>,
        passphrase: String?
    ): Unit = withSensitiveWalletOperation { lease ->
        withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found")
        if (!walletEntity.isWatchOnly) {
            throw IllegalArgumentException("This wallet already has signing capability")
        }
        if (
            walletEntity.isMultisig ||
            isMultisigDescriptor(walletEntity.descriptor) ||
            isMultisigDescriptor(walletEntity.changeDescriptor)
        ) {
            throw IllegalArgumentException("Seed phrase conversion is not available for multisig wallets")
        }
        if (mnemonic.size != 12 && mnemonic.size != 24) {
            throw IllegalArgumentException("Enter a 12 or 24 word seed phrase")
        }

        val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
        var mnemonicObj: Mnemonic? = null
        var secretKey: DescriptorSecretKey? = null
        var descriptors: Pair<Descriptor, Descriptor>? = null
        try {
            mnemonicObj = Mnemonic.fromString(mnemonic.joinToString(" "))
            val passphraseValue = passphrase.orEmpty()
            secretKey = DescriptorSecretKey(network, mnemonicObj, passphraseValue)
            val scriptType = ScriptType.fromDescriptor(walletEntity.descriptor)
            descriptors = createDescriptorPair(
                createExternal = {
                    ScriptType.createDescriptor(
                        checkNotNull(secretKey), scriptType, KeychainKind.EXTERNAL, network
                    )
                },
                createChange = {
                    ScriptType.createDescriptor(
                        checkNotNull(secretKey), scriptType, KeychainKind.INTERNAL, network
                    )
                }
            )
            val externalDescriptor = checkNotNull(descriptors).first
            val changeDescriptor = checkNotNull(descriptors).second

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

            val hasPassphrase = !passphrase.isNullOrBlank()
            keystoreManager.storeWalletSecrets(
                walletId = walletId,
                mnemonic = mnemonic.joinToString(" "),
                secretDescriptor = if (!hasPassphrase) externalDescriptor.toStringWithSecret() else null,
                secretChangeDescriptor = if (!hasPassphrase) changeDescriptor.toStringWithSecret() else null
            )

            walletDao.setWatchOnlyAndPassphrase(walletId, isWatchOnly = false, hasPassphrase = hasPassphrase)

            // Evict the public-only cached wallet so future signing loads the secret descriptors.
            evictWallet(walletId, lease)
            if (hasPassphrase) {
                val ownedDescriptors = checkNotNull(descriptors)
                descriptors = null
                val entry = createWalletEntryFromDescriptors(
                    descriptors = ownedDescriptors,
                    createPersister = { Persister.newInMemory() },
                    createWallet = { external, change, persister ->
                        Wallet(external, change, network, persister)
                    }
                )
                cacheWallet(walletId, entry, lease)
                markPassphraseWalletUnlocked(walletId, lease)
            }
        } finally {
            descriptors?.let(::closeDescriptorPair)
            closeSecretNativeResources(
                nativeCloseAction(secretKey) { it.destroy() },
                nativeCloseAction(mnemonicObj) { it.destroy() }
            )
        }
        }
        }
    }

    override suspend fun importPrivateDescriptor(
        name: String,
        descriptor: String
    ): WalletData = withSensitiveWalletOperation { lease ->
        withContext(Dispatchers.IO) {
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
        var descriptors: Pair<Descriptor, Descriptor>? = try {
            createDescriptorPair(
                createExternal = { Descriptor(externalDescriptorStr, network) },
                createChange = { Descriptor(changeDescriptorStr, network) }
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid private descriptor. Please check the format and try again.\n\nDetails: ${e.message}")
        }
        try {
        val externalDescriptor = checkNotNull(descriptors).first
        val changeDescriptor = checkNotNull(descriptors).second

        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val secretDescriptor = externalDescriptor.toStringWithSecret()
        val secretChangeDescriptor = changeDescriptor.toStringWithSecret()
        val isMultisigDescriptor = isMultisigDescriptor(publicDescriptor)

        // Prevent duplicate imports — compare public descriptors on the current network only.
        val activeNetwork = settingsManager.getNetwork()
        val existing = walletDao.getAllByNetwork(activeNetwork)
        if (existing.any { it.descriptor == publicDescriptor }) {
            closeDescriptorPair(checkNotNull(descriptors))
            descriptors = null
            throw IllegalArgumentException("A wallet with this descriptor is already in your wallet list.")
        }

        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with secret descriptors so this wallet can sign.
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val ownedDescriptors = checkNotNull(descriptors)
        descriptors = null
        val entry = createWalletEntryFromDescriptors(
            descriptors = ownedDescriptors,
            createPersister = { Persister.newSqlite(dbPath) },
            createWallet = { external, change, persister ->
                Wallet(external, change, network, persister)
            }
        )
        cacheWallet(walletId, entry, lease)

        try {
            keystoreManager.storeWalletSecrets(
                walletId = walletId,
                secretDescriptor = secretDescriptor,
                secretChangeDescriptor = secretChangeDescriptor
            )

            val walletEntity = WalletEntity(
                id = walletId,
                name = name,
                descriptor = publicDescriptor,
                changeDescriptor = publicChangeDescriptor,
                isWatchOnly = false,
                isMultisig = isMultisigDescriptor,
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
                isMultisig = isMultisigDescriptor,
                createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs),
                network = activeNetwork,
                masterFingerprint = normalized.masterFingerprint,
                derivationPath = normalized.derivationPath,
                hasPassphrase = false
            )
        } catch (e: Exception) {
            discardFailedWalletCreation(walletId, lease)
            throw e
        }
        } finally {
            descriptors?.let(::closeDescriptorPair)
        }
        }
    }

    override suspend fun syncWallet(walletId: String, config: ElectrumConfig?): WalletBalance =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        // Offline mode — skip sync entirely, return cached balance
        if (settingsManager.isOfflineMode()) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "Offline mode — skipping sync")
            return@withContext getBalanceUnderLease(walletId, lease)
        }

        // Passphrase wallet guard — never sync using the public descriptor (xpub) wallet.
        // Syncing the xpub against Electrum reveals real UTXO/tx history in the locked state,
        // which leaks wallet activity before the passphrase is entered. Only sync after unlock.
        val walletEntityForPassphraseCheck = walletDao.getById(walletId)
        if (walletEntityForPassphraseCheck?.hasPassphrase == true && !isPassphraseWalletMarkedUnlocked(walletId)) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: SKIPPING $walletId — passphrase wallet is locked")
            return@withContext WalletBalance(0, 0, 0, 0)
        }

        // Cross-network guard — don't sync a wallet that belongs to a different network
        val walletEntity = walletEntityForPassphraseCheck
        val currentNetwork = settingsManager.getNetwork()
        if (walletEntity != null && walletEntity.network != currentNetwork) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "syncWallet: SKIPPING $walletId — wallet is ${walletEntity.network} but current network is $currentNetwork")
            return@withContext getBalanceUnderLease(walletId, lease)
        }

        // R7-1: Per-wallet mutex — serialize syncs to prevent concurrent BDK access.
        // Do not return stale data when another sync is already running; screens opened
        // immediately after passphrase unlock need the completed scan before showing
        // address usage and balances.
        val mutex = syncMutex(walletId)
        if (mutex.isLocked && net.clench.wallet.BuildConfig.DEBUG) {
            android.util.Log.d("BdkRepo", "syncWallet: already syncing $walletId, waiting")
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
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: url=${effectiveConfig.serverUrl}, port=${effectiveConfig.port}, ssl=${effectiveConfig.useSsl}, custom=${effectiveConfig.isCustom}, tor=${effectiveConfig.useTor}, pinnedCert=${effectiveConfig.pinnedCert != null}")
            }

            // Load wallet first (fast, local operation)
            // [S-4] Gate: wallet ID exposure
            if (logSensitive) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: loading wallet $walletId")
            }
            val entry = loadWallet(walletId, lease)
            val wallet = entry.wallet
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: wallet loaded OK")

            // ElectrumClient constructor is a blocking native call (TCP+SSL handshake).
            // withTimeout cannot interrupt native/blocking calls, so we use a Future with hard timeout.
            val timeoutMs = if (effectiveConfig.isCustom) 60_000L else 30_000L
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            var activeConnection: net.clench.wallet.data.network.ActiveElectrumConnection? = null
            var fullScanBuilder: org.bitcoindevkit.FullScanRequestBuilder? = null
            var fullScanRequest: org.bitcoindevkit.FullScanRequest? = null
            var scanUpdate: org.bitcoindevkit.Update? = null
            try {
                // Create ElectrumClient via connection factory (handles TLS pinning + Tor relay)
                val resolved = electrumConnectionFactory.resolveConnection(effectiveConfig)
                // [S-4] Gate: connection mode details
                if (logSensitive) {
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: creating ElectrumClient mode=${resolved.mode} (timeout=${timeoutMs}ms)")
                }
                activeConnection = BoundedBlockingCall.awaitResource(
                    executor = executor,
                    timeoutMs = timeoutMs,
                    operation = "Electrum connection",
                    create = { electrumConnectionFactory.createConnection(effectiveConfig) },
                    close = { it.close() },
                    onCloseFailure = operationBarrier::quarantineNativeResource
                )
                val electrumClient = activeConnection.client
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: ElectrumClient created OK (mode=${activeConnection.mode})")

                // fullScan is a blocking native call, so a coroutine timeout cannot
                // pre-empt it. Run it on the dedicated executor and close its
                // transport before cancellation if the hard deadline expires.
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: building fullScan request for $walletId")
                fullScanBuilder = wallet.startFullScan()
                fullScanRequest = fullScanBuilder.build()
                val request = fullScanRequest
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: starting fullScan (stopGap=20, batch=10)")
                val scanFuture = executor.submit(java.util.concurrent.Callable {
                    electrumClient.fullScan(
                        request,
                        stopGap = 20uL,
                        batchSize = 10uL,
                        fetchPrevTxouts = true
                    )
                })
                scanUpdate = BoundedBlockingCall.await(
                    future = scanFuture,
                    timeoutMs = timeoutMs,
                    operation = "Electrum full scan",
                    onTimeout = { activeConnection.cancelTransport() }
                )
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: fullScan complete, applying update")

                wallet.applyUpdate(scanUpdate)
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: update applied, persisting")

                wallet.persist(entry.persister)
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: persisted OK")
            } catch (e: java.util.concurrent.TimeoutException) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "syncWallet: TIMEOUT for $walletId: ${e.message}")
                throw e
            } catch (e: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "syncWallet: ERROR for $walletId: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally {
                activeConnection?.cancelTransport()
                BoundedBlockingCall.shutdownAndAwaitTermination(
                    executor = executor,
                    operation = "Electrum wallet sync worker",
                    onTerminationStalled = operationBarrier::markFailedRestartRequiredFromOperation
                )
                operationBarrier.closeNativeResourcesOrFail(
                    listOfNotNull(
                        nativeCloseAction(scanUpdate) { it.close() },
                        nativeCloseAction(fullScanRequest) { it.close() },
                        nativeCloseAction(fullScanBuilder) { it.close() },
                        nativeCloseAction(activeConnection) { it.close() }
                    )
                )
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: cleanup done")
            }

            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: starting tx caching phase")
            // R7-4: Calculate tip height for confirmation count
            // IMPORTANT: Wallet's own transactions are stale for wallets that haven't received funds recently
            // (e.g. a 2022 wallet shows 66 confs instead of ~184,000). Always fetch current tip first.
            val transactions = wallet.transactions()
            // [S-4] Gate: tx count reveals wallet activity level
            if (logSensitive) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: got ${transactions.size} transactions")
            }
            
            // Priority 1: use the configured Electrum route for current tip height.
            var tipHeight: UInt = currentTipHeightFromElectrum() ?: 0u
            
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
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "tipHeight from wallet txs (fallback): $tipHeight")
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
                            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "tx ${tx.computeTxid().toString().take(12)}... CONFIRMED height=$txHeight confs=$confs")
                        }
                        Pair(ts, confs)
                    }
                    is ChainPosition.Unconfirmed -> {
                        if (logSensitive) {
                            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "tx ${tx.computeTxid().toString().take(12)}... UNCONFIRMED lastSeen=${pos.timestamp}")
                        }
                        Pair(pos.timestamp?.let { it.toLong() * 1000L }, 0)
                    }
                    else -> {
                        if (logSensitive) {
                            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "tx ${tx.computeTxid().toString().take(12)}... UNKNOWN pos=${pos.javaClass.simpleName}")
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
                        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "Watch-only: ${unconfirmedTxs.size} unconfirmed, ${trulyUnknown.size} need lookup")
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
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: balance confirmed=${balance.confirmed.toSat()} trustedPending=${balance.trustedPending.toSat()} untrustedPending=${balance.untrustedPending.toSat()} immature=${balance.immature.toSat()} txCount=$txCount")
            }
            WalletBalance(
                confirmedSat = balance.confirmed.toSat().toLong(),
                trustedPendingSat = balance.trustedPending.toSat().toLong(),
                untrustedPendingSat = balance.untrustedPending.toSat().toLong(),
                immatureSat = balance.immature.toSat().toLong()
            )
        }
        }
    }

    override suspend fun recoverWalletState(walletId: String, stopGap: UInt): WalletStateRecoveryResult =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
            require(WalletStateRecoveryPolicy.isValidStopGap(stopGap)) {
                "Recovery stop gap must be from ${WalletStateRecoveryPolicy.MIN_STOP_GAP} to ${WalletStateRecoveryPolicy.MAX_STOP_GAP}"
            }
            check(!settingsManager.isOfflineMode()) { "Disable offline mode before recovering wallet state" }

            val walletEntity = walletDao.getById(walletId)
                ?: throw IllegalArgumentException("Wallet not found: $walletId")
            check(!walletEntity.hasPassphrase) {
                "Passphrase wallets use ephemeral state; unlock and rescan the passphrase wallet instead"
            }

            syncMutex(walletId).withLock {
                evictWallet(walletId, lease)
                val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
                val dbFile = context.getDatabasePath("wallet_${walletId}.db")
                val originalFiles = listOf(
                    dbFile,
                    java.io.File(dbFile.path + "-wal"),
                    java.io.File(dbFile.path + "-shm"),
                    java.io.File(dbFile.path + "-journal")
                )
                val quarantineDir = java.io.File(context.noBackupFilesDir, "wallet-state-quarantine")
                val recoveryId = "${walletId.take(12)}-${System.currentTimeMillis()}"
                val stateTransaction = WalletStateQuarantineTransaction(
                    originalFiles = originalFiles,
                    quarantineDir = quarantineDir,
                    recoveryId = recoveryId
                )
                var replacementEntry: WalletEntry? = null

                try {
                    stateTransaction.quarantineOriginals()

                    val descriptors = createDescriptorPair(
                        createExternal = { Descriptor(walletEntity.descriptor, network) },
                        createChange = { Descriptor(walletEntity.changeDescriptor, network) }
                    )
                    stateTransaction.markReplacementStateStarted()
                    replacementEntry = createWalletEntryFromDescriptors(
                        descriptors = descriptors,
                        createPersister = { Persister.newSqlite(dbFile.absolutePath) },
                        createWallet = { external, change, persister ->
                            Wallet(external, change, network, persister)
                        }
                    )
                    val wallet = replacementEntry.wallet
                    val persister = replacementEntry.persister
                    val electrumConfig = settingsManager.loadElectrumConfig()
                    val connectionTimeoutMs = if (electrumConfig.isCustom) 60_000L else 30_000L
                    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    var activeConnection: net.clench.wallet.data.network.ActiveElectrumConnection? = null
                    var scanBuilder: org.bitcoindevkit.FullScanRequestBuilder? = null
                    var scanRequest: org.bitcoindevkit.FullScanRequest? = null
                    var scanUpdate: org.bitcoindevkit.Update? = null
                    try {
                        activeConnection = BoundedBlockingCall.awaitResource(
                            executor = executor,
                            timeoutMs = connectionTimeoutMs,
                            operation = "Electrum recovery connection",
                            create = { electrumConnectionFactory.createConnection(electrumConfig) },
                            close = { it.close() },
                            onCloseFailure = operationBarrier::quarantineNativeResource
                        )
                        scanBuilder = wallet.startFullScan()
                        scanRequest = scanBuilder.build()
                        val request = scanRequest
                        val connection = activeConnection
                        val scanFuture = executor.submit(java.util.concurrent.Callable {
                            connection.client.fullScan(
                                request,
                                stopGap = stopGap.toULong(),
                                batchSize = 10uL,
                                fetchPrevTxouts = true
                            )
                        })
                        scanUpdate = BoundedBlockingCall.await(
                            future = scanFuture,
                            timeoutMs = recoveryScanTimeoutMs,
                            operation = "Electrum recovery scan",
                            onTimeout = { connection.cancelTransport() }
                        )
                        wallet.applyUpdate(scanUpdate)
                        wallet.persist(persister)
                    } finally {
                        activeConnection?.cancelTransport()
                        BoundedBlockingCall.shutdownAndAwaitTermination(
                            executor = executor,
                            operation = "Electrum recovery worker",
                            onTerminationStalled = operationBarrier::markFailedRestartRequiredFromOperation
                        )
                        operationBarrier.closeNativeResourcesOrFail(
                            listOfNotNull(
                                nativeCloseAction(scanUpdate) { it.close() },
                                nativeCloseAction(scanRequest) { it.close() },
                                nativeCloseAction(scanBuilder) { it.close() },
                                nativeCloseAction(activeConnection) { it.close() }
                            )
                        )
                    }

                    cacheWallet(walletId, checkNotNull(replacementEntry), lease)
                    replacementEntry = null
                    val balance = wallet.balance()
                    try {
                        WalletStateRecoveryResult(
                            balance = WalletBalance(
                                confirmedSat = balance.confirmed.toSat().toLong(),
                                trustedPendingSat = balance.trustedPending.toSat().toLong(),
                                untrustedPendingSat = balance.untrustedPending.toSat().toLong(),
                                immatureSat = balance.immature.toSat().toLong()
                            ),
                            quarantineId = recoveryId,
                            preservedFileCount = stateTransaction.preservedFileCount,
                            stopGap = stopGap
                        )
                    } finally {
                        closeSecretNativeResources(nativeCloseAction(balance) { it.destroy() })
                    }
                } catch (e: Exception) {
                    evictWallet(walletId, lease)
                    replacementEntry?.let { closeWalletEntry(it, lease) }
                    replacementEntry = null
                    stateTransaction.rollback(e)
                    throw IllegalStateException(
                        "Extended wallet-state recovery scan failed. The original database was restored and no wallet state was deleted.",
                        e
                    )
                }
            }
        }
        }

    override suspend fun deleteWalletStateQuarantine(walletId: String, quarantineId: String): Int =
        withContext(Dispatchers.IO) {
            WalletStateQuarantinePolicy.validateId(walletId, quarantineId)
            val quarantineDir = java.io.File(context.noBackupFilesDir, "wallet-state-quarantine")
            if (!quarantineDir.exists()) return@withContext 0
            val matches = quarantineDir.listFiles().orEmpty().filter { file ->
                file.isFile && WalletStateQuarantinePolicy.matches(quarantineId, file.name)
            }
            matches.forEach { file ->
                check(file.delete()) { "Could not delete preserved recovery file ${file.name}" }
            }
            matches.size
        }

    override suspend fun getBalance(walletId: String): WalletBalance =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
            getBalanceUnderLease(walletId, lease)
        } }

    private suspend fun getBalanceUnderLease(
        walletId: String,
        lease: SensitiveWalletOperationBarrier.Lease
    ): WalletBalance {
        return syncMutex(walletId).withLock {
            val entry = loadWallet(walletId, lease)
            val wallet = entry.wallet
            val balance = wallet.balance()
            WalletBalance(
                confirmedSat = balance.confirmed.toSat().toLong(),
                trustedPendingSat = balance.trustedPending.toSat().toLong(),
                untrustedPendingSat = balance.untrustedPending.toSat().toLong(),
                immatureSat = balance.immature.toSat().toLong()
            )
        }
    }

    override suspend fun getTransactions(walletId: String): List<TransactionItem> =
        withSensitiveWalletOperation { _ ->
        // Passphrase wallets: never return Room-cached transactions when in the locked/in-memory state.
        // The Room transaction cache contains data from previous syncs of the real wallet, which must
        // not be visible before the passphrase is entered (same reason we use in-memory BDK wallets).
        // When unlocked, the cache is repopulated by syncWallet() and we return it normally.
        val walletEntity = walletDao.getById(walletId)
        if (walletEntity?.hasPassphrase == true && !isPassphraseWalletMarkedUnlocked(walletId)) {
            return@withSensitiveWalletOperation emptyList()
        }
        // Load labels for this wallet
        val labels = transactionLabelDao.getForWallet(walletId).associateBy { it.txid }
        transactionDao.getForWallet(walletId).map { entity ->
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

    override suspend fun getLastAddress(walletId: String): DomainAddress =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
            // R7-3: Fixed !! crash — use loadWallet() which always returns a valid entry
            // R7-8: Use nextUnusedAddress() which returns the next unused address without advancing the gap limit
            val entry = loadWallet(walletId, lease)
            val wallet = entry.wallet
            val addressInfo = wallet.nextUnusedAddress(KeychainKind.EXTERNAL)
            DomainAddress(
                address = addressInfo.address.toString(),
                index = addressInfo.index.toInt(),
                used = false
            )
        }
        }
    }

    override suspend fun getReceiveAddress(walletId: String): DomainAddress =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
            val entry = loadWallet(walletId, lease)
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
        }
    }

    override suspend fun buildTransaction(
        walletId: String,
        toAddress: String,
        amountSat: Long?,
        feeRateSatPerVbyte: Float,
        utxoTxid: String?,
        utxoVout: UInt?,
        selectedOutpoints: List<String>
    ): String = withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        val entry = loadWallet(walletId, lease)
        val wallet = entry.wallet
        val network = activeNetwork()
        val recipientAddress = org.bitcoindevkit.Address(toAddress, network)
        val feeRate = validatedFeeRate(feeRateSatPerVbyte)

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
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "buildTransaction: manual UTXO selection (passphrase=$isPassphraseWallet, ${frozenOutpoints.size} frozen)")
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
        return@withContext serializeFinalTransaction(psbt)
        }
        }
    }

    override suspend fun broadcastTransaction(config: ElectrumConfig, txHex: String): String =
        withSensitiveWalletOperation { _ -> withContext(Dispatchers.IO) {
        if (settingsManager.isOfflineMode()) {
            throw IllegalStateException("Cannot broadcast in offline mode")
        }

        // Parse transaction from hex bytes — BDK 2.x takes ByteArray
        val txBytes = txHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val tx = Transaction(txBytes)

        try {
            broadcastTransactionBounded(config, tx)
        } finally {
            closeSecretNativeResources(nativeCloseAction(tx) { it.close() })
        }
        }
    }

    private fun broadcastTransactionBounded(config: ElectrumConfig, tx: Transaction): String {
        val timeoutMs = if (config.isCustom) 60_000L else 30_000L
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var activeConnection: net.clench.wallet.data.network.ActiveElectrumConnection? = null
        try {
            activeConnection = BoundedBlockingCall.awaitResource(
                executor = executor,
                timeoutMs = timeoutMs,
                operation = "Electrum broadcast connection",
                create = { electrumConnectionFactory.createConnection(config) },
                close = { it.close() },
                onCloseFailure = operationBarrier::quarantineNativeResource
            )
            val connection = activeConnection
            val broadcastFuture = executor.submit(java.util.concurrent.Callable {
                connection.client.transactionBroadcast(tx).toString()
            })
            return BoundedBlockingCall.await(
                future = broadcastFuture,
                timeoutMs = timeoutMs,
                operation = "Electrum transaction broadcast",
                onTimeout = { connection.cancelTransport() }
            )
        } finally {
            activeConnection?.cancelTransport()
            BoundedBlockingCall.shutdownAndAwaitTermination(
                executor = executor,
                operation = "Electrum broadcast worker",
                onTerminationStalled = operationBarrier::markFailedRestartRequiredFromOperation
            )
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(nativeCloseAction(activeConnection) { it.close() })
            )
        }
    }

    override suspend fun inspectBuiltTransaction(
        walletId: String,
        txHex: String
    ): BuiltTransactionReview = withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        val txBytes = txHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val tx = Transaction(txBytes)
        try {
            inspectTransaction(walletId, tx, lease = lease)
        } finally {
            closeSecretNativeResources(nativeCloseAction(tx) { it.close() })
        }
        }
        }
    }

    override suspend fun inspectPsbt(
        walletId: String,
        psbtBase64: String
    ): BuiltTransactionReview = withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        PsbtSafety.inspectBase64(psbtBase64)
        val psbt = Psbt(psbtBase64)
        try {
            val tx = psbt.extractTx()
            try {
                val psbtInputs = psbt.input()
                val multisigInputs = psbtInputs.mapNotNull { input ->
                    val witnessScript = input.witnessScript?.toBytes() ?: return@mapNotNull null
                    MultisigPsbtInputSize(
                        witnessScript = witnessScript,
                        partialSignatureSizes = input.partialSigs.values.map(ByteArray::size)
                    )
                }
                val estimatedFinalVsize = if (
                    multisigInputs.size == tx.input().size &&
                    psbtInputs.all { it.finalScriptWitness.isNullOrEmpty() }
                ) {
                    MultisigPsbtVsizeEstimator.estimateFinalVsize(tx.weight().toLong(), multisigInputs)
                } else null
                inspectTransaction(
                    walletId = walletId,
                    tx = tx,
                    lease = lease,
                    knownFeeSat = psbt.fee().toLong(),
                    vsizeOverride = estimatedFinalVsize,
                    vsizeIsEstimate = estimatedFinalVsize != null
                )
            } finally {
                closeSecretNativeResources(nativeCloseAction(tx) { it.close() })
            }
        } finally {
            closeSecretNativeResources(nativeCloseAction(psbt) { it.close() })
        }
        }
        }
    }

    private suspend fun inspectTransaction(
        walletId: String,
        tx: Transaction,
        lease: SensitiveWalletOperationBarrier.Lease,
        knownFeeSat: Long? = null,
        vsizeOverride: Long? = null,
        vsizeIsEstimate: Boolean = false
    ): BuiltTransactionReview {
        val wallet = loadWallet(walletId, lease).wallet
        val feeSat = knownFeeSat ?: wallet.calculateFee(tx).toSat().toLong()
        val vsize = vsizeOverride ?: tx.vsize().toLong()
        return BuiltTransactionReview(
            txid = tx.computeTxid().toString(),
            feeSat = feeSat,
            vsize = vsize,
            feeRateSatPerVbyte = if (vsize > 0) feeSat.toDouble() / vsize else 0.0,
            vsizeIsEstimate = vsizeIsEstimate,
            inputs = tx.input().map { "${it.previousOutput.txid}:${it.previousOutput.vout}" },
            outputs = tx.output().mapIndexed { index, output ->
                TransactionReviewOutput(
                    index = index,
                    amountSat = output.value.toSat().toLong(),
                    address = runCatching {
                        org.bitcoindevkit.Address.fromScript(output.scriptPubkey, wallet.network()).toString()
                    }.getOrNull(),
                    belongsToWallet = wallet.isMine(output.scriptPubkey)
                )
            }
        )
    }

    override suspend fun listWallets(): List<WalletData> {
        val network = settingsManager.getNetwork()
        val allWallets = walletDao.getAll()
        val networkWallets = walletDao.getAllByNetwork(network)
        if (logSensitive) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "listWallets: network=$network total=${allWallets.size} forNetwork=${networkWallets.size}")
            allWallets.forEach { w ->
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "  wallet: id=${w.id.take(8)} name=${w.name} network=${w.network} watchOnly=${w.isWatchOnly}")
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

    override suspend fun deleteWallet(walletId: String) =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        // Remove from cache first
        evictWallet(walletId, lease)

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
        transactionLabelDao.deleteForWallet(walletId)
        utxoMetadataDao.deleteForWallet(walletId)
        addressBookDao.deleteForWallet(walletId)
        walletDao.deleteById(walletId)
        }
        }
    }

    override suspend fun getAddresses(walletId: String, count: Int): List<DomainAddress> = withContext(Dispatchers.IO) {
        getAddresses(walletId, KeychainKind.EXTERNAL, count)
    }

    override suspend fun getAddresses(walletId: String, keychain: KeychainKind, count: Int): List<DomainAddress> =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        val entry = loadWallet(walletId, lease)
        val wallet = entry.wallet

        // Determine "used" addresses by checking BDK's unused address list and
        // synced transaction outputs. Full-scan can discover history for addresses
        // that were not explicitly revealed in this in-memory passphrase session,
        // so transaction outputs are the authoritative fallback for address usage.
        val unusedAddresses = try {
            wallet.listUnusedAddresses(keychain).map { it.address.toString() }.toSet()
        } catch (_: Exception) { emptySet() }
        val transactionOutputAddresses = try {
            wallet.transactions()
                .flatMap { tx ->
                    tx.transaction.output().mapNotNull { txout ->
                        runCatching {
                            org.bitcoindevkit.Address.fromScript(txout.scriptPubkey, wallet.network()).toString()
                        }.getOrNull()
                    }
                }
                .toSet()
        } catch (_: Exception) { emptySet() }

        // Get the last revealed index to know which addresses have been revealed
        val lastRevealedIndex = try {
            wallet.derivationIndex(keychain)?.toInt() ?: 0
        } catch (_: Exception) { 0 }

        // [S-4] Gate: keychain and index info exposure
        if (logSensitive) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "getAddresses: keychain=$keychain lastRevealed=$lastRevealedIndex unused=${unusedAddresses.size}")
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
            val isUsed = addrStr in transactionOutputAddresses ||
                (!nothingRevealed && i <= lastRevealedIndex && addrStr !in unusedAddresses)
            // [S-4] Gate: address exposure (even partial addresses reveal wallet activity)
            if (logSensitive && i < 5) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "  addr[$i]=$addrStr revealed=${i <= lastRevealedIndex} inUnused=${addrStr in unusedAddresses} used=$isUsed")
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
        }
    }

    override suspend fun renameWallet(walletId: String, newName: String) {
        walletDao.updateName(walletId, newName)
    }

    override suspend fun getAccountXpub(walletId: String): String = withContext(Dispatchers.IO) {
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
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "Electrum fee estimation failed: ${e.message}")
            null
        }

        // If Electrum failed, only call external fee APIs when the user opted in.
        if (electrumFees == null) {
            if (settingsManager.isExternalFeeLookupEnabled()) {
                try {
                    return@withContext estimateFeesFromMempoolSpace()
                } catch (e: Exception) {
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "Mempool.space fee estimation failed: ${e.message}")
                }
            } else {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "External fee lookup disabled; using static fee defaults")
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
        val timeoutMs = if (config.isCustom) 60_000L else 30_000L
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var activeConnection: net.clench.wallet.data.network.ActiveElectrumConnection? = null
        return try {
            activeConnection = BoundedBlockingCall.awaitResource(
                executor = executor,
                timeoutMs = timeoutMs,
                operation = "Electrum fee-estimation connection",
                create = { electrumConnectionFactory.createConnection(config) },
                close = { it.close() },
                onCloseFailure = operationBarrier::quarantineNativeResource
            )
            val connection = activeConnection
            val estimateFuture = executor.submit(java.util.concurrent.Callable {
                // BDK's estimateFee() returns BTC/kvB. Convert to sat/vB with 1e5.
                val priorityBtcKvb = runCatching { connection.client.estimateFee(1uL) }.getOrNull()
                val standardBtcKvb = runCatching { connection.client.estimateFee(3uL) }.getOrNull()
                val economyBtcKvb = runCatching { connection.client.estimateFee(6uL) }.getOrNull()

                fun btcKvbToSatVb(btcKvb: Double?): Float? {
                    if (btcKvb == null || btcKvb <= 0.0) return null
                    return (btcKvb * 100_000.0).toFloat()
                }

                val prioritySatVb = btcKvbToSatVb(priorityBtcKvb)
                val standardSatVb = btcKvbToSatVb(standardBtcKvb)
                val economySatVb = btcKvbToSatVb(economyBtcKvb)

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
            })
            BoundedBlockingCall.await(
                future = estimateFuture,
                timeoutMs = timeoutMs,
                operation = "Electrum fee estimation",
                onTimeout = { connection.cancelTransport() }
            )
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "Electrum fee estimation error: ${e.message}")
            null
        } finally {
            activeConnection?.cancelTransport()
            BoundedBlockingCall.shutdownAndAwaitTermination(
                executor = executor,
                operation = "Electrum fee-estimation worker",
                onTerminationStalled = operationBarrier::markFailedRestartRequiredFromOperation
            )
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(nativeCloseAction(activeConnection) { it.close() })
            )
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

        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "Fetching fees from mempool API: $url")

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

    override suspend fun bumpFee(walletId: String, txid: String, newFeeRate: Float): String =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        val entry = loadWallet(walletId, lease)
        val wallet = entry.wallet
        val feeRate = validatedFeeRate(newFeeRate)

        val psbt = org.bitcoindevkit.BumpFeeTxBuilder(org.bitcoindevkit.Txid.fromString(txid), feeRate)
            .finish(wallet)

        // Sign the bumped transaction and durably persist the replacement state.
        wallet.sign(psbt)
        wallet.persist(entry.persister)
        serializeFinalTransaction(psbt)
        }
        }
    }

    override suspend fun cancelTransaction(walletId: String, txid: String, newFeeRate: Float): String =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        val walletEntity = walletDao.getById(walletId)
        require(walletEntity?.isWatchOnly != true) { "Watch-only wallets must cancel via their external signer." }

        val entry = loadWallet(walletId, lease)
        val wallet = entry.wallet
        val txDetails = wallet.txDetails(org.bitcoindevkit.Txid.fromString(txid))
            ?: throw IllegalArgumentException("Transaction not found in this wallet")
        val originalTx = txDetails.tx

        try {
            val unconfirmedPosition = txDetails.chainPosition as? ChainPosition.Unconfirmed
            require(unconfirmedPosition != null) {
                "Only unconfirmed transactions can be replaced."
            }
            require(originalTx.isExplicitlyRbf()) {
                "This transaction does not signal RBF, so Clench cannot attempt a replacement cancel."
            }
            val sentAndReceived = wallet.sentAndReceived(originalTx)
            val sent = sentAndReceived.sent.toSat()
            val received = sentAndReceived.received.toSat()
            require(sent > received) {
                "Only outgoing transactions can be canceled with a replacement."
            }

            val feeRate = validatedFeeRate(newFeeRate)
            val inputs = originalTx.input().map { it.previousOutput }
            require(inputs.isNotEmpty()) { "Original transaction has no spendable inputs to replace." }

            // Temporarily evict the original from BDK's local graph. This makes its
            // wallet-owned inputs selectable through addUtxo(), which preserves the
            // descriptor/key-origin metadata BDK needs to produce a valid signature.
            // Always restore the original after signing; the network, not a local build,
            // decides which conflicting transaction wins.
            val txidValue = org.bitcoindevkit.Txid.fromString(txid)
            val nowEpochSeconds = (System.currentTimeMillis() / 1_000L).toULong()
            val originalLastSeen = unconfirmedPosition.timestamp ?: nowEpochSeconds
            val evictedAt = ReplacementTransactionPolicy.evictionTimestamp(
                unconfirmedPosition.timestamp,
                nowEpochSeconds
            )
            val psbt = ReplacementTransactionPolicy.withTemporaryEviction(
                evict = {
                    wallet.applyEvictedTxs(listOf(org.bitcoindevkit.EvictedTx(txidValue, evictedAt)))
                },
                restore = {
                    wallet.applyUnconfirmedTxs(
                        listOf(org.bitcoindevkit.UnconfirmedTx(originalTx, originalLastSeen))
                    )
                },
                build = {
                    val selfAddress = wallet.nextUnusedAddress(KeychainKind.EXTERNAL)
                    var builder = TxBuilder()
                        .drainTo(selfAddress.address.scriptPubkey())
                        .feeRate(feeRate)
                    inputs.forEach { outpoint ->
                        builder = builder.addUtxo(outpoint)
                    }
                    builder.manuallySelectedOnly().finish(wallet)
                }
            )
            wallet.sign(psbt)
            wallet.persist(entry.persister)
            serializeFinalTransaction(psbt)
        } finally {
            closeSecretNativeResources(nativeCloseAction(originalTx) { it.close() })
        }
        }
        }
    }

    override suspend fun listUnspent(walletId: String): List<net.clench.wallet.domain.model.UtxoInfo> =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        // Passphrase wallet guard — same as getTransactions().
        // Never expose UTXOs from the public descriptor (xpub) wallet in the locked state.
        val walletEntity = walletDao.getById(walletId)
        if (walletEntity?.hasPassphrase == true && !isPassphraseWalletMarkedUnlocked(walletId)) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "listUnspent: passphrase wallet $walletId is locked — returning empty list")
            return@withContext emptyList()
        }

        val entry = loadWallet(walletId, lease)
        val wallet = entry.wallet
        val utxos = wallet.listUnspent()
        // [S-4] Gate: UTXO count and unlock status expose wallet balance info
        if (logSensitive) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "listUnspent: walletId=${walletId.take(8)} rawUtxoCount=${utxos.size} unlocked=${isPassphraseWalletMarkedUnlocked(walletId)}")
        }

        // Calculate tip height for confirmation count via the configured Electrum route.
        var tipHeight: UInt = currentTipHeightFromElectrum() ?: 0u
        
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
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "listUnspent: tipHeight from wallet txs (fallback): $tipHeight")
        }

        // Load frozen outpoints for this wallet
        val frozenOutpoints = try {
            utxoMetadataDao.getFrozenForWallet(walletId).map { it.outpoint }.toSet()
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "listUnspent: failed to get frozen UTXOs: ${e.message}")
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
    ): String = withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        val entry = loadWallet(walletId, lease)
        val wallet = entry.wallet
        val network = activeNetwork()
        val recipientAddress = org.bitcoindevkit.Address(toAddress, network)
        val feeRate = validatedFeeRate(feeRateSatPerVbyte)
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
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "createPsbt: failed to get frozen UTXOs: ${e.message}")
                emptySet()
            }
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "createPsbt: watch-only wallet, adding ${utxos.size} UTXOs (${frozenOutpoints.size} frozen/excluded)")
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
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "createPsbt: failed to get frozen UTXOs for coin control: ${e.message}")
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
                        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "createPsbt: skipping frozen UTXO $outpointStr")
                        continue
                    }
                    builder = builder.addUtxo(org.bitcoindevkit.OutPoint(org.bitcoindevkit.Txid.fromString(txid), vout))
                }
            }
            builder = builder.manuallySelectedOnly()
        } else if (amountSat != null && utxoTxid != null && utxoVout != null) {
            val outpointStr = "$utxoTxid:$utxoVout"
            if (outpointStr in frozenOutpointsForCoinControl) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "createPsbt: attempted to spend frozen UTXO $outpointStr")
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
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "createPsbt: build with globalXpubs failed, retrying without: ${e.message?.take(80)}")
            builder.finish(wallet)
        }

        try {
            val serializedPsbt = psbt.serialize()
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "createPsbt: built PSBT for ${if (isWatchOnly) "watch-only" else "full"} wallet, base64 len=${serializedPsbt.length}")

            // Log PSBT origin info for debugging — helps verify hardware wallet compatibility
            try {
                val psbtJson = org.json.JSONObject(psbt.jsonSerialize())
                val inputs = psbtJson.optJSONArray("inputs")
                if (inputs != null && inputs.length() > 0) {
                    val firstInput = inputs.getJSONObject(0)
                    val bip32 = firstInput.optJSONArray("bip32_derivation")
                        ?: firstInput.optJSONArray("bip32_derivations")
                    if (bip32 != null && bip32.length() > 0) {
                        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "createPsbt: bip32_derivation[0] = ${bip32.getJSONObject(0)}")
                    } else {
                        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "createPsbt: no bip32_derivation in first input")
                    }
                }
            } catch (e: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "createPsbt: could not log bip32_derivation: ${e.message}")
            }

            serializedPsbt
        } finally {
            closeSecretNativeResources(nativeCloseAction(psbt) { it.close() })
        }
        }
        }
    }

    override suspend fun buildBatchTransaction(
        walletId: String,
        recipients: List<net.clench.wallet.domain.repository.Recipient>,
        feeRateSatPerVbyte: Float,
        selectedOutpoints: List<String>
    ): String = withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        require(recipients.isNotEmpty()) { "At least one recipient is required" }
        // B-3: Defense-in-depth — toULong() wraps negatives to huge numbers; guard here
        recipients.forEach { r ->
            require(r.amountSat > 0) { "Recipient amount must be positive, got ${r.amountSat}" }
        }
        val entry = loadWallet(walletId, lease)
        val wallet = entry.wallet
        val network = activeNetwork()
        val feeRate = validatedFeeRate(feeRateSatPerVbyte)

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
        serializeFinalTransaction(psbt)
        }
        }
    }

    override suspend fun createBatchPsbt(
        walletId: String,
        recipients: List<net.clench.wallet.domain.repository.Recipient>,
        feeRateSatPerVbyte: Float,
        selectedOutpoints: List<String>
    ): String = withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        require(recipients.isNotEmpty()) { "At least one recipient is required" }
        // B-3: Defense-in-depth — toULong() wraps negatives to huge numbers; guard here
        recipients.forEach { r ->
            require(r.amountSat > 0) { "Recipient amount must be positive, got ${r.amountSat}" }
        }
        val entry = loadWallet(walletId, lease)
        val wallet = entry.wallet
        val network = activeNetwork()
        val feeRate = validatedFeeRate(feeRateSatPerVbyte)
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
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "createBatchPsbt: build with globalXpubs failed, retrying without: ${e.message?.take(80)}")
            builder.finish(wallet)
        }
        try {
            psbt.serialize()
        } finally {
            closeSecretNativeResources(nativeCloseAction(psbt) { it.close() })
        }
        }
        }
    }

    override suspend fun applyAndBroadcastPsbt(
        walletId: String,
        signedPsbtBase64: String,
        unsignedPsbtBase64: String,
        assertBroadcastAuthorized: () -> Unit
    ): String = withSensitiveWalletOperation { _ -> withContext(Dispatchers.IO) {
        if (settingsManager.isOfflineMode()) {
            throw IllegalStateException("Cannot broadcast in offline mode")
        }

        // Hardware wallets do not all return the same payload after signing:
        // SeedSigner/Keystone/Passport/Jade generally return a signed PSBT, while
        // COLDCARD can return either a signed PSBT or a finalized transaction
        // (BBQr file type T / .txn), depending on the export path and settings.
        val signedPsbt = parseSignedPsbtPayload(signedPsbtBase64)
        val signatureContext = externalSignatureContext(unsignedPsbtBase64)
        try {
            val tx = if (signedPsbt != null) {
                validatePsbtMatchesUnsignedPsbt(unsignedPsbtBase64, signedPsbt, signatureContext)
                // Finalize the PSBT before comparing the resulting transaction to
                // the original unsigned PSBT. This is stricter than comparing PSBT
                // metadata and covers QR, NFC, and file-import paths uniformly.
                val finalizeResult = signedPsbt.finalize()
                if (!finalizeResult.couldFinalize) {
                    val errorMsgs = finalizeResult.errors?.joinToString(", ") { it.toString() } ?: "Unknown error"
                    closeSecretNativeResources(
                        nativeCloseAction(finalizeResult.psbt) { it.close() }
                    )
                    throw IllegalStateException("Could not finalize PSBT: $errorMsgs")
                }
                try {
                    finalizeResult.psbt.extractTx()
                } finally {
                    closeSecretNativeResources(
                        nativeCloseAction(finalizeResult.psbt) { it.close() }
                    )
                }
            } else {
                // Not a PSBT; treat it as a finalized raw transaction payload.
                Transaction(decodeTransactionPayload(signedPsbtBase64))
            }

            try {
                validateTransactionMatchesUnsignedPsbt(unsignedPsbtBase64, tx, signatureContext)
                val config = settingsManager.loadElectrumConfig()
                // This is deliberately the last step before any network I/O.
                // Hardware-signing coordinators use it to prove that the exact
                // reviewed session is still current after parsing/finalization.
                assertBroadcastAuthorized()
                broadcastTransactionBounded(config, tx)
            } finally {
                closeSecretNativeResources(nativeCloseAction(tx) { it.close() })
            }
        } finally {
            closeSecretNativeResources(nativeCloseAction(signedPsbt) { it.close() })
        }
        }
    }

    override suspend fun mergeSignedPsbt(
        unsignedPsbtBase64: String,
        currentPsbtBase64: String,
        signedPsbtPayload: String
    ): PsbtSigningProgress = withSensitiveWalletOperation { _ -> withContext(Dispatchers.IO) {
        val returnedPsbt = parseSignedPsbtPayload(signedPsbtPayload)
        val signatureContext = externalSignatureContext(unsignedPsbtBase64)

        if (returnedPsbt == null) {
            val tx = Transaction(decodeTransactionPayload(signedPsbtPayload))
            try {
                validateTransactionMatchesUnsignedPsbt(unsignedPsbtBase64, tx, signatureContext)
                return@withContext PsbtSigningProgress(
                    psbtBase64 = signedPsbtPayload.trim(),
                    readyToBroadcast = true,
                    message = "Clench imported a finalized transaction and verified it matches the original PSBT."
                )
            } finally {
                closeSecretNativeResources(nativeCloseAction(tx) { it.close() })
            }
        }

        val currentPsbtPayload = currentPsbtBase64.ifBlank { unsignedPsbtBase64 }
        PsbtSafety.inspectBase64(currentPsbtPayload)
        val currentPsbt = Psbt(currentPsbtPayload)
        var mergedPsbt: Psbt? = null
        var finalizedPsbt: Psbt? = null
        try {
            val signatureCountBefore = signatureMaterialCount(currentPsbt)
            ExternalSignaturePolicy.validatePsbtBase64(
                currentPsbtPayload,
                signatureContext.inputKinds,
                signatureContext.outputCount
            )
            validatePsbtMatchesUnsignedPsbt(unsignedPsbtBase64, returnedPsbt, signatureContext)
            val signatureOnlyMerge = ExternalSignaturePolicy.mergeSignatureMaterial(
                current = currentPsbtPayload,
                returned = returnedPsbt.serialize(),
                inputKinds = signatureContext.inputKinds,
                outputCount = signatureContext.outputCount
            )
            mergedPsbt = try {
                Psbt(signatureOnlyMerge)
            } catch (e: Exception) {
                throw IllegalStateException("Signed PSBT could not be merged with the current PSBT: ${e.message}")
            }

            validatePsbtMatchesUnsignedPsbt(unsignedPsbtBase64, mergedPsbt, signatureContext)
            val mergedSerialized = mergedPsbt.serialize()
            val signatureCountAfter = signatureMaterialCount(mergedPsbt)
            val finalizeResult = mergedPsbt.finalize()
            finalizedPsbt = finalizeResult.psbt

            if (finalizeResult.couldFinalize) {
                val finalizedTx = finalizedPsbt.extractTx()
                try {
                    validateTransactionMatchesUnsignedPsbt(unsignedPsbtBase64, finalizedTx, signatureContext)
                } finally {
                    closeSecretNativeResources(nativeCloseAction(finalizedTx) { it.close() })
                }
                return@withContext PsbtSigningProgress(
                    psbtBase64 = finalizedPsbt.serialize(),
                    readyToBroadcast = true,
                    message = "Enough signatures collected. Clench verified the finalized transaction matches the original PSBT."
                )
            }

            if (signatureCountAfter <= signatureCountBefore) {
                throw IllegalStateException("No new usable signature was found for this PSBT. Check that the signer belongs to this multisig wallet.")
            }

            PsbtSigningProgress(
                psbtBase64 = mergedSerialized,
                readyToBroadcast = false,
                message = "Signature added. More signatures are required before broadcast."
            )
        } finally {
            closeSecretNativeResources(
                nativeCloseAction(finalizedPsbt) { it.close() },
                nativeCloseAction(mergedPsbt) { it.close() },
                nativeCloseAction(currentPsbt) { it.close() },
                nativeCloseAction(returnedPsbt) { it.close() }
            )
        }
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
                PsbtSafety.inspectBase64(candidate)
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
    private fun validateTransactionMatchesUnsignedPsbt(
        unsignedBase64: String,
        signedTx: Transaction,
        signatureContext: ExternalSignatureContext = externalSignatureContext(unsignedBase64)
    ) {
        PsbtSafety.inspectBase64(unsignedBase64)
        val unsigned = Psbt(unsignedBase64)
        var unsignedTx: Transaction? = null
        try {
            val expected = try {
                unsigned.extractTx().also { unsignedTx = it }.let(::fingerprintTransaction)
            } catch (e: Exception) {
                throw SecurityException(
                    "PSBT transaction-policy validation failed: the original unsigned transaction could not be extracted. Refusing to broadcast.",
                    e
                )
            }

            val actual = fingerprintTransaction(signedTx)
            compareTransactionFingerprints(expected, actual)
            validateFinalizedSignaturePolicy(signedTx, signatureContext)
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "PSBT validation passed: complete unsigned transaction policy matches")
        } finally {
            closeSecretNativeResources(
                nativeCloseAction(unsignedTx) { it.close() },
                nativeCloseAction(unsigned) { it.close() }
            )
        }
    }

    private fun validatePsbtMatchesUnsignedPsbt(
        unsignedBase64: String,
        candidate: Psbt,
        signatureContext: ExternalSignatureContext = externalSignatureContext(unsignedBase64)
    ) {
        ExternalSignaturePolicy.validatePsbtBase64(
            candidate.serialize(),
            signatureContext.inputKinds,
            signatureContext.outputCount
        )
        PsbtSafety.inspectBase64(unsignedBase64)
        val unsigned = Psbt(unsignedBase64)
        var expectedTx: Transaction? = null
        var actualTx: Transaction? = null
        try {
            val expected = try {
                unsigned.extractTx().also { expectedTx = it }.let(::fingerprintTransaction)
            } catch (e: Exception) {
                throw SecurityException("Could not extract the original PSBT transaction policy", e)
            }

            val actual = try {
                candidate.extractTx().also { actualTx = it }.let(::fingerprintTransaction)
            } catch (e: Exception) {
                throw SecurityException("Could not extract the returned PSBT transaction policy", e)
            }
            compareTransactionFingerprints(expected, actual)
        } finally {
            closeSecretNativeResources(
                nativeCloseAction(actualTx) { it.close() },
                nativeCloseAction(expectedTx) { it.close() },
                nativeCloseAction(unsigned) { it.close() }
            )
        }
    }

    private data class ExternalSignatureContext(
        val inputKinds: List<ExternalSignaturePolicy.InputKind>,
        val outputCount: Int
    )

    private fun externalSignatureContext(unsignedBase64: String): ExternalSignatureContext {
        PsbtSafety.inspectBase64(unsignedBase64)
        val psbt = Psbt(unsignedBase64)
        try {
            return externalSignatureContext(psbt)
        } finally {
            closeSecretNativeResources(nativeCloseAction(psbt) { it.close() })
        }
    }

    private fun externalSignatureContext(psbt: Psbt): ExternalSignatureContext {
        val psbtInputs = psbt.input()
        val psbtOutputs = psbt.output()
        var unsignedTx: Transaction? = null
        try {
            val txInputs = psbt.extractTx().also { unsignedTx = it }.input()
            try {
                require(txInputs.size == psbtInputs.size) {
                    "PSBT input metadata does not match the unsigned transaction"
                }
                val kinds = psbtInputs.mapIndexed { index, input ->
                    val witnessScript = input.witnessScript?.toBytes()
                    val redeemScript = input.redeemScript?.toBytes()
                    val prevoutScript = input.witnessUtxo?.scriptPubkey?.toBytes()
                        ?: input.nonWitnessUtxo?.let { previousTx ->
                            val vout = txInputs[index].previousOutput.vout.toInt()
                            val previousOutputs = previousTx.output()
                            try {
                                previousOutputs.getOrNull(vout)?.scriptPubkey?.toBytes()
                            } finally {
                                closeSecretNativeResources(
                                    *previousOutputs.map { output ->
                                        nativeCloseAction(output) { it.destroy() }
                                    }.toTypedArray()
                                )
                            }
                        }

                    when {
                        prevoutScript?.isPayToTaproot() == true -> ExternalSignaturePolicy.InputKind.TAPROOT
                        witnessScript != null ||
                            prevoutScript?.isPayToWitnessScriptHash() == true ||
                            redeemScript?.isPayToWitnessScriptHash() == true ->
                            ExternalSignaturePolicy.InputKind.ECDSA_WITNESS_SCRIPT
                        else -> ExternalSignaturePolicy.InputKind.ECDSA
                    }
                }
                return ExternalSignatureContext(kinds, psbtOutputs.size)
            } finally {
                closeSecretNativeResources(
                    *txInputs.map { input ->
                        nativeCloseAction(input) { it.destroy() }
                    }.toTypedArray()
                )
            }
        } finally {
            closeSecretNativeResources(
                *buildList {
                    add(nativeCloseAction(unsignedTx) { it.close() })
                    psbtInputs.forEach { input ->
                        add(nativeCloseAction(input) { it.destroy() })
                    }
                    psbtOutputs.forEach { output ->
                        add(nativeCloseAction(output) { it.destroy() })
                    }
                }.toTypedArray()
            )
        }
    }

    private fun validateFinalizedSignaturePolicy(
        transaction: Transaction,
        signatureContext: ExternalSignatureContext
    ) {
        val txInputs = transaction.input()
        try {
            require(txInputs.size == signatureContext.inputKinds.size) {
                "Finalized transaction input count does not match the original PSBT"
            }
            ExternalSignaturePolicy.validateFinalizedInputs(
                txInputs.mapIndexed { index, input ->
                    ExternalSignaturePolicy.FinalizedInput(
                        kind = signatureContext.inputKinds[index],
                        scriptSig = input.scriptSig.toBytes(),
                        witness = input.witness.map(ByteArray::copyOf)
                    )
                }
            )
        } finally {
            closeSecretNativeResources(
                *txInputs.map { input ->
                    nativeCloseAction(input) { it.destroy() }
                }.toTypedArray()
            )
        }
    }

    private fun ByteArray.isPayToTaproot(): Boolean =
        size == 34 && this[0] == 0x51.toByte() && this[1] == 0x20.toByte()

    private fun ByteArray.isPayToWitnessScriptHash(): Boolean =
        size == 34 && this[0] == 0x00.toByte() && this[1] == 0x20.toByte()

    private fun signatureMaterialCount(psbt: Psbt): Int {
        val inputs = psbt.input()
        try {
            return inputs.sumOf { input ->
                input.partialSigs.size +
                    input.tapScriptSigs.size +
                    (input.finalScriptWitness?.size ?: 0) +
                    (if (input.tapKeySig?.isNotEmpty() == true) 1 else 0) +
                    (if (input.finalScriptSig?.toBytes()?.isNotEmpty() == true) 1 else 0)
            }
        } finally {
            closeSecretNativeResources(
                *inputs.map { input ->
                    nativeCloseAction(input) { it.destroy() }
                }.toTypedArray()
            )
        }
    }

    private fun fingerprintTransaction(tx: Transaction): TransactionFingerprint {
        val txInputs = tx.input()
        val txOutputs = tx.output()
        try {
            val inputs = txInputs.map { input ->
                val previousOutput = input.previousOutput
                "${previousOutput.txid}:${previousOutput.vout}"
            }
            val sequences = txInputs.map { it.sequence.toLong() }
            val outputs = txOutputs.map { output ->
                OutputFingerprint(
                    valueSat = output.value.toSat().toLong(),
                    scriptPubkeyHex = output.scriptPubkey.toBytes().toHexString()
                )
            }
            return TransactionFingerprint(
                version = tx.version(),
                lockTime = tx.lockTime().toLong(),
                inputs = inputs,
                sequences = sequences,
                outputs = outputs
            )
        } finally {
            closeSecretNativeResources(
                *buildList {
                    txInputs.forEach { input ->
                        add(nativeCloseAction(input) { it.destroy() })
                    }
                    txOutputs.forEach { output ->
                        add(nativeCloseAction(output) { it.destroy() })
                    }
                }.toTypedArray()
            )
        }
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

    private fun compareTransactionFingerprints(
        expected: TransactionFingerprint,
        actual: TransactionFingerprint
    ) {
        if (expected.version != actual.version) {
            throw SecurityException("PSBT tampered: transaction version changed")
        }
        if (expected.lockTime != actual.lockTime) {
            throw SecurityException("PSBT tampered: transaction locktime changed")
        }
        if (expected.inputs != actual.inputs) {
            throw SecurityException("PSBT tampered: transaction inputs changed")
        }
        if (expected.sequences != actual.sequences) {
            throw SecurityException("PSBT tampered: input sequences changed")
        }
        compareOutputs(expected.outputs, actual.outputs)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun serializeFinalTransaction(psbt: Psbt): String {
        var tx: Transaction? = null
        try {
            tx = psbt.extractTx()
            return tx.serialize().toHexString()
        } finally {
            closeSecretNativeResources(
                nativeCloseAction(tx) { it.close() },
                nativeCloseAction(psbt) { it.close() }
            )
        }
    }

    private fun validatedFeeRate(value: Float): FeeRate {
        require(value.isFinite() && value in 1f..1_000f) {
            "Fee rate must be from 1 to 1000 sat/vB"
        }
        return FeeRate.fromSatPerVb(kotlin.math.ceil(value.toDouble()).toLong().toULong())
    }

    /**
     * Load wallet from cache or SQLite.
     * For signing wallets: retrieves secret descriptors (xprv) from encrypted Keystore.
     * For passphrase wallets (not yet unlocked): uses public descriptors only (watch-only mode).
     * For passphrase wallets (unlocked): uses the cached secret wallet.
     */
    private suspend fun loadWallet(
        walletId: String,
        lease: SensitiveWalletOperationBarrier.Lease
    ): WalletEntry {
        operationBarrier.assertActive(lease)
        // Check cache first - for unlocked passphrase wallets, the cached entry has secret descriptors
        cachedWallet(walletId, lease)?.let { return it }

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
        val isUnlocked = isWalletCached(walletId, lease)
        
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
        val descriptors = createDescriptorPair(
            createExternal = { Descriptor(externalDescriptorStr, network) },
            createChange = { Descriptor(changeDescriptorStr, network) }
        )

        // Passphrase wallets are ALWAYS in-memory only — never load from disk.
        // This prevents stale UTXO/tx data from the real wallet being visible in the locked state
        // (before the passphrase is entered). The public descriptor wallet shares the same walletId
        // as the passphrase-derived wallet, so if we loaded from disk here, real wallet data
        // would be visible without any passphrase. In-memory guarantees a clean slate every time.
        if (isPassphraseWallet) {
            val entry = createWalletEntryFromDescriptors(
                descriptors = descriptors,
                createPersister = Persister::newInMemory,
                createWallet = { external, change, persister ->
                    Wallet(external, change, network, persister)
                }
            )
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "loadWallet: passphrase wallet $walletId — using in-memory persister (locked state)")
            return cacheWalletIfAbsent(walletId, entry, lease)
        }

        // Non-passphrase wallets: load from SQLite (if exists, else create new)
        val dbFile = context.getDatabasePath("wallet_${walletId}.db")
        val hadExistingDb = dbFile.exists()
        val dbPath = dbFile.absolutePath
        val entry = createWalletEntryFromDescriptors(
            descriptors = descriptors,
            createPersister = { Persister.newSqlite(dbPath) },
            createWallet = { externalDescriptor, changeDescriptor, persister ->
            try {
                Wallet.load(externalDescriptor, changeDescriptor, persister)
            } catch (e: org.bitcoindevkit.LoadWithPersistException.CouldNotLoad) {
                if (!hadExistingDb) {
                    Wallet(externalDescriptor, changeDescriptor, network, persister)
                } else {
                    throw walletStateRecoveryRequired(walletId, e)
                }
            } catch (e: org.bitcoindevkit.LoadWithPersistException.Persist) {
                if (!hadExistingDb) {
                    Wallet(externalDescriptor, changeDescriptor, network, persister)
                } else {
                    throw walletStateRecoveryRequired(walletId, e)
                }
            } catch (e: Exception) {
                throw walletStateRecoveryRequired(walletId, e)
            }
            }
        )
        val wallet = entry.wallet

        // Debug: log descriptor and first address
        // [S-4] Gate: wallet ID and address exposure
        if (logSensitive) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "loadWallet: id=$walletId network=${walletEntity.network} watchOnly=${walletEntity.isWatchOnly}")
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "loadWallet: descriptor=(redacted)")
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "loadWallet: changeDesc=(redacted)")
            try {
                val addr0 = wallet.revealAddressesTo(org.bitcoindevkit.KeychainKind.EXTERNAL, 0u)
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "loadWallet: addr[0]=${addr0}")
            } catch (e: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "loadWallet: could not derive addr[0]: ${e.message}")
            }
        }

        // Cache and return
        return cacheWalletIfAbsent(walletId, entry, lease)
    }

    private fun walletStateRecoveryRequired(walletId: String, cause: Exception): WalletStateRecoveryRequiredException {
        if (net.clench.wallet.BuildConfig.DEBUG) {
            android.util.Log.e(
                "BdkBitcoinRepository",
                "Wallet state load failed for ${walletId.take(8)}; database preserved",
                cause
            )
        }
        return WalletStateRecoveryRequiredException(
            "Wallet state could not be opened. Clench preserved the database to prevent address reuse or missed funds. Restore the wallet state or perform an extended recovery scan before spending.",
            cause
        )
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
            input.startsWith("sh(wpkh(") || input.startsWith("wsh(") ||
            input.startsWith("sh(wsh(") || input.startsWith("tr(")) {
            // Extract and convert any non-xpub extended key inside the descriptor
            val descriptorWithoutChecksum = input.substringBefore("#").trim()
            val converted = descriptorWithoutChecksum
                .replace(Regex("zpub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("Zpub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("ypub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("Ypub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("vpub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("Vpub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("upub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
                .replace(Regex("Upub[1-9A-HJ-NP-Za-km-z]+")) { convertZpubToXpub(it.value) }
            val external = if (containsExtendedKey(converted) && !converted.contains("/0/*") && !converted.contains("/*")) {
                // Ranged extended-key descriptors need an explicit receive branch.
                converted.trimEnd(')') + "/0/*)"
            } else converted
            MultisigDescriptorSafety.validate(external)
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
            if (keyPart.startsWith("Zpub") || keyPart.startsWith("Ypub") || keyPart.startsWith("Vpub") || keyPart.startsWith("Upub")) {
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
                keyPart.startsWith("Vpub") -> Pair(convertZpubToXpub(keyPart), "wpkh")
                keyPart.startsWith("Upub") -> Pair(convertZpubToXpub(keyPart), "sh_wpkh")
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
        if (input.startsWith("Zpub") || input.startsWith("Ypub") || input.startsWith("Vpub") || input.startsWith("Upub")) {
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

    private fun containsExtendedKey(input: String): Boolean {
        return Regex("""(?i)[xtuvyz]prv|[xtuvyz]pub|[ZYUV]pub""").containsMatchIn(input)
    }

    private fun isMultisigDescriptor(descriptor: String): Boolean {
        val lower = descriptor.lowercase()
        return lower.contains("multi(") || lower.contains("sortedmulti(")
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
            val targetVersion = if (key.startsWith("vpub") || key.startsWith("upub") ||
                key.startsWith("Vpub") || key.startsWith("Upub")) {
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
     * Compute the legacy 8-byte identicon hash for a wallet.
     * New UI renders Sparrow-compatible LifeHash from the master fingerprint,
     * but this value is retained for older backups and fallback UI paths.
     */
    private fun computeIdenticonBytes(publicDescriptor: String, passphrase: String?): ByteArray? {
        val masterFpMatch = Regex("\\[([0-9a-fA-F]{8})/").find(publicDescriptor) ?: return null
        val hex = masterFpMatch.groupValues[1]
        val masterFpBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val input = masterFpBytes + (passphrase ?: "").toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input)
        return digest.sliceArray(0 until 8)
    }

    private fun currentTipHeightFromElectrum(): UInt? {
        if (settingsManager.isOfflineMode()) return null

        return try {
            val config = settingsManager.loadElectrumConfig()
            electrumConnectionFactory.createRawSocket(config).use { socket ->
                socket.soTimeout = 12_000
                val writer = java.io.PrintWriter(
                    java.io.BufferedWriter(
                        java.io.OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                    ),
                    true
                )
                val reader = java.io.BufferedReader(
                    java.io.InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
                )

                writer.println("""{"id":1,"method":"blockchain.headers.subscribe","params":[]}""")

                var reads = 0
                var tipHeight: UInt? = null
                while (reads < 4 && tipHeight == null) {
                    val line = reader.readLine() ?: break
                    reads++
                    val response = runCatching { org.json.JSONObject(line) }.getOrNull() ?: continue
                    val header = response.optJSONObject("result")
                    val height = header?.optInt("height", -1) ?: -1
                    if (height > 0) tipHeight = height.toUInt()
                }
                tipHeight
            }
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "Failed to get tip height from Electrum: ${e.message}")
            null
        }
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
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "batchElectrumTxLookup: connecting for ${txids.size} txids")
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "batchElectrumTxLookup: first txid=${txids.firstOrNull()} len=${txids.firstOrNull()?.length}")

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

            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "batchElectrumTxLookup: got ${responses.size} responses for ${txids.size} requests")
            // Parse results
            for ((idx, txid) in txids.withIndex()) {
                val resp = responses[idx]
                if (resp == null) {
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "batchElectrumTxLookup: no response for idx=$idx txid=${txid.take(12)}")
                    continue
                }
                val error = resp.optJSONObject("error")
                if (error != null) {
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "batchElectrumTxLookup: error for ${txid.take(12)}: ${error}")
                    continue
                }
                val txResult = resp.optJSONObject("result")
                if (txResult == null) {
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "batchElectrumTxLookup: no result object for ${txid.take(12)}: ${resp.toString().take(200)}")
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
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "Electrum batch: ${txid.take(12)}... confs=$confs height=$blockHeight time=$blockTime")
                    result[txid] = Pair(blockHeight, blockTime)
                }
            }

            socket.close()
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "batchElectrumTxLookup failed: ${e.message}")
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
            conn.inputStream.bufferedReader().use { it.readTextBounded(maxHttpResponseChars) }
        } finally {
            conn.disconnect()
        }
    }


    // ========== Multisig Wallet Methods ==========

    override suspend fun createMultisigWallet(
        name: String,
        threshold: Int,
        signerXpubs: List<String>,
        localSignerSecrets: Map<Int, MultisigPhoneSignerSecret>
    ): WalletData = withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        val network = activeNetwork()
        require(threshold in 1..signerXpubs.size) {
            "Threshold must be between 1 and the number of signers (${signerXpubs.size})"
        }
        require(signerXpubs.size in 2..7) {
            "Number of signers must be between 2 and 7"
        }

        val normalizedSignerKeys = signerXpubs.mapIndexed { index, xpub ->
            normalizeMultisigSignerKey(xpub, index + 1, network)
        }
        require(normalizedSignerKeys.map { canonicalMultisigSignerKey(it) }.distinct().size == normalizedSignerKeys.size) {
            "Duplicate cosigner key detected. Each signer must be unique."
        }
        require(localSignerSecrets.keys.all { it in signerXpubs.indices }) {
            "Phone signer secret index is outside the signer list"
        }
        require(localSignerSecrets.size < threshold) {
            "Phone signers must be fewer than the required signature threshold"
        }
        val normalizedLocalSecretKeys = localSignerSecrets.mapValues { (index, secret) ->
            normalizeMultisigSecretSignerKey(secret.accountXprvWithOrigin, index + 1, network)
        }

        // Build the sortedmulti descriptor fragments for external (receive) and change.
        // Each signer key may include origin info: [fingerprint/48'/0'/0'/2']xpub...
        // We append /0/* for external and /1/* for change.
        val externalKeys = normalizedSignerKeys.joinToString(",") { xpub ->
            if (xpub.endsWith("/0/*") || xpub.endsWith("/1/*")) {
                xpub.replace("/1/*", "/0/*")
            } else {
                "$xpub/0/*"
            }
        }
        val changeKeys = normalizedSignerKeys.joinToString(",") { xpub ->
            if (xpub.endsWith("/0/*") || xpub.endsWith("/1/*")) {
                xpub.replace("/0/*", "/1/*")
            } else {
                "$xpub/1/*"
            }
        }

        val externalDescriptorStr = "wsh(sortedmulti($threshold,$externalKeys))"
        val changeDescriptorStr = "wsh(sortedmulti($threshold,$changeKeys))"
        MultisigDescriptorSafety.validate(externalDescriptorStr)
        MultisigDescriptorSafety.validate(changeDescriptorStr)
        val signingExternalDescriptorStr = if (normalizedLocalSecretKeys.isNotEmpty()) {
            val keys = normalizedSignerKeys.mapIndexed { index, publicKey ->
                normalizedLocalSecretKeys[index] ?: publicKey
            }.joinToString(",") { key ->
                if (key.endsWith("/0/*") || key.endsWith("/1/*")) {
                    key.replace("/1/*", "/0/*")
                } else {
                    "$key/0/*"
                }
            }
            "wsh(sortedmulti($threshold,$keys))"
        } else null
        val signingChangeDescriptorStr = if (normalizedLocalSecretKeys.isNotEmpty()) {
            val keys = normalizedSignerKeys.mapIndexed { index, publicKey ->
                normalizedLocalSecretKeys[index] ?: publicKey
            }.joinToString(",") { key ->
                if (key.endsWith("/0/*") || key.endsWith("/1/*")) {
                    key.replace("/0/*", "/1/*")
                } else {
                    "$key/1/*"
                }
            }
            "wsh(sortedmulti($threshold,$keys))"
        } else null

        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "createMultisigWallet: external=$externalDescriptorStr")
        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "createMultisigWallet: change=$changeDescriptorStr")

        var publicDescriptors: Pair<Descriptor, Descriptor>? = null
        var signingDescriptors: Pair<Descriptor, Descriptor>? = null
        try {
        publicDescriptors = try {
            createDescriptorPair(
                createExternal = { Descriptor(externalDescriptorStr, network) },
                createChange = { Descriptor(changeDescriptorStr, network) }
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid multisig descriptor: ${e.message}")
        }
        if (signingExternalDescriptorStr != null && signingChangeDescriptorStr != null) {
            signingDescriptors = try {
                createDescriptorPair(
                    createExternal = { Descriptor(signingExternalDescriptorStr, network) },
                    createChange = { Descriptor(signingChangeDescriptorStr, network) }
                )
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid phone-signer descriptor: ${e.message}")
            }
        }
        val externalDescriptor = checkNotNull(publicDescriptors).first
        val changeDescriptor = checkNotNull(publicDescriptors).second
        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val signingSecretDescriptor = signingDescriptors?.first?.toStringWithSecret()
        val signingSecretChangeDescriptor = signingDescriptors?.second?.toStringWithSecret()
        signingDescriptors?.let(::closeDescriptorPair)
        signingDescriptors = null

        // Prevent duplicate imports
        val activeNetwork = if (network == Network.TESTNET) "testnet" else "mainnet"
        val existing = walletDao.getAllByNetwork(activeNetwork)
        if (existing.any { it.descriptor == publicDescriptor }) {
            throw IllegalArgumentException("A wallet with this multisig configuration already exists.")
        }

        // Generate wallet ID and create BDK wallet
        val walletId = UUID.randomUUID().toString()
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val ownedDescriptors = checkNotNull(publicDescriptors)
        publicDescriptors = null
        val entry = createWalletEntryFromDescriptors(
            descriptors = ownedDescriptors,
            createPersister = { Persister.newSqlite(dbPath) },
            createWallet = { external, change, persister ->
                Wallet(external, change, network, persister)
            }
        )
        cacheWallet(walletId, entry, lease)

        try {
            if (signingSecretDescriptor != null && signingSecretChangeDescriptor != null) {
                val signerMnemonics = localSignerSecrets.mapNotNull { (index, secret) ->
                    parseSignerKeyForMetadata(normalizedSignerKeys[index])?.let { parsed ->
                        stableKeystoreId(parsed.fingerprint, parsed.derivationPath, parsed.xpub) to
                            secret.mnemonicWords.joinToString(" ")
                    }
                }.toMap()
                keystoreManager.storeMultisigWalletSecrets(
                    walletId = walletId,
                    secretDescriptor = signingSecretDescriptor,
                    secretChangeDescriptor = signingSecretChangeDescriptor,
                    signerMnemonicsByKeyId = signerMnemonics
                )
            }

            val walletEntity = WalletEntity(
                id = walletId,
                name = name,
                descriptor = publicDescriptor,
                changeDescriptor = publicChangeDescriptor,
                isWatchOnly = true,
                isMultisig = true,
                createdAtEpochMs = System.currentTimeMillis(),
                network = activeNetwork
            )
            walletDao.insert(walletEntity)

            WalletData(
                id = walletId,
                name = name,
                descriptor = publicDescriptor,
                changeDescriptor = publicChangeDescriptor,
                isWatchOnly = true,
                isMultisig = true,
                createdAt = java.time.Instant.ofEpochMilli(walletEntity.createdAtEpochMs),
                network = activeNetwork
            )
        } catch (e: Exception) {
            discardFailedWalletCreation(walletId, lease)
            throw e
        }
        } finally {
            signingDescriptors?.let(::closeDescriptorPair)
            publicDescriptors?.let(::closeDescriptorPair)
        }
        }
    }

    override suspend fun generateMultisigPhoneSigner(): GeneratedMultisigPhoneSigner =
        withSensitiveWalletOperation { _ -> withContext(Dispatchers.IO) {
        val network = activeNetwork()
        val isTestnet = network == Network.TESTNET
        val derivationPath = if (isTestnet) "m/48'/1'/0'/2'" else "m/48'/0'/0'/2'"
        var mnemonic: Mnemonic? = null
        var rootSecretKey: DescriptorSecretKey? = null
        var accountPath: DerivationPath? = null
        var accountSecretKey: DescriptorSecretKey? = null
        var accountPublicKey: org.bitcoindevkit.DescriptorPublicKey? = null
        var rootPublicKey: org.bitcoindevkit.DescriptorPublicKey? = null
        try {
            mnemonic = walletMnemonicGenerator.generate(24)
            rootSecretKey = DescriptorSecretKey(network, mnemonic, "")
            accountPath = DerivationPath(derivationPath)
            accountSecretKey = checkNotNull(rootSecretKey).derive(checkNotNull(accountPath))
            accountPublicKey = accountSecretKey.asPublic()
            rootPublicKey = checkNotNull(rootSecretKey).asPublic()
            val fingerprint = rootPublicKey.masterFingerprint().uppercase(Locale.US)
            val publicKey = originWrapAccountKey(accountPublicKey.toString(), fingerprint, derivationPath)
            val secretKey = originWrapAccountKey(accountSecretKey.toString(), fingerprint, derivationPath)
            GeneratedMultisigPhoneSigner(
                mnemonicWords = checkNotNull(mnemonic).toString().split(" "),
                xpubWithOrigin = publicKey,
                accountXprvWithOrigin = secretKey,
                fingerprint = fingerprint,
                derivationPath = derivationPath
            )
        } finally {
            closeSecretNativeResources(
                nativeCloseAction(accountPublicKey) { it.destroy() },
                nativeCloseAction(accountSecretKey) { it.destroy() },
                nativeCloseAction(rootPublicKey) { it.destroy() },
                nativeCloseAction(accountPath) { it.destroy() },
                nativeCloseAction(rootSecretKey) { it.destroy() },
                nativeCloseAction(mnemonic) { it.destroy() }
            )
        }
        }
    }

    override suspend fun hasMultisigPhoneSigner(walletId: String): Boolean =
        withSensitiveWalletOperation { _ -> withContext(Dispatchers.IO) {
        val walletEntity = walletDao.getById(walletId) ?: return@withContext false
        walletEntity.isMultisig &&
            keystoreManager.getSecretDescriptor(walletId) != null &&
            keystoreManager.getSecretChangeDescriptor(walletId) != null
        }
    }

    override suspend fun signMultisigPsbtWithPhoneKeys(walletId: String, psbtBase64: String): String =
        withSensitiveWalletOperation { _ -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        PsbtSafety.inspectBase64(psbtBase64)
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        require(walletEntity.isMultisig) { "Phone signer PSBT signing is only available for multisig wallets" }
        val externalSecret = keystoreManager.getSecretDescriptor(walletId)
            ?: throw IllegalStateException("No Clench phone signer keys are stored for this wallet")
        val changeSecret = keystoreManager.getSecretChangeDescriptor(walletId)
            ?: throw IllegalStateException("No Clench phone signer change keys are stored for this wallet")
        val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
        val descriptors = createDescriptorPair(
            createExternal = { Descriptor(externalSecret, network) },
            createChange = { Descriptor(changeSecret, network) }
        )
        val entry = createWalletEntryFromDescriptors(
            descriptors = descriptors,
            createPersister = { Persister.newInMemory() },
            createWallet = { external, change, persister ->
                Wallet(external, change, network, persister)
            }
        )
        val signingWallet = entry.wallet
        val psbt = try {
            Psbt(psbtBase64)
        } catch (e: Exception) {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(signingWallet) { it.close() },
                    nativeCloseAction(entry.persister) { it.close() }
                )
            )
            throw e
        }
        try {
            signingWallet.sign(psbt)
            psbt.serialize()
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(psbt) { it.close() },
                    nativeCloseAction(signingWallet) { it.close() },
                    nativeCloseAction(entry.persister) { it.close() }
                )
            )
        }
        }
        }
    }

    private fun normalizeMultisigSignerKey(raw: String, signerNumber: Int, network: Network): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "Signer $signerNumber: extended public key is required" }
        require(!trimmed.startsWith("wsh(") && !trimmed.startsWith("wpkh(") && !trimmed.startsWith("sh(")) {
            "Signer $signerNumber: paste the signer public key, not a full descriptor"
        }

        val origin: String
        val keyWithSuffix: String
        if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf(']')
            require(closeBracket > 0) { "Signer $signerNumber: malformed key origin — missing closing ']'" }
            origin = trimmed.substring(0, closeBracket + 1)
            validateMultisigOriginNetwork(origin, signerNumber, network)
            keyWithSuffix = trimmed.substring(closeBracket + 1)
        } else {
            origin = ""
            keyWithSuffix = trimmed
        }

        val suffix = when {
            keyWithSuffix.endsWith("/0/*") -> "/0/*"
            keyWithSuffix.endsWith("/1/*") -> "/1/*"
            else -> ""
        }
        val key = keyWithSuffix.removeSuffix("/0/*").removeSuffix("/1/*")
        validateMultisigSignerNetwork(key, signerNumber, network)
        require(!key.startsWith("xprv") && !key.startsWith("yprv") &&
            !key.startsWith("zprv") && !key.startsWith("tprv")) {
            "Signer $signerNumber: private extended keys are not allowed"
        }
        val publicKey = when {
            key.startsWith("xpub") || key.startsWith("tpub") -> key
            key.startsWith("ypub") || key.startsWith("zpub") ||
                key.startsWith("Ypub") || key.startsWith("Zpub") ||
                key.startsWith("upub") || key.startsWith("vpub") ||
                key.startsWith("Upub") || key.startsWith("Vpub") -> convertZpubToXpub(key)
            else -> throw IllegalArgumentException(
                "Signer $signerNumber: unrecognized key format. Expected xpub, Zpub, tpub, or similar public extended key."
            )
        }
        return "$origin$publicKey$suffix"
    }

    private fun normalizeMultisigSecretSignerKey(raw: String, signerNumber: Int, network: Network): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "Signer $signerNumber: phone signer secret is missing" }

        val origin: String
        val keyWithSuffix: String
        if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf(']')
            require(closeBracket > 0) { "Signer $signerNumber: malformed phone signer origin — missing closing ']'" }
            origin = trimmed.substring(0, closeBracket + 1)
            validateMultisigOriginNetwork(origin, signerNumber, network)
            keyWithSuffix = trimmed.substring(closeBracket + 1)
        } else {
            origin = ""
            keyWithSuffix = trimmed
        }

        val suffix = when {
            keyWithSuffix.endsWith("/0/*") -> "/0/*"
            keyWithSuffix.endsWith("/1/*") -> "/1/*"
            else -> ""
        }
        val key = keyWithSuffix.removeSuffix("/0/*").removeSuffix("/1/*")
        validateMultisigSecretSignerNetwork(key, signerNumber, network)
        require(key.startsWith("xprv") || key.startsWith("tprv")) {
            "Signer $signerNumber: phone signer secret must be an xprv/tprv account key"
        }
        return "$origin$key$suffix"
    }

    private fun validateMultisigSignerNetwork(key: String, signerNumber: Int, network: Network) {
        val mainnetKey = listOf("xpub", "ypub", "zpub", "Ypub", "Zpub").any { key.startsWith(it) }
        val testnetKey = listOf("tpub", "upub", "vpub", "Upub", "Vpub").any { key.startsWith(it) }
        if (network == Network.TESTNET && mainnetKey) {
            throw IllegalArgumentException("Signer $signerNumber: mainnet public key used while Clench is set to testnet")
        }
        if (network == Network.BITCOIN && testnetKey) {
            throw IllegalArgumentException("Signer $signerNumber: testnet public key used while Clench is set to mainnet")
        }
    }

    private fun validateMultisigSecretSignerNetwork(key: String, signerNumber: Int, network: Network) {
        val mainnetKey = key.startsWith("xprv")
        val testnetKey = key.startsWith("tprv")
        if (network == Network.TESTNET && mainnetKey) {
            throw IllegalArgumentException("Signer $signerNumber: mainnet phone signer key used while Clench is set to testnet")
        }
        if (network == Network.BITCOIN && testnetKey) {
            throw IllegalArgumentException("Signer $signerNumber: testnet phone signer key used while Clench is set to mainnet")
        }
    }

    private fun validateMultisigOriginNetwork(origin: String, signerNumber: Int, network: Network) {
        val inner = origin.removePrefix("[").removeSuffix("]")
        val parts = inner.split('/')
        require(parts.isNotEmpty() && Regex("^[0-9a-fA-F]{8}$").matches(parts[0])) {
            "Signer $signerNumber: key origin must start with an 8-character master fingerprint"
        }
        val pathParts = parts.drop(1).let { if (it.firstOrNull() == "m") it.drop(1) else it }
        if (pathParts.size >= 2) {
            val coinType = pathParts[1].removeSuffix("'").removeSuffix("h").removeSuffix("H")
            val expected = if (network == Network.TESTNET) "1" else "0"
            require(coinType == expected) {
                "Signer $signerNumber: origin path coin type $coinType does not match ${if (network == Network.TESTNET) "testnet" else "mainnet"}"
            }
        }
    }

    private data class ParsedSignerKey(
        val fingerprint: String?,
        val derivationPath: String?,
        val xpub: String
    )

    private fun parseSignerKeyForMetadata(raw: String): ParsedSignerKey? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val originMatch = Regex("""^\[([0-9a-fA-F]{8})(?:/([^\]]+))?\](.+)$""").find(trimmed)
        val fingerprint = originMatch?.groupValues?.getOrNull(1)?.uppercase(Locale.US)
        val originPath = originMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        val keyWithPath = originMatch?.groupValues?.getOrNull(3) ?: trimmed
        val xpub = keyWithPath
            .removeSuffix("/0/*")
            .removeSuffix("/1/*")
            .removeSuffix("/**")
            .trim()
            .ifBlank { return null }
        return ParsedSignerKey(fingerprint, originPath, xpub)
    }

    private fun stableKeystoreId(
        fingerprint: String?,
        derivationPath: String?,
        xpub: String
    ): String {
        val input = listOf(
            fingerprint.orEmpty().uppercase(Locale.US),
            derivationPath.orEmpty().lowercase(Locale.US),
            xpub.trim()
        ).joinToString("|")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private fun originWrapAccountKey(rawKey: String, fingerprint: String, derivationPath: String): String {
        val key = MultisigAccountKeyPolicy.normalizeGeneratedAccountKey(rawKey)
        if (key.startsWith("[")) return key
        return "[${fingerprint.uppercase(Locale.US)}/${derivationPath.removePrefix("m/")}]$key"
    }

    private fun canonicalMultisigSignerKey(raw: String): String {
        val trimmed = raw.trim()
        val key = if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket >= 0) trimmed.substring(closeBracket + 1) else trimmed
        } else trimmed
        return key.removeSuffix("/0/*").removeSuffix("/1/*")
    }

    // ========== Passphrase Wallet Methods ==========

    /**
     * Unlock a passphrase wallet by deriving secret descriptors from stored mnemonic + passphrase.
     * Caches the fully-functional wallet (with signing capability) in walletCache.
     * 
     * @throws IllegalArgumentException if wallet not found or passphrase is incorrect
     */
    override suspend fun unlockPassphraseWallet(walletId: String, passphrase: String): Unit =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "unlockPassphraseWallet: starting for $walletId")
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        
        if (!walletEntity.hasPassphrase) {
            throw IllegalArgumentException("This wallet does not use a passphrase")
        }

        // Get mnemonic from keystore
        val mnemonicStr = keystoreManager.getMnemonic(walletId)
        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "unlockPassphraseWallet: mnemonic ${if (mnemonicStr != null) "found" else "NOT FOUND"}")
        if (mnemonicStr == null) throw IllegalStateException("Mnemonic not found for wallet: $walletId")
        
        val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
        val scriptType = ScriptType.fromDescriptor(walletEntity.descriptor)
        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "unlockPassphraseWallet: scriptType=$scriptType network=$network")
        var mnemonic: Mnemonic? = null
        var secretKey: DescriptorSecretKey? = null
        var descriptors: Pair<Descriptor, Descriptor>? = null
        try {
            mnemonic = Mnemonic.fromString(mnemonicStr)
            secretKey = DescriptorSecretKey(network, mnemonic, passphrase)
            descriptors = createDescriptorPair(
                createExternal = {
                    ScriptType.createDescriptor(
                        checkNotNull(secretKey),
                        scriptType,
                        KeychainKind.EXTERNAL,
                        network
                    )
                },
                createChange = {
                    ScriptType.createDescriptor(
                        checkNotNull(secretKey),
                        scriptType,
                        KeychainKind.INTERNAL,
                        network
                    )
                }
            )

            // Duress wallet design: any passphrase is silently accepted. Passphrase-derived
            // wallets use an in-memory persister and are discarded on lock/background.
            val ownedDescriptors = checkNotNull(descriptors)
            descriptors = null
            val entry = createWalletEntryFromDescriptors(
                descriptors = ownedDescriptors,
                createPersister = { Persister.newInMemory() },
                createWallet = { external, change, persister ->
                    Wallet(external, change, network, persister)
                }
            )

            // unlockedPassphraseWallets, not merely walletCache, is authoritative.
            cacheWallet(walletId, entry, lease)
            markPassphraseWalletUnlocked(walletId, lease)

            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "unlockPassphraseWallet: unlocked wallet $walletId")
        } finally {
            descriptors?.let(::closeDescriptorPair)
            closeSecretNativeResources(
                nativeCloseAction(secretKey) { it.destroy() },
                nativeCloseAction(mnemonic) { it.destroy() }
            )
        }
        }
        }
    }

    /**
     * Lock a passphrase wallet by evicting the cached secret wallet.
     * The public-key-only version remains available for viewing balance/addresses.
     */
    override suspend fun lockPassphraseWallet(walletId: String): Unit =
        withSensitiveWalletOperation { lease -> withContext(Dispatchers.IO) {
        syncMutex(walletId).withLock {
        // Remove from unlock tracking set first
        markPassphraseWalletLocked(walletId, lease)
        // Close and discard the in-memory wallet — all session data is destroyed
        evictWallet(walletId, lease)
        // Wipe Room transaction cache — it contains real wallet tx history which must not be
        // visible before the passphrase is entered next session.
        transactionDao.deleteForWallet(walletId)
        // Delete the on-disk wallet DB so the locked state shows no cached UTXOs or balance.
        // The public descriptor (xpub) is preserved in Room — addresses can always be re-derived.
        // On next unlock, a fresh in-memory wallet is created and synced from Electrum.
        // This prevents stale UTXO data from leaking through the public descriptor wallet
        // which could reveal real wallet activity even before the passphrase is entered.
        val dbFile = context.getDatabasePath("wallet_${walletId}.db")
        val undeleted = PassphraseWalletCacheCleanup.deleteAndFindRemaining(dbFile)
        if (undeleted.isNotEmpty()) {
            throw IllegalStateException(
                "Passphrase wallet locked, but public cache cleanup failed for " +
                    undeleted.joinToString { it.name }
            )
        }
        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "lockPassphraseWallet: verified on-disk DB deletion for $walletId")
        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "lockPassphraseWallet: locked and discarded in-memory wallet $walletId")
        }
        }
    }

    /**
     * Check if a passphrase wallet is currently unlocked (secret wallet cached).
     */
    override fun isPassphraseWalletUnlocked(walletId: String): Boolean {
        return operationBarrier.isOpen() && isPassphraseWalletMarkedUnlocked(walletId)
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
     * @return Pair(legacyIdenticonBytes [8], masterFingerprintBytes [4]) or null on error
     */
    suspend fun getPassphraseFingerprint(
        walletId: String,
        passphrase: String
    ): Pair<ByteArray, ByteArray>? = withSensitiveWalletOperation { _ -> withContext(Dispatchers.IO) {
        var mnemonic: Mnemonic? = null
        var secretKey: DescriptorSecretKey? = null
        var descriptor: Descriptor? = null
        var passphraseBytes: ByteArray? = null
        try {
            val walletEntity = walletDao.getById(walletId)
                ?: return@withContext null
            
            val mnemonicStr = keystoreManager.getMnemonic(walletId)
                ?: return@withContext null
            
            val parsedMnemonic = Mnemonic.fromString(mnemonicStr)
            mnemonic = parsedMnemonic
            val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
            
            val derivedSecretKey = DescriptorSecretKey(network, parsedMnemonic, passphrase)
            secretKey = derivedSecretKey
            val scriptType = ScriptType.fromDescriptor(walletEntity.descriptor)
            val derivedDescriptor = ScriptType.createDescriptor(derivedSecretKey, scriptType, KeychainKind.EXTERNAL, network)
            descriptor = derivedDescriptor
            
            // Extract master fingerprint from descriptor
            val masterFpMatch = Regex("\\[([0-9a-fA-F]{8})/").find(derivedDescriptor.toString())
                ?: return@withContext null
            val hex = masterFpMatch.groupValues[1]
            val masterFpBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            
            // Compute legacy fallback bytes; the UI renders LifeHash from masterFpBytes.
            val encodedPassphrase = passphrase.toByteArray(Charsets.UTF_8)
            passphraseBytes = encodedPassphrase
            val identiconBytes = java.security.MessageDigest.getInstance("SHA-256").apply {
                update(masterFpBytes)
            }.digest(encodedPassphrase).sliceArray(0 until 8)
            
            Pair(identiconBytes, masterFpBytes)
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "getPassphraseFingerprint failed: ${e.message}")
            null
        } finally {
            passphraseBytes?.fill(0)
            closeSecretNativeResources(
                nativeCloseAction(descriptor) { it.close() },
                nativeCloseAction(secretKey) { it.destroy() },
                nativeCloseAction(mnemonic) { it.destroy() }
            )
        }
        }
    }

}
