package net.clench.wallet.security

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/** A process transition derived from all Clench-owned activities, not one Activity callback. */
internal enum class AppProcessTransition {
    FOREGROUND,
    BACKGROUND
}

/**
 * Tracks whether at least one Clench-owned activity is started.
 *
 * Android starts the destination activity before stopping the source activity for an in-task
 * transition, so launching [net.clench.wallet.ui.PortraitCaptureActivity] does not look like the
 * app entered the background. A configuration-change stop is also ignored: the replacement
 * activity continues the same foreground process session. In contrast, launching a system file
 * picker or leaving Clench stops the last Clench activity and produces a real background event.
 */
internal class AppActivityVisibilityTracker {
    private val startedActivities: MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap())
    private var processForeground = false

    @Synchronized
    fun onActivityStarted(activityToken: Any): AppProcessTransition? {
        if (!startedActivities.add(activityToken)) return null
        if (processForeground) return null
        processForeground = true
        return AppProcessTransition.FOREGROUND
    }

    @Synchronized
    fun onActivityStopped(
        activityToken: Any,
        changingConfigurations: Boolean
    ): AppProcessTransition? {
        if (!startedActivities.remove(activityToken)) return null
        if (startedActivities.isNotEmpty() || !processForeground) return null
        if (changingConfigurations) return null
        processForeground = false
        return AppProcessTransition.BACKGROUND
    }
}

internal enum class SensitiveCleanupStatus {
    READY,
    SECURING,
    SECURED_IN_BACKGROUND,
    FAILED,
    FAILED_RESTART_REQUIRED
}

internal data class AppProcessSecuritySnapshot(
    val isForeground: Boolean = false,
    val foregroundGeneration: Long = 0L,
    val cleanupCycle: Long = 0L,
    val cleanupStatus: SensitiveCleanupStatus = SensitiveCleanupStatus.READY,
    val appLockRequired: Boolean = false
)

/**
 * Identifies one foreground security session.
 *
 * Authentication callbacks must present the token captured before the system prompt was opened.
 * A real background transition changes both values, so a delayed success callback cannot
 * authorize work in a newly-created navigation/session generation.
 */
internal data class ForegroundAuthorizationToken(
    val foregroundGeneration: Long,
    val cleanupCycle: Long
)

internal enum class CleanupFailureDisposition {
    NONE,
    RETRYABLE,
    RESTART_REQUIRED
}

/** Tokenized cleanup ownership; an old finalizer can never release a newer cleanup cycle. */
internal class CleanupOwnership {
    private val nextToken = AtomicLong(0L)
    private val owner = AtomicLong(NO_OWNER)

    fun claim(): Long? {
        val token = nextToken.incrementAndGet()
        check(token != NO_OWNER) { "Cleanup ownership token is exhausted" }
        return if (owner.compareAndSet(NO_OWNER, token)) token else null
    }

    fun release(token: Long): Boolean = owner.compareAndSet(token, NO_OWNER)

    fun isClaimed(): Boolean = owner.get() != NO_OWNER

    private companion object {
        const val NO_OWNER = 0L
    }
}

/** Run every independent cleanup even when an earlier one fails. */
internal suspend fun runSensitiveCleanupPlan(
    deleteLegacyPassphrases: suspend () -> Unit,
    evictWalletState: suspend () -> Unit,
    restartRequired: (Throwable) -> Boolean
): CleanupFailureDisposition {
    var disposition = CleanupFailureDisposition.NONE
    try {
        deleteLegacyPassphrases()
    } catch (_: Throwable) {
        disposition = CleanupFailureDisposition.RETRYABLE
    }
    try {
        evictWalletState()
    } catch (failure: Throwable) {
        disposition = if (restartRequired(failure)) {
            CleanupFailureDisposition.RESTART_REQUIRED
        } else if (disposition == CleanupFailureDisposition.NONE) {
            CleanupFailureDisposition.RETRYABLE
        } else {
            disposition
        }
    }
    return disposition
}

internal inline fun deliverAuthenticatedActionIfCurrent(
    isCurrent: () -> Boolean,
    onSuccess: () -> Unit,
    onStale: () -> Unit
) {
    if (isCurrent()) onSuccess() else onStale()
}

/** Pure state machine used by the Android lifecycle coordinator and unit tests. */
internal class AppProcessSecurityStateMachine {
    private var snapshot = AppProcessSecuritySnapshot()
    private var backgroundAtElapsedRealtime: Long? = null

    fun current(): AppProcessSecuritySnapshot = snapshot

    fun captureForegroundAuthorization(
        allowPendingAppLock: Boolean = false
    ): ForegroundAuthorizationToken? {
        if (!snapshot.isForeground ||
            snapshot.cleanupStatus != SensitiveCleanupStatus.READY ||
            (!allowPendingAppLock && snapshot.appLockRequired)
        ) {
            return null
        }
        return ForegroundAuthorizationToken(
            foregroundGeneration = snapshot.foregroundGeneration,
            cleanupCycle = snapshot.cleanupCycle
        )
    }

    fun isForegroundAuthorizationCurrent(
        token: ForegroundAuthorizationToken,
        allowPendingAppLock: Boolean = false
    ): Boolean =
        snapshot.isForeground &&
            snapshot.cleanupStatus == SensitiveCleanupStatus.READY &&
            snapshot.foregroundGeneration == token.foregroundGeneration &&
            snapshot.cleanupCycle == token.cleanupCycle &&
            (allowPendingAppLock || !snapshot.appLockRequired)

    /** Clear the authoritative lock gate only for authentication from this exact generation. */
    fun satisfyAppLock(token: ForegroundAuthorizationToken): AppProcessSecuritySnapshot? {
        if (!isForegroundAuthorizationCurrent(token, allowPendingAppLock = true)) return null
        snapshot = snapshot.copy(appLockRequired = false)
        return snapshot
    }

    fun onBackground(nowElapsedRealtime: Long): AppProcessSecuritySnapshot {
        backgroundAtElapsedRealtime = nowElapsedRealtime.coerceAtLeast(0L)
        snapshot = when (snapshot.cleanupStatus) {
            SensitiveCleanupStatus.READY -> snapshot.copy(
                isForeground = false,
                cleanupCycle = snapshot.cleanupCycle + 1L,
                cleanupStatus = SensitiveCleanupStatus.SECURING
            )
            else -> snapshot.copy(isForeground = false)
        }
        return snapshot
    }

    fun onForeground(
        nowElapsedRealtime: Long,
        appLockConfigured: Boolean,
        lockTimeoutMs: Long
    ): AppProcessSecuritySnapshot {
        val backgroundAt = backgroundAtElapsedRealtime
        val elapsed = backgroundAt?.let {
            (nowElapsedRealtime.coerceAtLeast(0L) - it).coerceAtLeast(0L)
        }
        val lockRequired = appLockConfigured && elapsed != null &&
            elapsed >= lockTimeoutMs.coerceAtLeast(0L)
        val cleanupStatus = when (snapshot.cleanupStatus) {
            SensitiveCleanupStatus.SECURED_IN_BACKGROUND -> SensitiveCleanupStatus.READY
            else -> snapshot.cleanupStatus
        }

        backgroundAtElapsedRealtime = null
        snapshot = snapshot.copy(
            isForeground = true,
            foregroundGeneration = snapshot.foregroundGeneration + 1L,
            cleanupStatus = cleanupStatus,
            appLockRequired = snapshot.appLockRequired || lockRequired
        )
        return snapshot
    }

    fun onCleanupSucceeded(cleanupCycle: Long): AppProcessSecuritySnapshot {
        if (cleanupCycle != snapshot.cleanupCycle ||
            snapshot.cleanupStatus != SensitiveCleanupStatus.SECURING
        ) {
            return snapshot
        }
        snapshot = snapshot.copy(
            cleanupStatus = if (snapshot.isForeground) {
                SensitiveCleanupStatus.READY
            } else {
                SensitiveCleanupStatus.SECURED_IN_BACKGROUND
            }
        )
        return snapshot
    }

    fun onCleanupFailed(
        cleanupCycle: Long,
        restartRequired: Boolean = false
    ): AppProcessSecuritySnapshot {
        if (cleanupCycle != snapshot.cleanupCycle) return snapshot
        if (snapshot.cleanupStatus == SensitiveCleanupStatus.FAILED_RESTART_REQUIRED) {
            return snapshot
        }
        snapshot = snapshot.copy(
            cleanupStatus = if (restartRequired) {
                SensitiveCleanupStatus.FAILED_RESTART_REQUIRED
            } else {
                SensitiveCleanupStatus.FAILED
            }
        )
        return snapshot
    }

    fun retryCleanup(): AppProcessSecuritySnapshot {
        check(snapshot.cleanupStatus == SensitiveCleanupStatus.FAILED) {
            "Cleanup retry is only valid from the failed state"
        }
        snapshot = snapshot.copy(
            cleanupCycle = snapshot.cleanupCycle + 1L,
            cleanupStatus = SensitiveCleanupStatus.SECURING
        )
        return snapshot
    }
}
