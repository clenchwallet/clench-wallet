package net.clench.wallet.data.backup

import android.util.Base64
import androidx.room.withTransaction
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.entity.TransactionLabelEntity
import net.clench.wallet.data.local.entity.UtxoMetadataEntity
import net.clench.wallet.data.local.entity.WalletEntity
import net.clench.wallet.data.repository.MultisigDescriptorSafety
import net.clench.wallet.domain.model.toNetworkKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.Network
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClenchStateBackupManager @Inject constructor(
    private val database: ClenchDatabase,
    private val walletDao: WalletDao,
    private val transactionLabelDao: TransactionLabelDao,
    private val utxoMetadataDao: UtxoMetadataDao,
    private val settingsManager: SettingsManager
) {
    data class ImportResult(
        val importedWallets: Int,
        val skippedWallets: Int,
        val importedLabels: Int,
        val importedUtxoMetadata: Int,
        val hotWalletsNeedingSeed: Int
    ) {
        fun toUserMessage(): String {
            val parts = mutableListOf<String>()
            parts += "Imported $importedWallets wallet${if (importedWallets == 1) "" else "s"}"
            if (skippedWallets > 0) parts += "skipped $skippedWallets duplicate${if (skippedWallets == 1) "" else "s"}"
            if (importedLabels > 0) parts += "restored $importedLabels label${if (importedLabels == 1) "" else "s"}"
            if (importedUtxoMetadata > 0) parts += "restored $importedUtxoMetadata UTXO note${if (importedUtxoMetadata == 1) "" else "s"}"
            if (hotWalletsNeedingSeed > 0) {
                parts += "$hotWalletsNeedingSeed hot wallet${if (hotWalletsNeedingSeed == 1) "" else "s"} restored watch-only until the matching seed phrase is re-entered"
            }
            return parts.joinToString("; ")
        }
    }

    suspend fun exportStateBackupJson(): String {
        val wallets = walletDao.getAll()
        val walletJson = JSONArray()
        val labelJson = JSONArray()
        val utxoJson = JSONArray()

        wallets.forEach { wallet ->
            val network = wallet.network.toBdkNetwork()
            requirePublicDescriptor(wallet.descriptor, network, "receive")
            requirePublicDescriptor(wallet.changeDescriptor, network, "change")
            walletJson.put(JSONObject().apply {
                put("id", wallet.id)
                put("name", wallet.name)
                put("descriptor", wallet.descriptor)
                put("changeDescriptor", wallet.changeDescriptor)
                put("isWatchOnly", wallet.isWatchOnly)
                put("isMultisig", wallet.isMultisig)
                put("createdAtEpochMs", wallet.createdAtEpochMs)
                put("network", wallet.network)
                putNullable("preferredHardwareWallet", wallet.preferredHardwareWallet)
                put("hasPassphrase", wallet.hasPassphrase)
                putNullable("identiconBytes", wallet.identiconBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                putNullable("masterFingerprint", wallet.masterFingerprint)
                putNullable("derivationPath", wallet.derivationPath)
                putNullable("importedViaDevice", wallet.importedViaDevice)
                put("restoreRequiresSeedPhrase", !wallet.isWatchOnly && !wallet.isMultisig)
            })

            transactionLabelDao.getForWallet(wallet.id).forEach { label ->
                labelJson.put(JSONObject().apply {
                    put("walletId", label.walletId)
                    put("txid", label.txid)
                    put("label", label.label)
                    put("updatedAt", label.updatedAt)
                })
            }

            utxoMetadataDao.getForWallet(wallet.id).forEach { meta ->
                utxoJson.put(JSONObject().apply {
                    put("walletId", meta.walletId)
                    put("outpoint", meta.outpoint)
                    putNullable("label", meta.label)
                    put("isFrozen", meta.isFrozen)
                })
            }
        }

        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("secretsIncluded", false)
            put("warning", "This backup excludes seed phrases, private descriptors, PINs, biometric secrets, and passphrases.")
            put("settings", settingsManager.exportBackupSettings())
            put("wallets", walletJson)
            put("transactionLabels", labelJson)
            put("utxoMetadata", utxoJson)
        }.toString(2)
    }

    suspend fun importStateBackupJson(json: String): ImportResult {
        require(json.length <= MAX_BACKUP_CHARS) { "Backup file is too large." }
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "This is not a Clench state backup file." }
        require(root.optInt("version") == VERSION) { "Unsupported Clench backup version." }
        require(!root.optBoolean("secretsIncluded", false)) {
            "Backups containing wallet secrets are not accepted."
        }

        val wallets = root.optJSONArray("wallets") ?: JSONArray()
        val labels = root.optJSONArray("transactionLabels") ?: JSONArray()
        val utxoMetadata = root.optJSONArray("utxoMetadata") ?: JSONArray()
        require(wallets.length() <= MAX_WALLETS) { "Backup contains too many wallets." }
        require(labels.length() <= MAX_LABELS) { "Backup contains too many transaction labels." }
        require(utxoMetadata.length() <= MAX_UTXO_METADATA) { "Backup contains too many UTXO records." }

        validateBackupWallets(wallets)

        val result = database.withTransaction {

        val existingWallets = walletDao.getAll()
        val knownByDescriptor = existingWallets.associateBy { descriptorKey(it.descriptor, it.network) }.toMutableMap()
        val existingIds = existingWallets.map { it.id }.toMutableSet()
        val walletIdMap = mutableMapOf<String, String>()

        var importedWallets = 0
        var skippedWallets = 0
        var hotWalletsNeedingSeed = 0

        for (i in 0 until wallets.length()) {
            val item = wallets.getJSONObject(i)
            val originalId = item.optString("id").ifBlank { UUID.randomUUID().toString() }
            val descriptor = item.getString("descriptor")
            val changeDescriptor = item.getString("changeDescriptor")
            val network = item.optString("network", "mainnet")
            val descriptorKey = descriptorKey(descriptor, network)
            val duplicate = knownByDescriptor[descriptorKey]
            if (duplicate != null) {
                walletIdMap[originalId] = duplicate.id
                skippedWallets++
                continue
            }

            val restoredId = if (originalId in existingIds) UUID.randomUUID().toString() else originalId
            existingIds += restoredId
            walletIdMap[originalId] = restoredId

            val originallyHot = item.optBoolean("restoreRequiresSeedPhrase", false)
            if (originallyHot) hotWalletsNeedingSeed++

            val restoredWallet = WalletEntity(
                id = restoredId,
                name = item.optString("name", "Restored Wallet").take(MAX_NAME_CHARS),
                descriptor = descriptor,
                changeDescriptor = changeDescriptor,
                isWatchOnly = true,
                isMultisig = item.optBoolean("isMultisig", false),
                createdAtEpochMs = item.optLong("createdAtEpochMs", System.currentTimeMillis()),
                network = network,
                preferredHardwareWallet = item.optNullableString("preferredHardwareWallet"),
                hasPassphrase = false,
                identiconBytes = item.optNullableString("identiconBytes")?.let {
                    Base64.decode(it, Base64.NO_WRAP).also { bytes ->
                        require(bytes.size <= MAX_IDENTICON_BYTES) { "Wallet identicon is too large." }
                    }
                },
                masterFingerprint = item.optNullableString("masterFingerprint"),
                derivationPath = item.optNullableString("derivationPath"),
                importedViaDevice = item.optNullableString("importedViaDevice")
            )
            walletDao.insert(restoredWallet)
            knownByDescriptor[descriptorKey] = restoredWallet
            importedWallets++
        }

        var importedLabels = 0
        for (i in 0 until labels.length()) {
            val item = labels.getJSONObject(i)
            val targetWalletId = walletIdMap[item.optString("walletId")] ?: continue
            val txid = item.optString("txid")
            val label = item.optString("label")
            if (!txid.matches(TXID_REGEX) || label.isBlank()) continue
            transactionLabelDao.upsert(
                TransactionLabelEntity(
                    key = "$targetWalletId:$txid",
                    walletId = targetWalletId,
                    txid = txid,
                    label = label.take(MAX_LABEL_CHARS),
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
                )
            )
            importedLabels++
        }

        var importedUtxoMetadata = 0
        for (i in 0 until utxoMetadata.length()) {
            val item = utxoMetadata.getJSONObject(i)
            val targetWalletId = walletIdMap[item.optString("walletId")] ?: continue
            val outpoint = item.optString("outpoint")
            if (!outpoint.matches(OUTPOINT_REGEX)) continue
            utxoMetadataDao.upsert(
                UtxoMetadataEntity(
                    outpoint = outpoint,
                    walletId = targetWalletId,
                    label = item.optNullableString("label")?.take(MAX_LABEL_CHARS),
                    isFrozen = item.optBoolean("isFrozen", false)
                )
            )
            importedUtxoMetadata++
        }

        ImportResult(
            importedWallets = importedWallets,
            skippedWallets = skippedWallets,
            importedLabels = importedLabels,
            importedUtxoMetadata = importedUtxoMetadata,
            hotWalletsNeedingSeed = hotWalletsNeedingSeed
        )
        }

        // Apply only non-security presentation preferences after the database transaction
        // succeeds. Network, Electrum, Tor, offline, lock, and biometric policy require
        // explicit changes in Settings and are never silently replaced by a backup file.
        root.optJSONObject("settings")?.let { settingsManager.importBackupSettings(it) }
        return result
    }

    private fun validateBackupWallets(wallets: JSONArray) {
        for (i in 0 until wallets.length()) {
            val item = wallets.getJSONObject(i)
            val descriptor = item.getString("descriptor").trim()
            val changeDescriptor = item.getString("changeDescriptor").trim()
            require(descriptor.length <= MAX_DESCRIPTOR_CHARS && changeDescriptor.length <= MAX_DESCRIPTOR_CHARS) {
                "Wallet descriptor is too large."
            }
            val networkName = item.optString("network", "mainnet")
            require(networkName == "mainnet" || networkName == "testnet") { "Backup wallet has an invalid network." }
            val network = networkName.toBdkNetwork()
            requirePublicDescriptor(descriptor, network, "receive")
            requirePublicDescriptor(changeDescriptor, network, "change")
            MultisigDescriptorSafety.validate(descriptor)
            MultisigDescriptorSafety.validate(changeDescriptor)
        }
    }

    private fun requirePublicDescriptor(descriptor: String, network: Network, label: String) {
        require(!containsPrivateKeyMaterial(descriptor)) {
            "Backup contains a private descriptor. Clench state backups must be watch-only."
        }
        val parsed = runCatching { Descriptor(descriptor, network.toNetworkKind()) }
            .getOrElse { throw IllegalArgumentException("Backup contains an invalid $label descriptor.", it) }
        try {
            // BDK structurally separates secret and public descriptor material. This catches
            // private-key encodings that a prefix/WIF regex does not know about.
            require(parsed.toStringWithSecret() == parsed.toString()) {
                "Backup contains a private descriptor. Clench state backups must be watch-only."
            }
        } finally {
            parsed.close()
        }
    }

    private fun String.toBdkNetwork(): Network = when (this) {
        "mainnet" -> Network.BITCOIN
        "testnet" -> Network.TESTNET
        else -> throw IllegalArgumentException("Backup wallet has an invalid network.")
    }

    private fun descriptorKey(descriptor: String, network: String): String {
        return "${descriptor.substringBefore("#").trim()}|$network"
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value == null) put(name, JSONObject.NULL) else put(name, value)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (!has(name) || isNull(name)) null else optString(name)
    }

    companion object {
        private const val FORMAT = "clench-state-backup"
        private const val VERSION = 1
        const val MAX_BACKUP_CHARS = 2_000_000
        private const val MAX_WALLETS = 250
        private const val MAX_LABELS = 25_000
        private const val MAX_UTXO_METADATA = 25_000
        private const val MAX_NAME_CHARS = 100
        private const val MAX_LABEL_CHARS = 500
        private const val MAX_DESCRIPTOR_CHARS = 20_000
        private const val MAX_IDENTICON_BYTES = 4_096
        private val PRIVATE_EXTENDED_KEY_REGEX = Regex("(?i)[xyzuvt]prv[1-9A-HJ-NP-Za-km-z]+")
        private val WIF_REGEX = Regex("(?:^|[^1-9A-HJ-NP-Za-km-z])[KL5c9][1-9A-HJ-NP-Za-km-z]{50,51}(?:$|[^1-9A-HJ-NP-Za-km-z])")
        private val TXID_REGEX = Regex("(?i)^[0-9a-f]{64}$")
        private val OUTPOINT_REGEX = Regex("(?i)^[0-9a-f]{64}:[0-9]{1,10}$")

        internal fun containsPrivateKeyMaterial(descriptor: String): Boolean =
            PRIVATE_EXTENDED_KEY_REGEX.containsMatchIn(descriptor) || WIF_REGEX.containsMatchIn(descriptor)
    }
}
