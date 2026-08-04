package net.clench.wallet.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.repository.BdkBitcoinRepository
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.data.repository.WalletCacheRestartRequiredException
import net.clench.wallet.security.AppActivityVisibilityTracker
import net.clench.wallet.security.AppProcessSecuritySnapshot
import net.clench.wallet.security.AppProcessSecurityStateMachine
import net.clench.wallet.security.AppProcessTransition
import net.clench.wallet.security.CleanupFailureDisposition
import net.clench.wallet.security.CleanupOwnership
import net.clench.wallet.security.ForegroundAuthorizationToken
import net.clench.wallet.security.SensitiveCleanupStatus
import net.clench.wallet.security.runSensitiveCleanupPlan

/**
 * Applies background security to the whole Clench process rather than to MainActivity alone.
 *
 * All Clench-owned activities participate in the foreground count, so CaptureActivity does not
 * punch a lock-suppression hole. System activities do not participate: a picker, permission UI
 * that fully obscures Clench, Home, or another app therefore triggers immediate secret-session
 * admission closure and a verified cleanup pass.
 */
@Singleton
class AppProcessSecurityCoordinator @Inject constructor(
    bitcoinRepository: BdkBitcoinRepository,
    settingsManager: SettingsManager,
    keystoreManager: KeystoreManager,
    operationBarrier: SensitiveWalletOperationBarrier
) : Application.ActivityLifecycleCallbacks {
    private val bitcoinRepository = bitcoinRepository
    private val settingsManager = settingsManager
    private val keystoreManager = keystoreManager
    private val visibilityTracker = AppActivityVisibilityTracker()
    private val stateMachine = AppProcessSecurityStateMachine()
    private val stateLock = Any()
    private val registered = AtomicBoolean(false)
    private val cleanupOwnership = CleanupOwnership()
    private val cleanupMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(stateMachine.current())

    init {
        operationBarrier.registerRestartRequiredListener(::onNativeRestartRequired)
    }

    internal val state: StateFlow<AppProcessSecuritySnapshot> = _state.asStateFlow()

    /** Immediately remove protected navigation when any foreground native close is unverified. */
    private fun onNativeRestartRequired() {
        var launchCleanupCycle: Long? = null
        var ownershipToken: Long? = null
        synchronized(stateLock) {
            val current = stateMachine.current()
            _state.value = stateMachine.onCleanupFailed(
                cleanupCycle = current.cleanupCycle,
                restartRequired = true
            )
            // A failure from a foreground leased operation has no lifecycle cleanup in flight.
            // Start one after admission is already permanently closed so every other native,
            // Room, and filesystem cleanup is still attempted. If background cleanup is already
            // SECURING, it owns the ticket and will preserve the fatal result itself.
            if (current.cleanupStatus == SensitiveCleanupStatus.READY &&
                cleanupOwnership.claim().also { ownershipToken = it } != null
            ) {
                runCatching { bitcoinRepository.beginSensitiveSessionEviction() }
                    .onSuccess { launchCleanupCycle = current.cleanupCycle }
                    .onFailure { ownershipToken?.let(cleanupOwnership::release) }
            }
        }
        launchCleanupCycle?.let { cycle ->
            launchCleanup(cycle, checkNotNull(ownershipToken))
        }
    }

    fun register(application: Application) {
        if (registered.compareAndSet(false, true)) {
            application.registerActivityLifecycleCallbacks(this)
        }
    }

    /**
     * Establish a clean cold-start boundary before any Activity can expose wallet state.
     *
     * Legacy passphrase deletion and native/public-cache eviction are independent passes: both
     * run even when either fails. Admission is reopened only after every pass and the repository's
     * empty-state invariant succeed.
     */
    suspend fun secureColdStart() {
        val ownershipToken = checkNotNull(cleanupOwnership.claim()) {
            "Sensitive cleanup already running"
        }
        try {
            val beginDisposition = try {
                bitcoinRepository.beginSensitiveSessionEviction()
                CleanupFailureDisposition.NONE
            } catch (_: Throwable) {
                CleanupFailureDisposition.RESTART_REQUIRED
            }
            val cleanupDisposition = performSensitiveCleanup()
            val disposition = maxOf(beginDisposition, cleanupDisposition)
            synchronized(stateLock) {
                val accessSucceeded = disposition == CleanupFailureDisposition.NONE &&
                    runCatching { bitcoinRepository.allowSensitiveSessionAccess() }.isSuccess
                if (!accessSucceeded) {
                    val current = stateMachine.current()
                    _state.value = stateMachine.onCleanupFailed(
                        cleanupCycle = current.cleanupCycle,
                        restartRequired = disposition == CleanupFailureDisposition.RESTART_REQUIRED ||
                            disposition == CleanupFailureDisposition.NONE
                    )
                }
            }
        } finally {
            cleanupOwnership.release(ownershipToken)
        }
    }

    internal fun captureForegroundAuthorization(
        allowPendingAppLock: Boolean = false
    ): ForegroundAuthorizationToken? = synchronized(stateLock) {
        stateMachine.captureForegroundAuthorization(allowPendingAppLock)
    }

    internal fun isForegroundAuthorizationCurrent(
        token: ForegroundAuthorizationToken,
        allowPendingAppLock: Boolean = false
    ): Boolean = synchronized(stateLock) {
        stateMachine.isForegroundAuthorizationCurrent(token, allowPendingAppLock)
    }

    /** Consume the authoritative app-lock gate only for this exact foreground generation. */
    internal fun satisfyAppLock(token: ForegroundAuthorizationToken): Boolean =
        synchronized(stateLock) {
            val next = stateMachine.satisfyAppLock(token) ?: return@synchronized false
            _state.value = next
            true
        }

    /** Retry the complete cleanup suite; admission remains closed until every pass succeeds. */
    fun retrySensitiveCleanup() {
        val cleanupCycle = synchronized(stateLock) {
            if (stateMachine.current().cleanupStatus != SensitiveCleanupStatus.FAILED) {
                return
            }
            val next = stateMachine.retryCleanup()
            _state.value = next
            val ownershipToken = cleanupOwnership.claim() ?: return
            try {
                bitcoinRepository.beginSensitiveSessionEviction()
            } catch (_: Throwable) {
                _state.value = stateMachine.onCleanupFailed(
                    cleanupCycle = next.cleanupCycle,
                    restartRequired = true
                )
                cleanupOwnership.release(ownershipToken)
                return
            }
            next.cleanupCycle to ownershipToken
        }
        launchCleanup(cleanupCycle.first, cleanupCycle.second)
    }

    override fun onActivityStarted(activity: Activity) {
        if (visibilityTracker.onActivityStarted(activity) != AppProcessTransition.FOREGROUND) return

        synchronized(stateLock) {
            val before = stateMachine.current()
            var next = stateMachine.onForeground(
                nowElapsedRealtime = SystemClock.elapsedRealtime(),
                appLockConfigured = settingsManager.getAppLockMode() != "none",
                lockTimeoutMs = settingsManager.getLockTimeoutMs()
            )

            if (before.cleanupStatus == SensitiveCleanupStatus.SECURED_IN_BACKGROUND &&
                next.cleanupStatus == SensitiveCleanupStatus.READY
            ) {
                next = runCatching {
                    bitcoinRepository.allowSensitiveSessionAccess()
                    next
                }.getOrElse {
                    stateMachine.onCleanupFailed(
                        cleanupCycle = next.cleanupCycle,
                        restartRequired = true
                    )
                }
            }
            _state.value = next
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (visibilityTracker.onActivityStopped(activity, activity.isChangingConfigurations) !=
            AppProcessTransition.BACKGROUND
        ) {
            return
        }

        val cleanup = synchronized(stateLock) {
            val before = stateMachine.current()
            // Close repository admission before publishing or otherwise processing the
            // background transition. This is the first fail-closed action for a real process
            // background and leaves no state-machine-to-barrier admission window.
            val ownershipToken = if (before.cleanupStatus == SensitiveCleanupStatus.READY) {
                cleanupOwnership.claim()
            } else {
                null
            }
            val admissionClosed = if (ownershipToken != null) {
                runCatching { bitcoinRepository.beginSensitiveSessionEviction() }
            } else {
                null
            }
            val next = stateMachine.onBackground(SystemClock.elapsedRealtime())
            _state.value = next
            if (before.cleanupStatus != SensitiveCleanupStatus.READY) {
                return@synchronized null
            }
            if (ownershipToken == null) return@synchronized null
            if (admissionClosed?.isFailure != false) {
                _state.value = stateMachine.onCleanupFailed(
                    cleanupCycle = next.cleanupCycle,
                    restartRequired = true
                )
                cleanupOwnership.release(ownershipToken)
                return@synchronized null
            }
            next.cleanupCycle to ownershipToken
        }

        cleanup?.let { (cycle, token) -> launchCleanup(cycle, token) }
    }

    private fun launchCleanup(cleanupCycle: Long, ownershipToken: Long) {
        cleanupScope.launch {
            try {
                val cleanupDisposition = performSensitiveCleanup()
                synchronized(stateLock) {
                    if (cleanupCycle != stateMachine.current().cleanupCycle) {
                        cleanupOwnership.release(ownershipToken)
                        return@synchronized
                    }

                    var next = if (cleanupDisposition == CleanupFailureDisposition.NONE) {
                        stateMachine.onCleanupSucceeded(cleanupCycle)
                    } else {
                        stateMachine.onCleanupFailed(
                            cleanupCycle = cleanupCycle,
                            restartRequired = cleanupDisposition == CleanupFailureDisposition.RESTART_REQUIRED
                        )
                    }

                    if (cleanupDisposition == CleanupFailureDisposition.NONE &&
                        next.isForeground &&
                        next.cleanupStatus == SensitiveCleanupStatus.READY
                    ) {
                        next = runCatching {
                            bitcoinRepository.allowSensitiveSessionAccess()
                            next
                        }.getOrElse {
                            stateMachine.onCleanupFailed(
                                cleanupCycle = cleanupCycle,
                                restartRequired = true
                            )
                        }
                    }
                    // Cleanup is complete. Release ownership under the same lock and before
                    // publishing READY, so a lifecycle stop cannot observe READY while failing
                    // to claim the next cleanup cycle.
                    cleanupOwnership.release(ownershipToken)
                    _state.value = next
                }
            } finally {
                // Token matching makes this harmless after READY was published and another
                // lifecycle stop has already claimed a newer cleanup cycle.
                cleanupOwnership.release(ownershipToken)
            }
        }
    }

    private suspend fun performSensitiveCleanup(): CleanupFailureDisposition = cleanupMutex.withLock {
        runSensitiveCleanupPlan(
            deleteLegacyPassphrases = { keystoreManager.deleteAllPassphrases() },
            evictWalletState = { bitcoinRepository.completeSensitiveSessionEviction() },
            restartRequired = { it is WalletCacheRestartRequiredException }
        )
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
