package net.clench.wallet.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.clench.wallet.data.local.dao.WalletKeystoreMetadataDao
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.entity.TransactionLabelEntity
import net.clench.wallet.data.local.entity.WalletKeystoreMetadataEntity
import net.clench.wallet.data.util.Bip329
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.util.DescriptorDisplayPolicy
import net.clench.wallet.ui.util.copyToClipboardWithAutoClear
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class WalletInfoViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val transactionLabelDao: TransactionLabelDao,
    private val walletKeystoreMetadataDao: WalletKeystoreMetadataDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class MultisigKeystoreInfo(
        val keyId: String,
        val label: String,
        val masterFingerprint: String?,
        val derivationPath: String?,
        val xpub: String,
        val checks: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    data class MultisigPolicyInfo(
        val policyType: String,
        val scriptType: String,
        val threshold: Int,
        val totalSigners: Int,
        val descriptor: String,
        val bsmsDescriptorRecord: String,
        val keyReplacementWarning: String,
        val recoveryChecklist: List<String>,
        val warnings: List<String>,
        val keystores: List<MultisigKeystoreInfo>
    )

    data class DescriptorBackupMetadata(
        val isMultisig: Boolean,
        val bsmsDescriptorRecord: String?,
        val multisigPolicy: String?,
        val keyReplacementWarning: String?,
        val recoveryChecklist: List<String>,
        val signerWarnings: List<String>
    )

    data class UiState(
        val walletId: String = "",
        val walletName: String = "",
        val isWatchOnly: Boolean = false,
        val isMultisig: Boolean = false,
        val hasPassphrase: Boolean = false,
        val network: String = "mainnet",
        val transactionCount: Int = 0,
        val derivationPath: String = "",
        val accountXpub: String = "",
        val xpubLabel: String = "zpub",
        val preferredHardwareWallet: String? = null,
        val masterFingerprint: String? = null,        // stored HW fingerprint, e.g. "D3E95C19"
        val storedDerivationPath: String? = null,     // stored HW derivation path
        val importedViaDevice: String? = null,        // e.g. "COLDCARD_Q"
        val fingerprint: String = "",
        val fingerprintColors: List<Int> = emptyList(),
        val fingerprintBytes: ByteArray? = null,
        val masterFingerprintBytes: ByteArray? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val isEditing: Boolean = false,
        val editName: String = "",
        val descriptor: String = "",
        val changeDescriptor: String = "",
        val multisigPolicy: MultisigPolicyInfo? = null,
        val copied: Boolean = false,
        val isConvertingToHot: Boolean = false,
        val convertedToHot: Boolean = false,
        val labelImportExportResult: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun load(walletId: String) {
        _uiState.update { it.copy(walletId = walletId, isLoading = true) }
        viewModelScope.launch {
            try {
                val wallet = bitcoinRepository.getWalletEntity(walletId)
                if (wallet == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Wallet not found") }
                    return@launch
                }

                val txs = bitcoinRepository.getTransactions(walletId)
                val parsedMultisigPolicy = parseMultisigPolicyForDisplay(wallet.descriptor, wallet.changeDescriptor)
                val keystoreMetadata = if (parsedMultisigPolicy != null) {
                    withContext(Dispatchers.IO) {
                        walletKeystoreMetadataDao.getForWallet(walletId).associateBy { it.keyId }
                    }
                } else emptyMap()
                val multisigPolicy = parsedMultisigPolicy?.withMetadata(keystoreMetadata)
                val effectiveIsMultisig = wallet.isMultisig ||
                    multisigPolicy != null ||
                    DescriptorDisplayPolicy.isMultisigDescriptor(wallet.descriptor) ||
                    DescriptorDisplayPolicy.isMultisigDescriptor(wallet.changeDescriptor)
                val xpub = if (effectiveIsMultisig) "" else try { bitcoinRepository.getAccountXpub(walletId) } catch (_: Exception) { "" }
                val derivPath = if (effectiveIsMultisig) {
                    multisigPolicy?.let { "${it.threshold} of ${it.totalSigners}" } ?: "See keystores"
                } else {
                    try { bitcoinRepository.getDerivationPath(walletId) } catch (_: Exception) { "Unknown" }
                }

                // Determine xpub label based on network and prefix
                val xpubLabel = when {
                    xpub.startsWith("zpub") -> "zpub"
                    xpub.startsWith("vpub") -> "vpub"
                    xpub.startsWith("ypub") -> "ypub"
                    xpub.startsWith("upub") -> "upub"
                    xpub.startsWith("tpub") -> "tpub"
                    xpub.startsWith("xpub") -> "xpub"
                    else -> "xpub"
                }

                // Visual fingerprint: use stored identicon bytes if available (preserves
                // passphrase-derived visual from wallet creation). Fall back to recomputing
                // without passphrase for older wallets that don't have stored bytes.
                val fingerprint = if (effectiveIsMultisig) "" else generateFingerprint(wallet.descriptor)
                val fingerprintColors = if (effectiveIsMultisig) emptyList() else generateFingerprintColors(fingerprint)
                val masterFp = if (effectiveIsMultisig) null else CreateWalletViewModel.extractMasterFingerprint(wallet.descriptor)
                val fpBytes = if (effectiveIsMultisig) null else wallet.identiconBytes ?: if (masterFp != null) {
                    CreateWalletViewModel.computeFingerprint(masterFp, "").sliceArray(0 until 8)
                } else null

                _uiState.update { it.copy(
                    walletName = wallet.name,
                    isWatchOnly = wallet.isWatchOnly,
                    isMultisig = effectiveIsMultisig,
                    hasPassphrase = wallet.hasPassphrase,
                    network = wallet.network,
                    transactionCount = txs.size,
                    derivationPath = derivPath,
                    accountXpub = xpub,
                    xpubLabel = xpubLabel,
                    preferredHardwareWallet = wallet.preferredHardwareWallet,
                    masterFingerprint = wallet.masterFingerprint,
                    storedDerivationPath = wallet.derivationPath,
                    importedViaDevice = wallet.importedViaDevice,
                    fingerprint = fingerprint,
                    fingerprintColors = fingerprintColors,
                    fingerprintBytes = fpBytes,
                    masterFingerprintBytes = masterFp,
                    descriptor = wallet.descriptor,
                    changeDescriptor = wallet.changeDescriptor,
                    multisigPolicy = multisigPolicy,
                    isLoading = false
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun startEditing() {
        _uiState.update { it.copy(isEditing = true, editName = it.walletName) }
    }

    fun setEditName(name: String) {
        _uiState.update { it.copy(editName = name) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(isEditing = false) }
    }

    fun saveName() {
        val walletId = _uiState.value.walletId
        val newName = _uiState.value.editName.trim()
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                bitcoinRepository.renameWallet(walletId, newName)
                _uiState.update { it.copy(walletName = newName, isEditing = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun setPreferredHardwareWallet(device: String?) {
        val walletId = _uiState.value.walletId
        viewModelScope.launch {
            try {
                bitcoinRepository.setPreferredHardwareWallet(walletId, device)
                _uiState.update { it.copy(preferredHardwareWallet = device) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun renameKeystore(keyId: String, label: String) {
        val walletId = _uiState.value.walletId
        val cleanLabel = label.trim().take(64)
        if (walletId.isBlank() || keyId.isBlank() || cleanLabel.isBlank()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    walletKeystoreMetadataDao.upsert(
                        WalletKeystoreMetadataEntity(
                            walletId = walletId,
                            keyId = keyId,
                            label = cleanLabel,
                            preferredHardwareWallet = null,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
                _uiState.update { state ->
                    val updatedPolicy = state.multisigPolicy?.let { policy ->
                        val updatedKeystores = policy.keystores.map { keystore ->
                            if (keystore.keyId == keyId) keystore.copy(label = cleanLabel) else keystore
                        }
                        policy.copy(
                            keystores = updatedKeystores,
                            warnings = buildMultisigWarnings(updatedKeystores)
                        )
                    }
                    state.copy(multisigPolicy = updatedPolicy)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun convertWatchOnlyToHot(mnemonicWords: CharArray, passphrase: CharArray?) {
        val walletId = _uiState.value.walletId
        val words = String(mnemonicWords).trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        val passphraseString = passphrase?.let { String(it) }?.ifBlank { null }
        if (words.size != 12 && words.size != 24) {
            mnemonicWords.fill('0')
            passphrase?.fill('0')
            _uiState.update { it.copy(error = "Enter a 12 or 24 word seed phrase") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isConvertingToHot = true, error = null, convertedToHot = false) }
            try {
                bitcoinRepository.convertWatchOnlyToHot(walletId, words, passphraseString)
                _uiState.update {
                    it.copy(
                        isConvertingToHot = false,
                        convertedToHot = true,
                        isWatchOnly = false,
                        hasPassphrase = passphraseString != null,
                        error = null
                    )
                }
                load(walletId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConvertingToHot = false,
                        error = e.message ?: "Could not add seed phrase"
                    )
                }
            } finally {
                mnemonicWords.fill('0')
                passphrase?.fill('0')
            }
        }
    }

    fun clearConversionSuccess() {
        _uiState.update { it.copy(convertedToHot = false) }
    }

    fun clearLabelImportExportResult() {
        _uiState.update { it.copy(labelImportExportResult = null) }
    }

    fun exportWalletDescriptorBackup() {
        val state = _uiState.value
        viewModelScope.launch {
            try {
                val wallet = bitcoinRepository.getWalletEntity(state.walletId)
                    ?: throw IllegalStateException("Wallet not found")
                val payload = buildDescriptorBackupPayload(wallet, System.currentTimeMillis())
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val safeName = wallet.name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "wallet" }
                val fileName = "clench-$safeName-descriptors-$dateStr.json"
                val file = File(context.cacheDir, fileName)
                withContext(Dispatchers.IO) { file.writeText(payload) }

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export wallet descriptors").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                _uiState.update { it.copy(labelImportExportResult = "Descriptor export failed: ${e.message}") }
            }
        }
    }

    fun exportLabels() {
        val walletId = _uiState.value.walletId
        viewModelScope.launch {
            try {
                val labels = withContext(Dispatchers.IO) { transactionLabelDao.getForWallet(walletId) }
                if (labels.isEmpty()) {
                    _uiState.update { it.copy(labelImportExportResult = "No labels to export") }
                    return@launch
                }
                val jsonl = Bip329.exportLabels(labels)
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val fileName = "clench-labels-$dateStr.jsonl"
                val file = File(context.cacheDir, fileName)
                withContext(Dispatchers.IO) { file.writeText(jsonl) }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/jsonl"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export labels").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                _uiState.update { it.copy(labelImportExportResult = "Export failed: ${e.message}") }
            }
        }
    }

    fun importLabels(uri: Uri) {
        val walletId = _uiState.value.walletId
        viewModelScope.launch {
            try {
                val jsonl = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw Exception("Could not read file")
                }
                val parsed = Bip329.importLabels(jsonl)
                if (parsed.isEmpty()) {
                    _uiState.update { it.copy(labelImportExportResult = "No transaction labels found in file") }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    for ((txid, label) in parsed) {
                        transactionLabelDao.upsert(
                            TransactionLabelEntity(
                                key = "$walletId:$txid",
                                walletId = walletId,
                                txid = txid,
                                label = label
                            )
                        )
                    }
                }
                _uiState.update { it.copy(labelImportExportResult = "Imported ${parsed.size} labels") }
            } catch (e: Exception) {
                _uiState.update { it.copy(labelImportExportResult = "Import failed: ${e.message}") }
            }
        }
    }

    fun copyToClipboard(text: String, label: String = "Copied") {
        copyToClipboardWithAutoClear(context, label, text)
        _uiState.update { it.copy(copied = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000L)
            _uiState.update { it.copy(copied = false) }
        }
    }

    /**
     * Generate fingerprint from master fingerprint bytes extracted from descriptor.
     * Uses SHA-256 of the 4-byte master fingerprint (no passphrase mixing — passphrase
     * is not stored, so we can only use the descriptor's embedded master fingerprint).
     */
    private fun generateFingerprint(descriptorString: String): String {
        return try {
            val masterFp = CreateWalletViewModel.extractMasterFingerprint(descriptorString)
            if (masterFp != null) {
                val hash = CreateWalletViewModel.computeFingerprint(masterFp, "")
                hash.take(8).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            } else {
                // Fallback for descriptors without origin (e.g. bare xpub watch-only)
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(descriptorString.toByteArray())
                hash.take(8).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            }
        } catch (_: Exception) { "" }
    }

    private fun generateFingerprintColors(fingerprint: String): List<Int> {
        if (fingerprint.isBlank()) return emptyList()
        val bytes = fingerprint.split(":").mapNotNull {
            try { it.toInt(16) } catch (_: Exception) { null }
        }
        // Generate 8 hue-based colors from the bytes (matching CreateWalletScreen style)
        return bytes.map { b ->
            val hue = b * 360f / 256f
            android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.7f))
        }
    }

    companion object {
        internal fun parseMultisigPolicyForDisplay(
            descriptor: String,
            changeDescriptor: String
        ): MultisigPolicyInfo? {
            val cleanDescriptor = descriptor.substringBefore("#").trim()
            val multiCall = extractFunctionArgs(cleanDescriptor, "sortedmulti")
                ?: extractFunctionArgs(cleanDescriptor, "multi")
                ?: return null
            val args = splitDescriptorArgs(multiCall)
            val threshold = args.firstOrNull()?.trim()?.toIntOrNull() ?: return null
            val keystores = args.drop(1).mapIndexedNotNull { index, raw ->
                parseKeystore(index, raw)
            }
            if (keystores.isEmpty() || threshold !in 1..keystores.size) return null

            val scriptType = when {
                cleanDescriptor.startsWith("sh(wsh(") -> "Nested SegWit (P2SH-P2WSH)"
                cleanDescriptor.startsWith("wsh(") -> "Native SegWit (P2WSH)"
                cleanDescriptor.startsWith("sh(") -> "Legacy (P2SH)"
                else -> "Unknown"
            }

            return MultisigPolicyInfo(
                policyType = "Multi Signature",
                scriptType = scriptType,
                threshold = threshold,
                totalSigners = keystores.size,
                descriptor = cleanDescriptor.ifBlank { changeDescriptor.substringBefore("#").trim() },
                bsmsDescriptorRecord = buildBsmsDescriptorRecord(cleanDescriptor),
                keyReplacementWarning = "Do not replace a cosigner inside this wallet. Create a new multisig wallet with the replacement signer set, verify its receive addresses, then move funds.",
                recoveryChecklist = buildRecoveryChecklist(threshold, keystores.size),
                warnings = buildMultisigWarnings(keystores),
                keystores = keystores
            )
        }

        internal fun buildDescriptorBackupPayload(
            wallet: net.clench.wallet.domain.model.WalletData,
            exportedAtEpochMs: Long
        ): String {
            val metadata = buildDescriptorBackupMetadata(wallet)
            return JSONObject().apply {
                put("format", "clench-wallet-descriptor-backup")
                put("version", 1)
                put("exportedAtEpochMs", exportedAtEpochMs)
                put("walletId", wallet.id)
                put("name", wallet.name)
                put("network", wallet.network)
                put("isWatchOnly", wallet.isWatchOnly)
                put("isMultisig", metadata.isMultisig)
                put("descriptor", wallet.descriptor)
                put("changeDescriptor", wallet.changeDescriptor)
                metadata.bsmsDescriptorRecord?.let { put("bsmsDescriptorRecord", it) }
                metadata.multisigPolicy?.let { put("multisigPolicy", it) }
                metadata.keyReplacementWarning?.let { put("keyReplacementWarning", it) }
                if (metadata.recoveryChecklist.isNotEmpty()) {
                    put("recoveryChecklist", org.json.JSONArray(metadata.recoveryChecklist))
                }
                if (metadata.signerWarnings.isNotEmpty()) {
                    put("signerWarnings", org.json.JSONArray(metadata.signerWarnings))
                }
                put("preferredHardwareWallet", wallet.preferredHardwareWallet ?: JSONObject.NULL)
                put("masterFingerprint", wallet.masterFingerprint ?: JSONObject.NULL)
                put("derivationPath", wallet.derivationPath ?: JSONObject.NULL)
                put("importedViaDevice", wallet.importedViaDevice ?: JSONObject.NULL)
                put("secretsIncluded", false)
                put("warning", "This file contains public descriptors only. It cannot spend funds by itself, does not include seed phrases, passphrases, or private keys, and can reveal wallet history.")
                put("verificationInstructions", "After importing, verify network, script type, derivation path, master fingerprint, and first receive address before funding or spending.")
                put("reimportInstructions", "Import this file in Clench, or import the descriptor/BSMS record in Sparrow, BlueWallet, Nunchuk, or another descriptor-aware wallet.")
            }.toString(2)
        }

        internal fun buildDescriptorBackupMetadata(
            wallet: net.clench.wallet.domain.model.WalletData
        ): DescriptorBackupMetadata {
            val policy = parseMultisigPolicyForDisplay(wallet.descriptor, wallet.changeDescriptor)
            return DescriptorBackupMetadata(
                isMultisig = wallet.isMultisig ||
                    policy != null ||
                    DescriptorDisplayPolicy.isMultisigDescriptor(wallet.descriptor) ||
                    DescriptorDisplayPolicy.isMultisigDescriptor(wallet.changeDescriptor),
                bsmsDescriptorRecord = policy?.bsmsDescriptorRecord,
                multisigPolicy = policy?.let { "${it.threshold} of ${it.totalSigners}" },
                keyReplacementWarning = policy?.keyReplacementWarning,
                recoveryChecklist = policy?.recoveryChecklist.orEmpty(),
                signerWarnings = policy?.warnings.orEmpty()
            )
        }

        private fun MultisigPolicyInfo.withMetadata(
            metadataByKey: Map<String, WalletKeystoreMetadataEntity>
        ): MultisigPolicyInfo {
            if (metadataByKey.isEmpty()) return this
            val updatedKeystores = keystores.map { keystore ->
                val label = metadataByKey[keystore.keyId]?.label
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                if (label == null) keystore else keystore.copy(label = label)
            }
            return copy(
                keystores = updatedKeystores,
                warnings = buildMultisigWarnings(updatedKeystores)
            )
        }

        internal fun buildBsmsDescriptorRecord(descriptor: String): String {
            val cleanDescriptor = descriptor.substringBefore("#").trim()
            val bsmsDescriptor = cleanDescriptor
                .replace("/0/*", "/**")
                .replace("/1/*", "/**")
            return listOf(
                "BSMS 1.0",
                bsmsDescriptor,
                "/0/*,/1/*"
            ).joinToString("\n")
        }

        private fun buildRecoveryChecklist(threshold: Int, totalSigners: Int): List<String> {
            return listOf(
                "Export and store the descriptor backup before funding this wallet.",
                "Verify the BSMS/descriptor import restores the same $threshold-of-$totalSigners policy in another wallet.",
                "Confirm each signer fingerprint and derivation path on the physical signer.",
                "Generate the first receive address from the restored wallet and compare it with Clench.",
                "Run a small PSBT signing drill before storing meaningful funds."
            )
        }

        internal fun buildMultisigWarnings(keystores: List<MultisigKeystoreInfo>): List<String> {
            return buildList {
                keystores.forEach { keystore ->
                    if (keystore.masterFingerprint == null) {
                        add("${keystore.label}: missing master fingerprint")
                    }
                    if (keystore.derivationPath == null) {
                        add("${keystore.label}: missing derivation path")
                    }
                }
            }
        }

        private fun extractFunctionArgs(descriptor: String, functionName: String): String? {
            val start = descriptor.indexOf("$functionName(")
            if (start < 0) return null
            val argsStart = start + functionName.length + 1
            var depth = 1
            for (index in argsStart until descriptor.length) {
                when (descriptor[index]) {
                    '(' -> depth += 1
                    ')' -> {
                        depth -= 1
                        if (depth == 0) return descriptor.substring(argsStart, index)
                    }
                }
            }
            return null
        }

        private fun splitDescriptorArgs(args: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            args.forEachIndexed { index, char ->
                when (char) {
                    '(', '[' -> depth += 1
                    ')', ']' -> depth -= 1
                    ',' -> if (depth == 0) {
                        parts += args.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
            parts += args.substring(start).trim()
            return parts.filter { it.isNotBlank() }
        }

        private fun parseKeystore(index: Int, raw: String): MultisigKeystoreInfo? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null
            val originMatch = Regex("""^\[([0-9a-fA-F]{8})(?:/([^\]]+))?\](.+)$""").find(trimmed)
            val fingerprint = originMatch?.groupValues?.getOrNull(1)?.uppercase()
            val originPath = originMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
            val keyWithPath = originMatch?.groupValues?.getOrNull(3) ?: trimmed
            val xpub = keyWithPath
                .removeSuffix("/0/*")
                .removeSuffix("/1/*")
                .removeSuffix("/**")
                .trim()
            if (xpub.isBlank()) return null

            val warnings = buildList {
                if (fingerprint == null) add("Missing master fingerprint")
                if (originPath == null) add("Missing derivation path")
            }
            val checks = buildList {
                add("Public key present")
                if (fingerprint != null) add("Master fingerprint present")
                if (originPath != null) add("Derivation path present")
                if (keyWithPath.contains("/0/*") || keyWithPath.contains("/1/*") || keyWithPath.contains("/**")) {
                    add("Ranged branch present")
                }
            }

            return MultisigKeystoreInfo(
                keyId = stableKeystoreId(fingerprint, originPath, xpub),
                label = "Keystore ${index + 1}",
                masterFingerprint = fingerprint,
                derivationPath = originPath?.let { if (it.startsWith("m/")) it else "m/$it" },
                xpub = xpub,
                checks = checks,
                warnings = warnings
            )
        }

        internal fun stableKeystoreId(
            fingerprint: String?,
            derivationPath: String?,
            xpub: String
        ): String {
            val input = listOf(
                fingerprint.orEmpty().uppercase(Locale.US),
                derivationPath.orEmpty().lowercase(Locale.US),
                xpub.trim()
            ).joinToString("|")
            return MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
