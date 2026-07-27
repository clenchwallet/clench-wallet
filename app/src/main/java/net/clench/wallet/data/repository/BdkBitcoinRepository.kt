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
import net.clench.wallet.security.PsbtSafety
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
import org.bitcoindevkit.WordCount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
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
    private val torAwareHttpClient: TorAwareHttpClient
) : BitcoinRepository {

    private val maxHttpResponseChars = 2 * 1024 * 1024

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

    private fun closeWalletEntry(entry: WalletEntry) {
        // UniFFI wallet objects own native key/descriptors and must not be left for GC,
        // especially when a passphrase wallet is locked or a secret-bearing cache entry
        // is replaced.
        runCatching { entry.wallet.close() }
        runCatching { entry.persister.close() }
    }

    private fun cacheWallet(walletId: String, entry: WalletEntry) {
        walletCache.put(walletId, entry)?.let(::closeWalletEntry)
    }

    private fun cacheWalletIfAbsent(walletId: String, entry: WalletEntry): WalletEntry {
        val existing = walletCache.putIfAbsent(walletId, entry)
        if (existing != null) closeWalletEntry(entry)
        return existing ?: entry
    }

    private fun evictWallet(walletId: String) {
        walletCache.remove(walletId)?.let(::closeWalletEntry)
    }

    /** Dispose every cached native wallet/persister when the app leaves the foreground. */
    fun clearCachedWallets() {
        walletCache.keys.toList().forEach(::evictWallet)
    }

    private fun discardFailedWalletCreation(walletId: String) {
        evictWallet(walletId)
        runCatching { keystoreManager.deleteWalletSecrets(walletId) }
        val dbFile = context.getDatabasePath("wallet_${walletId}.db")
        listOf(
            dbFile,
            java.io.File(dbFile.path + "-wal"),
            java.io.File(dbFile.path + "-shm"),
            java.io.File(dbFile.path + "-journal")
        ).forEach { runCatching { it.delete() } }
    }

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
        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val secretDescriptor = if (passphrase.isNullOrBlank()) externalDescriptor.toStringWithSecret() else null
        val secretChangeDescriptor = if (passphrase.isNullOrBlank()) changeDescriptor.toStringWithSecret() else null

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)
        val wallet = try {
            Wallet(externalDescriptor, changeDescriptor, network, persister)
        } catch (e: Exception) {
            persister.close()
            throw e
        } finally {
            externalDescriptor.close()
            changeDescriptor.close()
        }
        cacheWallet(walletId, WalletEntry(wallet, persister))

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
            discardFailedWalletCreation(walletId)
            throw e
        }
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
            externalDescriptor.close()
            changeDescriptor.close()
            throw IllegalArgumentException("This seed phrase is already imported in your wallet list.")
        }

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)
        val wallet = try {
            Wallet(externalDescriptor, changeDescriptor, network, persister)
        } catch (e: Exception) {
            persister.close()
            throw e
        } finally {
            externalDescriptor.close()
            changeDescriptor.close()
        }
        cacheWallet(walletId, WalletEntry(wallet, persister))

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
            discardFailedWalletCreation(walletId)
            throw e
        }
    }

    override suspend fun importWatchOnly(
        name: String,
        descriptor: String,
        deviceType: String?
    ): WalletData = withContext(Dispatchers.IO) {
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
        val externalDescriptor = try {
            Descriptor(externalDescriptorStr, network)
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "importWatchOnly: external descriptor invalid")
            throw IllegalArgumentException("Invalid descriptor or extended public key. Please check the format and try again.\n\nDetails: ${e.message}")
        }
        val changeDescriptor = try {
            Descriptor(changeDescriptorStr, network)
        } catch (e: Exception) {
            externalDescriptor.close()
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "importWatchOnly: change descriptor invalid")
            throw IllegalArgumentException("Invalid descriptor or extended public key. Please check the format and try again.\n\nDetails: ${e.message}")
        }
        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()

        // Prevent duplicate imports — check current network only
        val existing = walletDao.getAllByNetwork(settingsManager.getNetwork())
        if (existing.any { it.descriptor == publicDescriptor }) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "importWatchOnly: duplicate descriptor found")
            externalDescriptor.close()
            changeDescriptor.close()
            throw IllegalArgumentException("A wallet with this descriptor is already in your wallet list.")
        }

        // Generate wallet ID
        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with SQLite persistence (no signing keys)
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)
        val wallet = try {
            Wallet(externalDescriptor, changeDescriptor, network, persister)
        } catch (e: Exception) {
            persister.close()
            throw e
        } finally {
            externalDescriptor.close()
            changeDescriptor.close()
        }
        cacheWallet(walletId, WalletEntry(wallet, persister))

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
        val mnemonicObj = Mnemonic.fromString(mnemonic.joinToString(" "))
        var secretKey: DescriptorSecretKey? = null
        var derivedExternalDescriptor: Descriptor? = null
        var derivedChangeDescriptor: Descriptor? = null
        try {
            val passphraseValue = passphrase.orEmpty()
            secretKey = DescriptorSecretKey(network, mnemonicObj, passphraseValue)
            val scriptType = ScriptType.fromDescriptor(walletEntity.descriptor)
            val externalDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.EXTERNAL, network)
            val changeDescriptor = ScriptType.createDescriptor(secretKey, scriptType, KeychainKind.INTERNAL, network)
            derivedExternalDescriptor = externalDescriptor
            derivedChangeDescriptor = changeDescriptor

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
            evictWallet(walletId)
            if (hasPassphrase) {
                val persister = Persister.newInMemory()
                val wallet = try {
                    Wallet(externalDescriptor, changeDescriptor, network, persister)
                } catch (e: Exception) {
                    persister.close()
                    throw e
                }
                cacheWallet(walletId, WalletEntry(wallet, persister))
                unlockedPassphraseWallets.add(walletId)
            }
        } finally {
            try { derivedExternalDescriptor?.close() } catch (_: Exception) {}
            try { derivedChangeDescriptor?.close() } catch (_: Exception) {}
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
            externalDescriptor.close()
            throw IllegalArgumentException("Invalid private change descriptor. Please check the format and try again.\n\nDetails: ${e.message}")
        }

        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val secretDescriptor = externalDescriptor.toStringWithSecret()
        val secretChangeDescriptor = changeDescriptor.toStringWithSecret()
        val isMultisigDescriptor = isMultisigDescriptor(publicDescriptor)

        // Prevent duplicate imports — compare public descriptors on the current network only.
        val activeNetwork = settingsManager.getNetwork()
        val existing = walletDao.getAllByNetwork(activeNetwork)
        if (existing.any { it.descriptor == publicDescriptor }) {
            externalDescriptor.close()
            changeDescriptor.close()
            throw IllegalArgumentException("A wallet with this descriptor is already in your wallet list.")
        }

        val walletId = UUID.randomUUID().toString()

        // Create BDK wallet with secret descriptors so this wallet can sign.
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)
        val wallet = try {
            Wallet(externalDescriptor, changeDescriptor, network, persister)
        } catch (e: Exception) {
            persister.close()
            throw e
        } finally {
            externalDescriptor.close()
            changeDescriptor.close()
        }
        cacheWallet(walletId, WalletEntry(wallet, persister))

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
            discardFailedWalletCreation(walletId)
            throw e
        }
    }

    override suspend fun syncWallet(walletId: String, config: ElectrumConfig?): WalletBalance = withContext(Dispatchers.IO) {
        // Offline mode — skip sync entirely, return cached balance
        if (settingsManager.isOfflineMode()) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "Offline mode — skipping sync")
            return@withContext getBalance(walletId)
        }

        // Passphrase wallet guard — never sync using the public descriptor (xpub) wallet.
        // Syncing the xpub against Electrum reveals real UTXO/tx history in the locked state,
        // which leaks wallet activity before the passphrase is entered. Only sync after unlock.
        val walletEntityForPassphraseCheck = walletDao.getById(walletId)
        if (walletEntityForPassphraseCheck?.hasPassphrase == true && !unlockedPassphraseWallets.contains(walletId)) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: SKIPPING $walletId — passphrase wallet is locked")
            return@withContext WalletBalance(0, 0, 0, 0)
        }

        // Cross-network guard — don't sync a wallet that belongs to a different network
        val walletEntity = walletEntityForPassphraseCheck
        val currentNetwork = settingsManager.getNetwork()
        if (walletEntity != null && walletEntity.network != currentNetwork) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "syncWallet: SKIPPING $walletId — wallet is ${walletEntity.network} but current network is $currentNetwork")
            return@withContext getBalance(walletId)
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
            val entry = loadWallet(walletId)
            val wallet = entry.wallet
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: wallet loaded OK")

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
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: creating ElectrumClient mode=${resolved.mode} (timeout=${timeoutMs}ms)")
                }
                val connectFuture = executor.submit(java.util.concurrent.Callable {
                    electrumConnectionFactory.createConnection(effectiveConfig)
                })
                try {
                    activeConnection = connectFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    connectFuture.cancel(true)
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "syncWallet: ElectrumClient connect TIMEOUT after ${timeoutMs}ms")
                    throw java.util.concurrent.TimeoutException("ElectrumClient connection timed out after ${timeoutMs}ms")
                } catch (e: java.util.concurrent.ExecutionException) {
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "syncWallet: ElectrumClient connect ERROR: ${e.cause?.message}")
                    throw e.cause ?: e
                }
                val electrumClient = activeConnection.client
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: ElectrumClient created OK (mode=${activeConnection.mode})")

                // Full scan with coroutine timeout (fullScan is also blocking but generally completes)
                withTimeout(timeoutMs) {
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: building fullScan request for $walletId")
                    val fullScanRequest = wallet.startFullScan().build()
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: starting fullScan (stopGap=20, batch=10)")
                    val update = electrumClient.fullScan(
                        fullScanRequest,
                        stopGap = 20uL,
                        batchSize = 10uL,
                        fetchPrevTxouts = true
                    )
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: fullScan complete, applying update")

                    wallet.applyUpdate(update)
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: update applied, persisting")

                    wallet.persist(entry.persister)
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "syncWallet: persisted OK")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "syncWallet: TIMEOUT for $walletId: ${e.message}")
                throw e
            } catch (e: java.util.concurrent.TimeoutException) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "syncWallet: CONNECT TIMEOUT for $walletId: ${e.message}")
                throw e
            } catch (e: Exception) {
                if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("BdkRepo", "syncWallet: ERROR for $walletId: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally {
                try { activeConnection?.close() } catch (_: Exception) {}
                executor.shutdownNow()
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

    override suspend fun recoverWalletState(walletId: String, stopGap: UInt): WalletStateRecoveryResult =
        withContext(Dispatchers.IO) {
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
                evictWallet(walletId)
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

                    val externalDescriptor = Descriptor(walletEntity.descriptor, network)
                    val changeDescriptor = Descriptor(walletEntity.changeDescriptor, network)
                    stateTransaction.markReplacementStateStarted()
                    val persister = Persister.newSqlite(dbFile.absolutePath)
                    val wallet = try {
                        Wallet(externalDescriptor, changeDescriptor, network, persister)
                    } catch (e: Exception) {
                        persister.close()
                        throw e
                    } finally {
                        externalDescriptor.close()
                        changeDescriptor.close()
                    }
                    replacementEntry = WalletEntry(wallet, persister)
                    val activeConnection = electrumConnectionFactory.createConnection(settingsManager.loadElectrumConfig())
                    try {
                        val request = wallet.startFullScan().build()
                        val update = activeConnection.client.fullScan(
                            request,
                            stopGap = stopGap.toULong(),
                            batchSize = 10uL,
                            fetchPrevTxouts = true
                        )
                        wallet.applyUpdate(update)
                        wallet.persist(persister)
                    } finally {
                        activeConnection.close()
                    }

                    cacheWallet(walletId, checkNotNull(replacementEntry))
                    replacementEntry = null
                    val balance = wallet.balance()
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
                } catch (e: Exception) {
                    evictWallet(walletId)
                    replacementEntry?.let(::closeWalletEntry)
                    replacementEntry = null
                    stateTransaction.rollback(e)
                    throw IllegalStateException(
                        "Extended wallet-state recovery scan failed. The original database was restored and no wallet state was deleted.",
                        e
                    )
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
        try {
            wallet.sign(psbt)
            return@withContext serializeFinalTransaction(psbt)
        } catch (e: Exception) {
            runCatching { psbt.close() }
            throw e
        }
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
            tx.close()
            activeConnection.close()
        }
    }

    override suspend fun inspectBuiltTransaction(
        walletId: String,
        txHex: String
    ): BuiltTransactionReview = withContext(Dispatchers.IO) {
        val txBytes = txHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val tx = Transaction(txBytes)
        try {
            inspectTransaction(walletId, tx)
        } finally {
            tx.close()
        }
    }

    override suspend fun inspectPsbt(
        walletId: String,
        psbtBase64: String
    ): BuiltTransactionReview = withContext(Dispatchers.IO) {
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
                    knownFeeSat = psbt.fee().toLong(),
                    vsizeOverride = estimatedFinalVsize,
                    vsizeIsEstimate = estimatedFinalVsize != null
                )
            } finally {
                tx.close()
            }
        } finally {
            psbt.close()
        }
    }

    private suspend fun inspectTransaction(
        walletId: String,
        tx: Transaction,
        knownFeeSat: Long? = null,
        vsizeOverride: Long? = null,
        vsizeIsEstimate: Boolean = false
    ): BuiltTransactionReview {
        val wallet = loadWallet(walletId).wallet
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

    override suspend fun deleteWallet(walletId: String) {
        // Remove from cache first
        evictWallet(walletId)

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

    override suspend fun getAddresses(walletId: String, count: Int): List<DomainAddress> = withContext(Dispatchers.IO) {
        getAddresses(walletId, KeychainKind.EXTERNAL, count)
    }

    override suspend fun getAddresses(walletId: String, keychain: KeychainKind, count: Int): List<DomainAddress> = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
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
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "Electrum fee estimation error: ${e.message}")
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

    override suspend fun bumpFee(walletId: String, txid: String, newFeeRate: Float): String = withContext(Dispatchers.IO) {
        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val feeRate = validatedFeeRate(newFeeRate)

        val psbt = org.bitcoindevkit.BumpFeeTxBuilder(org.bitcoindevkit.Txid.fromString(txid), feeRate)
            .finish(wallet)

        try {
            // Sign the bumped transaction and durably persist the replacement state.
            wallet.sign(psbt)
            wallet.persist(entry.persister)
            serializeFinalTransaction(psbt)
        } catch (e: Exception) {
            runCatching { psbt.close() }
            throw e
        }
    }

    override suspend fun cancelTransaction(walletId: String, txid: String, newFeeRate: Float): String = withContext(Dispatchers.IO) {
        val walletEntity = walletDao.getById(walletId)
        require(walletEntity?.isWatchOnly != true) { "Watch-only wallets must cancel via their external signer." }

        val entry = loadWallet(walletId)
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
            try {
                wallet.sign(psbt)
                wallet.persist(entry.persister)
                serializeFinalTransaction(psbt)
            } catch (e: Exception) {
                runCatching { psbt.close() }
                throw e
            }
        } finally {
            originalTx.close()
        }
    }

    override suspend fun listUnspent(walletId: String): List<net.clench.wallet.domain.model.UtxoInfo> = withContext(Dispatchers.IO) {
        // Passphrase wallet guard — same as getTransactions().
        // Never expose UTXOs from the public descriptor (xpub) wallet in the locked state.
        val walletEntity = walletDao.getById(walletId)
        if (walletEntity?.hasPassphrase == true && !unlockedPassphraseWallets.contains(walletId)) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "listUnspent: passphrase wallet $walletId is locked — returning empty list")
            return@withContext emptyList()
        }

        val entry = loadWallet(walletId)
        val wallet = entry.wallet
        val utxos = wallet.listUnspent()
        // [S-4] Gate: UTXO count and unlock status expose wallet balance info
        if (logSensitive) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "listUnspent: walletId=${walletId.take(8)} rawUtxoCount=${utxos.size} unlocked=${unlockedPassphraseWallets.contains(walletId)}")
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
            psbt.close()
        }
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
        try {
            wallet.sign(psbt)
            serializeFinalTransaction(psbt)
        } catch (e: Exception) {
            runCatching { psbt.close() }
            throw e
        }
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
            psbt.close()
        }
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
        try {
            val tx = if (signedPsbt != null) {
                // Finalize the PSBT before comparing the resulting transaction to
                // the original unsigned PSBT. This is stricter than comparing PSBT
                // metadata and covers QR, NFC, and file-import paths uniformly.
                val finalizeResult = signedPsbt.finalize()
                if (!finalizeResult.couldFinalize) {
                    val errorMsgs = finalizeResult.errors?.joinToString(", ") { it.toString() } ?: "Unknown error"
                    finalizeResult.psbt.close()
                    throw IllegalStateException("Could not finalize PSBT: $errorMsgs")
                }
                try {
                    finalizeResult.psbt.extractTx()
                } finally {
                    finalizeResult.psbt.close()
                }
            } else {
                // Not a PSBT; treat it as a finalized raw transaction payload.
                Transaction(decodeTransactionPayload(signedPsbtBase64))
            }

            try {
                validateTransactionMatchesUnsignedPsbt(unsignedPsbtBase64, tx)
                val config = settingsManager.loadElectrumConfig()
                val activeConnection = electrumConnectionFactory.createConnection(config)
                try {
                    activeConnection.client.transactionBroadcast(tx).toString()
                } finally {
                    activeConnection.close()
                }
            } finally {
                tx.close()
            }
        } finally {
            signedPsbt?.close()
        }
    }

    override suspend fun mergeSignedPsbt(
        unsignedPsbtBase64: String,
        currentPsbtBase64: String,
        signedPsbtPayload: String
    ): PsbtSigningProgress = withContext(Dispatchers.IO) {
        val returnedPsbt = parseSignedPsbtPayload(signedPsbtPayload)

        if (returnedPsbt == null) {
            val tx = Transaction(decodeTransactionPayload(signedPsbtPayload))
            try {
                validateTransactionMatchesUnsignedPsbt(unsignedPsbtBase64, tx)
                return@withContext PsbtSigningProgress(
                    psbtBase64 = signedPsbtPayload.trim(),
                    readyToBroadcast = true,
                    message = "Clench imported a finalized transaction and verified it matches the original PSBT."
                )
            } finally {
                tx.close()
            }
        }

        val currentPsbtPayload = currentPsbtBase64.ifBlank { unsignedPsbtBase64 }
        PsbtSafety.inspectBase64(currentPsbtPayload)
        val currentPsbt = Psbt(currentPsbtPayload)
        var mergedPsbt: Psbt? = null
        var finalizedPsbt: Psbt? = null
        try {
            val signatureCountBefore = signatureMaterialCount(currentPsbt)
            mergedPsbt = try {
                currentPsbt.combine(returnedPsbt)
            } catch (e: Exception) {
                throw IllegalStateException("Signed PSBT could not be merged with the current PSBT: ${e.message}")
            }

            validatePsbtMatchesUnsignedPsbt(unsignedPsbtBase64, mergedPsbt)
            val mergedSerialized = mergedPsbt.serialize()
            val signatureCountAfter = signatureMaterialCount(mergedPsbt)
            val finalizeResult = mergedPsbt.finalize()
            finalizedPsbt = finalizeResult.psbt

            if (finalizeResult.couldFinalize) {
                val finalizedTx = finalizedPsbt.extractTx()
                try {
                    validateTransactionMatchesUnsignedPsbt(unsignedPsbtBase64, finalizedTx)
                } finally {
                    finalizedTx.close()
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
            finalizedPsbt?.close()
            mergedPsbt?.close()
            currentPsbt.close()
            returnedPsbt.close()
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
    private fun validateTransactionMatchesUnsignedPsbt(unsignedBase64: String, signedTx: Transaction) {
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
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "PSBT validation passed: complete unsigned transaction policy matches")
        } finally {
            unsignedTx?.close()
            unsigned.close()
        }
    }

    private fun validatePsbtMatchesUnsignedPsbt(unsignedBase64: String, candidate: Psbt) {
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
            actualTx?.close()
            expectedTx?.close()
            unsigned.close()
        }
    }

    private fun signatureMaterialCount(psbt: Psbt): Int {
        return psbt.input().sumOf { input ->
            input.partialSigs.size +
                input.tapScriptSigs.size +
                (input.finalScriptWitness?.size ?: 0) +
                (if (input.tapKeySig?.isNotEmpty() == true) 1 else 0) +
                (if (input.finalScriptSig?.toBytes()?.isNotEmpty() == true) 1 else 0)
        }
    }

    private fun fingerprintTransaction(tx: Transaction): TransactionFingerprint {
        val inputs = tx.input().map { input ->
            val previousOutput = input.previousOutput
            "${previousOutput.txid}:${previousOutput.vout}"
        }
        val sequences = tx.input().map { it.sequence.toLong() }
        val outputs = tx.output().map { output ->
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
        try {
            val tx = psbt.extractTx()
            try {
                return tx.serialize().toHexString()
            } finally {
                tx.close()
            }
        } finally {
            psbt.close()
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
            val wallet = try {
                Wallet(externalDescriptor, changeDescriptor, network, persister)
            } catch (e: Exception) {
                persister.close()
                throw e
            } finally {
                externalDescriptor.close()
                changeDescriptor.close()
            }
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "loadWallet: passphrase wallet $walletId — using in-memory persister (locked state)")
            val entry = WalletEntry(wallet, persister)
            return cacheWalletIfAbsent(walletId, entry)
        }

        // Non-passphrase wallets: load from SQLite (if exists, else create new)
        val dbFile = context.getDatabasePath("wallet_${walletId}.db")
        val hadExistingDb = dbFile.exists()
        val dbPath = dbFile.absolutePath
        val persister = Persister.newSqlite(dbPath)

        val wallet = try {
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
        } catch (e: Exception) {
            persister.close()
            throw e
        } finally {
            externalDescriptor.close()
            changeDescriptor.close()
        }

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
        val entry = WalletEntry(wallet, persister)
        return cacheWalletIfAbsent(walletId, entry)
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
    ): WalletData = withContext(Dispatchers.IO) {
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

        // Parse descriptors through BDK to validate
        val externalDescriptor = try {
            Descriptor(externalDescriptorStr, network)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid multisig descriptor: ${e.message}")
        }
        val changeDescriptor = try {
            Descriptor(changeDescriptorStr, network)
        } catch (e: Exception) {
            externalDescriptor.close()
            throw IllegalArgumentException("Invalid multisig change descriptor: ${e.message}")
        }
        val signingExternalDescriptor = signingExternalDescriptorStr?.let { descriptor ->
            try {
                Descriptor(descriptor, network)
            } catch (e: Exception) {
                externalDescriptor.close()
                changeDescriptor.close()
                throw IllegalArgumentException("Invalid phone-signer descriptor: ${e.message}")
            }
        }
        val signingChangeDescriptor = signingChangeDescriptorStr?.let { descriptor ->
            try {
                Descriptor(descriptor, network)
            } catch (e: Exception) {
                signingExternalDescriptor?.close()
                externalDescriptor.close()
                changeDescriptor.close()
                throw IllegalArgumentException("Invalid phone-signer change descriptor: ${e.message}")
            }
        }
        val publicDescriptor = externalDescriptor.toString()
        val publicChangeDescriptor = changeDescriptor.toString()
        val signingSecretDescriptor = signingExternalDescriptor?.toStringWithSecret()
        val signingSecretChangeDescriptor = signingChangeDescriptor?.toStringWithSecret()
        signingExternalDescriptor?.close()
        signingChangeDescriptor?.close()

        // Prevent duplicate imports
        val activeNetwork = if (network == Network.TESTNET) "testnet" else "mainnet"
        val existing = walletDao.getAllByNetwork(activeNetwork)
        if (existing.any { it.descriptor == publicDescriptor }) {
            externalDescriptor.close()
            changeDescriptor.close()
            throw IllegalArgumentException("A wallet with this multisig configuration already exists.")
        }

        // Generate wallet ID and create BDK wallet
        val walletId = UUID.randomUUID().toString()
        val dbPath = context.getDatabasePath("wallet_${walletId}.db").absolutePath
        val persister = Persister.newSqlite(dbPath)
        val wallet = try {
            Wallet(externalDescriptor, changeDescriptor, network, persister)
        } catch (e: Exception) {
            persister.close()
            throw e
        } finally {
            externalDescriptor.close()
            changeDescriptor.close()
        }
        cacheWallet(walletId, WalletEntry(wallet, persister))

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
            discardFailedWalletCreation(walletId)
            throw e
        }
    }

    override suspend fun generateMultisigPhoneSigner(): GeneratedMultisigPhoneSigner = withContext(Dispatchers.IO) {
        val network = activeNetwork()
        val isTestnet = network == Network.TESTNET
        val derivationPath = if (isTestnet) "m/48'/1'/0'/2'" else "m/48'/0'/0'/2'"
        val mnemonic = Mnemonic(WordCount.WORDS24)
        val rootSecretKey = DescriptorSecretKey(network, mnemonic, "")
        val accountPath = DerivationPath(derivationPath)
        var accountSecretKey: DescriptorSecretKey? = null
        var accountPublicKey: org.bitcoindevkit.DescriptorPublicKey? = null
        var rootPublicKey: org.bitcoindevkit.DescriptorPublicKey? = null
        try {
            accountSecretKey = rootSecretKey.derive(accountPath)
            accountPublicKey = accountSecretKey.asPublic()
            rootPublicKey = rootSecretKey.asPublic()
            val fingerprint = rootPublicKey.masterFingerprint().uppercase(Locale.US)
            val publicKey = originWrapAccountKey(accountPublicKey.toString(), fingerprint, derivationPath)
            val secretKey = originWrapAccountKey(accountSecretKey.toString(), fingerprint, derivationPath)
            GeneratedMultisigPhoneSigner(
                mnemonicWords = mnemonic.toString().split(" "),
                xpubWithOrigin = publicKey,
                accountXprvWithOrigin = secretKey,
                fingerprint = fingerprint,
                derivationPath = derivationPath
            )
        } finally {
            try { accountPublicKey?.destroy() } catch (_: Exception) {}
            try { accountSecretKey?.destroy() } catch (_: Exception) {}
            try { rootPublicKey?.destroy() } catch (_: Exception) {}
            try { accountPath.destroy() } catch (_: Exception) {}
            try { rootSecretKey.destroy() } catch (_: Exception) {}
            try { mnemonic.destroy() } catch (_: Exception) {}
        }
    }

    override suspend fun hasMultisigPhoneSigner(walletId: String): Boolean = withContext(Dispatchers.IO) {
        val walletEntity = walletDao.getById(walletId) ?: return@withContext false
        walletEntity.isMultisig &&
            keystoreManager.getSecretDescriptor(walletId) != null &&
            keystoreManager.getSecretChangeDescriptor(walletId) != null
    }

    override suspend fun signMultisigPsbtWithPhoneKeys(walletId: String, psbtBase64: String): String = withContext(Dispatchers.IO) {
        PsbtSafety.inspectBase64(psbtBase64)
        val walletEntity = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        require(walletEntity.isMultisig) { "Phone signer PSBT signing is only available for multisig wallets" }
        val externalSecret = keystoreManager.getSecretDescriptor(walletId)
            ?: throw IllegalStateException("No Clench phone signer keys are stored for this wallet")
        val changeSecret = keystoreManager.getSecretChangeDescriptor(walletId)
            ?: throw IllegalStateException("No Clench phone signer change keys are stored for this wallet")
        val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
        val externalDescriptor = Descriptor(externalSecret, network)
        val changeDescriptor = try {
            Descriptor(changeSecret, network)
        } catch (e: Exception) {
            externalDescriptor.close()
            throw e
        }
        val persister = Persister.newInMemory()
        val signingWallet = try {
            Wallet(externalDescriptor, changeDescriptor, network, persister)
        } catch (e: Exception) {
            persister.close()
            throw e
        } finally {
            externalDescriptor.close()
            changeDescriptor.close()
        }
        val psbt = try {
            Psbt(psbtBase64)
        } catch (e: Exception) {
            signingWallet.close()
            persister.close()
            throw e
        }
        try {
            signingWallet.sign(psbt)
            psbt.serialize()
        } finally {
            psbt.close()
            signingWallet.close()
            persister.close()
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
    override suspend fun unlockPassphraseWallet(walletId: String, passphrase: String): Unit = withContext(Dispatchers.IO) {
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
        
        val mnemonic = Mnemonic.fromString(mnemonicStr)
        val network = if (walletEntity.network == "testnet") Network.TESTNET else Network.BITCOIN
        
        // Derive secret descriptors with the passphrase
        val secretKey = DescriptorSecretKey(network, mnemonic, passphrase)
        val scriptType = ScriptType.fromDescriptor(walletEntity.descriptor)
        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "unlockPassphraseWallet: scriptType=$scriptType network=$network")
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
        // The user identifies the correct wallet by recognising the fingerprint image.
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
        val wallet = try {
            Wallet(externalDescriptor, changeDescriptor, network, persister)
        } catch (e: Exception) {
            persister.close()
            throw e
        } finally {
            externalDescriptor.close()
            changeDescriptor.close()
        }

        // Cache the in-memory wallet for this session and mark as explicitly unlocked.
        // unlockedPassphraseWallets is the authoritative unlock signal — walletCache alone
        // is not sufficient because loadWallet() pre-populates it with the public-xpub wallet.
        cacheWallet(walletId, WalletEntry(wallet, persister))
        unlockedPassphraseWallets.add(walletId)
        
        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "unlockPassphraseWallet: unlocked wallet $walletId")
    }

    /**
     * Lock a passphrase wallet by evicting the cached secret wallet.
     * The public-key-only version remains available for viewing balance/addresses.
     */
    override fun lockPassphraseWallet(walletId: String) {
        // Remove from unlock tracking set first
        unlockedPassphraseWallets.remove(walletId)
        // Close and discard the in-memory wallet — all session data is destroyed
        evictWallet(walletId)
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
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "lockPassphraseWallet: deleted on-disk DB for $walletId")
        } catch (e: Exception) {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("BdkRepo", "lockPassphraseWallet: failed to delete DB: ${e.message}")
        }
        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("BdkRepo", "lockPassphraseWallet: locked and discarded in-memory wallet $walletId")
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
     * @return Pair(legacyIdenticonBytes [8], masterFingerprintBytes [4]) or null on error
     */
    suspend fun getPassphraseFingerprint(walletId: String, passphrase: String): Pair<ByteArray, ByteArray>? = withContext(Dispatchers.IO) {
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
            try { descriptor?.close() } catch (_: Exception) {}
            try { secretKey?.destroy() } catch (_: Exception) {}
            try { mnemonic?.destroy() } catch (_: Exception) {}
        }
    }

}
