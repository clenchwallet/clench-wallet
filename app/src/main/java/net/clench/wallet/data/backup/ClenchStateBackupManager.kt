package net.clench.wallet.data.backup

import android.util.Base64
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.entity.TransactionLabelEntity
import net.clench.wallet.data.local.entity.UtxoMetadataEntity
import net.clench.wallet.data.local.entity.WalletEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClenchStateBackupManager @Inject constructor(
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
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "This is not a Clench state backup file." }
        require(root.optInt("version") == VERSION) { "Unsupported Clench backup version." }

        root.optJSONObject("settings")?.let { settingsManager.importBackupSettings(it) }

        val existingWallets = walletDao.getAll()
        val knownByDescriptor = existingWallets.associateBy { descriptorKey(it.descriptor, it.network) }.toMutableMap()
        val existingIds = existingWallets.map { it.id }.toMutableSet()
        val walletIdMap = mutableMapOf<String, String>()

        var importedWallets = 0
        var skippedWallets = 0
        var hotWalletsNeedingSeed = 0

        val wallets = root.optJSONArray("wallets") ?: JSONArray()
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
                name = item.optString("name", "Restored Wallet"),
                descriptor = descriptor,
                changeDescriptor = changeDescriptor,
                isWatchOnly = true,
                isMultisig = item.optBoolean("isMultisig", false),
                createdAtEpochMs = item.optLong("createdAtEpochMs", System.currentTimeMillis()),
                network = network,
                preferredHardwareWallet = item.optNullableString("preferredHardwareWallet"),
                hasPassphrase = false,
                identiconBytes = item.optNullableString("identiconBytes")?.let { Base64.decode(it, Base64.NO_WRAP) },
                masterFingerprint = item.optNullableString("masterFingerprint"),
                derivationPath = item.optNullableString("derivationPath"),
                importedViaDevice = item.optNullableString("importedViaDevice")
            )
            walletDao.insert(restoredWallet)
            knownByDescriptor[descriptorKey] = restoredWallet
            importedWallets++
        }

        var importedLabels = 0
        val labels = root.optJSONArray("transactionLabels") ?: JSONArray()
        for (i in 0 until labels.length()) {
            val item = labels.getJSONObject(i)
            val targetWalletId = walletIdMap[item.optString("walletId")] ?: continue
            val txid = item.optString("txid")
            val label = item.optString("label")
            if (txid.isBlank() || label.isBlank()) continue
            transactionLabelDao.upsert(
                TransactionLabelEntity(
                    key = "$targetWalletId:$txid",
                    walletId = targetWalletId,
                    txid = txid,
                    label = label,
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
                )
            )
            importedLabels++
        }

        var importedUtxoMetadata = 0
        val utxoMetadata = root.optJSONArray("utxoMetadata") ?: JSONArray()
        for (i in 0 until utxoMetadata.length()) {
            val item = utxoMetadata.getJSONObject(i)
            val targetWalletId = walletIdMap[item.optString("walletId")] ?: continue
            val outpoint = item.optString("outpoint")
            if (outpoint.isBlank()) continue
            utxoMetadataDao.upsert(
                UtxoMetadataEntity(
                    outpoint = outpoint,
                    walletId = targetWalletId,
                    label = item.optNullableString("label"),
                    isFrozen = item.optBoolean("isFrozen", false)
                )
            )
            importedUtxoMetadata++
        }

        return ImportResult(
            importedWallets = importedWallets,
            skippedWallets = skippedWallets,
            importedLabels = importedLabels,
            importedUtxoMetadata = importedUtxoMetadata,
            hotWalletsNeedingSeed = hotWalletsNeedingSeed
        )
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
    }
}
