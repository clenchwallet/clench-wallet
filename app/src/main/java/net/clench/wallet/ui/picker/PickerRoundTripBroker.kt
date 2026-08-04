package net.clench.wallet.ui.picker

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.clench.wallet.security.ForegroundAuthorizationToken
import net.clench.wallet.ui.navigation.Routes

internal enum class PickerPurpose {
    SETTINGS_BACKUP_IMPORT,
    SETTINGS_BACKUP_EXPORT,
    RECOVERY_BACKUP_IMPORT,
    WALLET_SETUP_IMPORT,
    SIGNER_VAULT_IMPORT,
    MULTISIG_SIGNER_IMPORT,
    WALLET_LABEL_IMPORT,
    TAPSIGNER_SETUP_BACKUP,
    TAPSIGNER_WALLET_BACKUP,
    SWEEP_WIF_IMPORT,
    RAW_TRANSACTION_IMPORT,
    HARDWARE_PSBT_IMPORT,
    HARDWARE_PSBT_EXPORT,
    PHONE_PSBT_EXPORT
}

internal enum class PickerMode {
    OPEN_DOCUMENT,
    CREATE_DOCUMENT
}

/** Typed, allowlisted navigation targets for a picker return. */
internal sealed class PickerDestination {
    abstract fun route(): String

    data object Settings : PickerDestination() {
        override fun route() = Routes.Settings.route
    }

    data object RecoveryWizard : PickerDestination() {
        override fun route() = Routes.RecoveryWizard.route
    }

    data class WalletImport(val hardwareWalletMode: Boolean) : PickerDestination() {
        override fun route() = if (hardwareWalletMode) {
            Routes.ImportHardwareWallet.route
        } else {
            Routes.ImportWallet.route
        }
    }

    data object SignerVault : PickerDestination() {
        override fun route() = Routes.SignerVault.route
    }

    data class CreateMultisig(
        val signerIndex: Int,
        val signerId: String? = null,
        val preset: String? = null
    ) : PickerDestination() {
        init {
            require(signerIndex in 0..14)
            require(signerId == null || signerId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            require(preset == null || preset in setOf("secure_vault"))
        }

        override fun route() = Routes.CreateMultisig.build(signerId, preset)
    }

    data class WalletInfo(val walletId: String) : PickerDestination() {
        init { requireSafeRouteId(walletId) }
        override fun route() = Routes.WalletInfo.build(walletId)
    }

    data class RawTransaction(val walletId: String) : PickerDestination() {
        init { requireSafeRouteId(walletId) }
        override fun route() = Routes.RawTransaction.build(walletId)
    }

    data class Sweep(val walletId: String) : PickerDestination() {
        init { requireSafeRouteId(walletId) }
        override fun route() = Routes.Sweep.build(walletId)
    }

    data class HardwarePsbt(
        val walletId: String,
        val deviceType: String,
        val handoffToken: String
    ) : PickerDestination() {
        init {
            requireSafeRouteId(walletId)
            require(deviceType.matches(Regex("[A-Z0-9_]{1,64}")))
            requireHandoffToken(handoffToken)
        }
        override fun route() = Routes.HardwarePsbt.build(walletId, deviceType)
    }

    data class PhonePsbt(val walletId: String, val handoffToken: String) : PickerDestination() {
        init {
            requireSafeRouteId(walletId)
            requireHandoffToken(handoffToken)
        }
        override fun route() = Routes.PhoneSignerPsbt.build(walletId)
    }

    companion object {
        private fun requireSafeRouteId(value: String) {
            require(value.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        }

        private fun requireHandoffToken(value: String) {
            require(value.matches(Regex("[a-f0-9]{32}")))
        }
    }
}

/**
 * Typed picker requests. Only route metadata and public identifiers are retained; constructors
 * intentionally provide no field capable of carrying a seed, PIN/CVC, passphrase, WIF, PSBT,
 * transaction, descriptor/xpub, or backup contents.
 */
internal sealed class PickerRequest(
    val purpose: PickerPurpose,
    val mode: PickerMode,
    val destination: PickerDestination,
    val mimeTypes: List<String>,
    val suggestedName: String? = null
) {
    init {
        require(mimeTypes.isNotEmpty() && mimeTypes.all { it.length in 1..128 })
        require(mode == PickerMode.CREATE_DOCUMENT || suggestedName == null)
        require(suggestedName == null ||
            suggestedName.matches(Regex("[A-Za-z0-9._-]{1,160}")))
    }

    data object SettingsBackupImport : PickerRequest(
        PickerPurpose.SETTINGS_BACKUP_IMPORT,
        PickerMode.OPEN_DOCUMENT,
        PickerDestination.Settings,
        listOf("application/json", "text/json", "*/*")
    )

    data class SettingsBackupExport(val filename: String) : PickerRequest(
        PickerPurpose.SETTINGS_BACKUP_EXPORT,
        PickerMode.CREATE_DOCUMENT,
        PickerDestination.Settings,
        listOf("application/json"),
        filename
    ) {
        init { require(filename.matches(Regex("clench-state-backup-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json"))) }
    }

    data object RecoveryBackupImport : PickerRequest(
        PickerPurpose.RECOVERY_BACKUP_IMPORT,
        PickerMode.OPEN_DOCUMENT,
        PickerDestination.RecoveryWizard,
        listOf("application/json", "text/json", "*/*")
    )

    data class WalletSetupImport(val hardwareWalletMode: Boolean) : PickerRequest(
        PickerPurpose.WALLET_SETUP_IMPORT,
        PickerMode.OPEN_DOCUMENT,
        PickerDestination.WalletImport(hardwareWalletMode),
        listOf("*/*")
    )

    data class TapsignerSetupBackup(
        val hardwareWalletMode: Boolean,
        val filename: String
    ) : PickerRequest(
        PickerPurpose.TAPSIGNER_SETUP_BACKUP,
        PickerMode.CREATE_DOCUMENT,
        PickerDestination.WalletImport(hardwareWalletMode),
        listOf("application/octet-stream"),
        filename
    ) {
        init {
            require(filename.matches(
                Regex("tapsigner-backup-[A-Za-z0-9_-]{1,64}-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.aes")
            ))
        }
    }

    data class SignerVaultImport(val deviceType: String?) : PickerRequest(
        PickerPurpose.SIGNER_VAULT_IMPORT,
        PickerMode.OPEN_DOCUMENT,
        PickerDestination.SignerVault,
        listOf("text/*", "application/json", "application/octet-stream", "*/*")
    ) {
        init {
            require(deviceType == null || deviceType.matches(Regex("[A-Z0-9_]{1,64}")))
        }
    }

    data class MultisigSignerImport(
        val signerIndex: Int,
        val signerId: String? = null,
        val preset: String? = null
    ) : PickerRequest(
        PickerPurpose.MULTISIG_SIGNER_IMPORT,
        PickerMode.OPEN_DOCUMENT,
        PickerDestination.CreateMultisig(signerIndex, signerId, preset),
        listOf("*/*")
    )

    data class WalletLabelImport(val walletId: String) : PickerRequest(
        PickerPurpose.WALLET_LABEL_IMPORT,
        PickerMode.OPEN_DOCUMENT,
        PickerDestination.WalletInfo(walletId),
        listOf("*/*")
    )

    data class TapsignerWalletBackup(val walletId: String, val filename: String) : PickerRequest(
        PickerPurpose.TAPSIGNER_WALLET_BACKUP,
        PickerMode.CREATE_DOCUMENT,
        PickerDestination.WalletInfo(walletId),
        listOf("application/octet-stream"),
        filename
    ) {
        init {
            require(filename.matches(
                Regex("tapsigner-backup-[A-Za-z0-9_-]{1,64}-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.aes")
            ))
        }
    }

    data class RawTransactionImport(val walletId: String) : PickerRequest(
        PickerPurpose.RAW_TRANSACTION_IMPORT,
        PickerMode.OPEN_DOCUMENT,
        PickerDestination.RawTransaction(walletId),
        listOf("text/plain", "application/octet-stream", "*/*")
    )

    data class SweepWifImport(val walletId: String) : PickerRequest(
        PickerPurpose.SWEEP_WIF_IMPORT,
        PickerMode.OPEN_DOCUMENT,
        PickerDestination.Sweep(walletId),
        listOf("text/plain", "application/octet-stream", "*/*")
    )

    data class HardwarePsbtImport(
        val walletId: String,
        val deviceType: String,
        val handoffToken: String
    ) : PickerRequest(
        PickerPurpose.HARDWARE_PSBT_IMPORT,
        PickerMode.OPEN_DOCUMENT,
        PickerDestination.HardwarePsbt(walletId, deviceType, handoffToken),
        listOf("application/octet-stream", "*/*")
    )

    data class HardwarePsbtExport(
        val walletId: String,
        val deviceType: String,
        val filename: String,
        val handoffToken: String
    ) : PickerRequest(
        PickerPurpose.HARDWARE_PSBT_EXPORT,
        PickerMode.CREATE_DOCUMENT,
        PickerDestination.HardwarePsbt(walletId, deviceType, handoffToken),
        listOf("application/octet-stream"),
        filename
    ) {
        init {
            require(filename == "${walletId.take(8)}_unsigned.psbt" ||
                filename == "${walletId.take(8)}_partial.psbt")
        }
    }

    data class PhonePsbtExport(
        val walletId: String,
        val filename: String,
        val handoffToken: String
    ) : PickerRequest(
        PickerPurpose.PHONE_PSBT_EXPORT,
        PickerMode.CREATE_DOCUMENT,
        PickerDestination.PhonePsbt(walletId, handoffToken),
        listOf("application/octet-stream"),
        filename
    ) {
        init { require(filename == "${walletId.take(8)}_phone_signed.psbt") }
    }
}

internal data class PickerResume(
    val requestId: Long,
    val purpose: PickerPurpose,
    val destination: PickerDestination,
    val cancelled: Boolean
) {
    val resumeRoute: String get() = destination.route()
}

internal data class PickerResult(
    val requestId: Long,
    val request: PickerRequest,
    val uri: String?
)

/**
 * Process-scoped, one-shot broker for system document pickers.
 *
 * Hilt owns this instance so Activity recreation while DocumentsUI is open does not orphan the
 * request. Android process death safely drops it. The broker never requests a persistable grant.
 */
@Singleton
internal class PickerRoundTripBroker internal constructor(
    private val monotonicNanos: () -> Long
) {
    @Inject constructor() : this(System::nanoTime)

    private data class Pending(
        val requestId: Long,
        val request: PickerRequest,
        val launchAuthorization: ForegroundAuthorizationToken,
        val resultUri: String? = null,
        val resultRecorded: Boolean = false,
        val deliveryAuthorization: ForegroundAuthorizationToken? = null,
        val expiresAtNanos: Long
    )

    private var nextRequestId = 1L
    private var pending: Pending? = null
    private val _resume = MutableStateFlow<PickerResume?>(null)
    val resume: StateFlow<PickerResume?> = _resume.asStateFlow()

    @Synchronized
    fun begin(request: PickerRequest, authorization: ForegroundAuthorizationToken): Long? {
        clearIfExpiredLocked()
        if (pending != null) return null
        val id = nextRequestId++
        pending = Pending(
            requestId = id,
            request = request,
            launchAuthorization = authorization,
            expiresAtNanos = monotonicNanos().saturatingAdd(PICKER_TTL_NANOS)
        )
        EXPIRY_EXECUTOR.schedule(
            { expireRequest(id) },
            PICKER_TTL_NANOS,
            TimeUnit.NANOSECONDS
        )
        return id
    }

    @Synchronized
    fun abort(requestId: Long) {
        if (pending?.requestId != requestId) return
        pending = null
        _resume.value = null
    }

    @Synchronized
    fun recordResult(uri: String?) {
        clearIfExpiredLocked()
        val current = pending ?: return
        if (current.resultRecorded) return
        pending = current.copy(
            resultUri = uri,
            resultRecorded = true,
            deliveryAuthorization = null
        )
        _resume.value = null
    }

    /** Caller must validate [authorization] against the coordinator's current admission state. */
    @Synchronized
    fun authorizeResult(
        authorization: ForegroundAuthorizationToken,
        authorizationIsCurrent: Boolean
    ) {
        clearIfExpiredLocked()
        val current = pending ?: return
        if (!authorizationIsCurrent ||
            !current.resultRecorded ||
            authorization.foregroundGeneration <= current.launchAuthorization.foregroundGeneration ||
            authorization.cleanupCycle <= current.launchAuthorization.cleanupCycle
        ) {
            return
        }
        pending = current.copy(deliveryAuthorization = authorization)
        _resume.value = PickerResume(
            requestId = current.requestId,
            purpose = current.request.purpose,
            destination = current.request.destination,
            cancelled = current.resultUri == null
        )
    }

    @Synchronized
    fun revokeAuthorization() {
        clearIfExpiredLocked()
        pending = pending?.copy(deliveryAuthorization = null)
        _resume.value = null
    }

    @Synchronized
    fun consume(
        purpose: PickerPurpose,
        destination: PickerDestination,
        authorization: ForegroundAuthorizationToken,
        authorizationIsCurrent: Boolean
    ): PickerResult? {
        clearIfExpiredLocked()
        val current = pending ?: return null
        if (!authorizationIsCurrent ||
            !current.resultRecorded ||
            current.request.purpose != purpose ||
            current.request.destination != destination ||
            current.deliveryAuthorization != authorization
        ) {
            // A resumed result presented to the wrong route/purpose/generation is not recoverable.
            // Destroy it rather than leaving a stale globally-blocking picker request.
            pending = null
            _resume.value = null
            return null
        }
        val result = PickerResult(current.requestId, current.request, current.resultUri)
        pending = null
        _resume.value = null
        return result
    }

    private fun clearIfExpiredLocked() {
        val current = pending ?: return
        if (monotonicNanos() >= current.expiresAtNanos) {
            pending = null
            _resume.value = null
        }
    }

    @Synchronized
    private fun expireRequest(requestId: Long) {
        if (pending?.requestId != requestId) return
        clearIfExpiredLocked()
    }

    private fun Long.saturatingAdd(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    companion object {
        internal const val PICKER_TTL_NANOS = 10L * 60L * 1_000_000_000L
        private val EXPIRY_EXECUTOR = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "clench-picker-expiry").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }
}
