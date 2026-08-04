package net.clench.wallet.security

import kotlinx.coroutines.runBlocking
import net.clench.wallet.data.repository.NativeWalletResourceCleanup
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.data.repository.WalletCacheRestartRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AppProcessSecurityPolicyTest {
    @Test
    fun `old cleanup finalizer cannot release a newer cleanup owner`() {
        val ownership = CleanupOwnership()
        var first = checkNotNull(ownership.claim())
        repeat(256) {
            assertNull(ownership.claim())
            assertTrue(ownership.release(first))
            first = checkNotNull(ownership.claim())
        }
        val old = first
        assertTrue(ownership.release(old))
        val second = checkNotNull(ownership.claim())

        assertFalse(ownership.release(old))
        assertTrue(ownership.isClaimed())
        assertTrue(ownership.release(second))
        assertFalse(ownership.isClaimed())
    }

    @Test
    fun `Clench scanner activity remains the same foreground process session`() {
        val tracker = AppActivityVisibilityTracker()
        val main = Any()
        val scanner = Any()

        assertEquals(AppProcessTransition.FOREGROUND, tracker.onActivityStarted(main))
        assertNull(tracker.onActivityStarted(scanner))
        assertNull(tracker.onActivityStopped(main, changingConfigurations = false))
        assertNull(tracker.onActivityStarted(main))
        assertNull(tracker.onActivityStopped(scanner, changingConfigurations = false))
    }

    @Test
    fun `system picker or prompt backgrounds the last Clench activity`() {
        val tracker = AppActivityVisibilityTracker()
        val main = Any()

        tracker.onActivityStarted(main)
        assertEquals(
            AppProcessTransition.BACKGROUND,
            tracker.onActivityStopped(main, changingConfigurations = false)
        )
        assertEquals(AppProcessTransition.FOREGROUND, tracker.onActivityStarted(main))
    }

    @Test
    fun `configuration replacement does not create a background session`() {
        val tracker = AppActivityVisibilityTracker()
        val oldActivity = Any()
        val replacement = Any()

        tracker.onActivityStarted(oldActivity)
        assertNull(tracker.onActivityStopped(oldActivity, changingConfigurations = true))
        assertNull(tracker.onActivityStarted(replacement))
    }

    @Test
    fun `timeout is measured only across a real process background`() {
        val machine = AppProcessSecurityStateMachine()

        val firstForeground = machine.onForeground(
            nowElapsedRealtime = 100L,
            appLockConfigured = true,
            lockTimeoutMs = 1_000L
        )
        assertFalse(firstForeground.appLockRequired)

        machine.onBackground(1_000L)
        val beforeTimeout = machine.onForeground(
            nowElapsedRealtime = 1_999L,
            appLockConfigured = true,
            lockTimeoutMs = 1_000L
        )
        assertFalse(beforeTimeout.appLockRequired)

        machine.onBackground(2_000L)
        val atTimeout = machine.onForeground(
            nowElapsedRealtime = 3_000L,
            appLockConfigured = true,
            lockTimeoutMs = 1_000L
        )
        assertTrue(atTimeout.appLockRequired)
    }

    @Test
    fun `cleanup pending or failed remains fail closed after foreground return`() {
        val machine = AppProcessSecurityStateMachine()
        machine.onForeground(0L, appLockConfigured = false, lockTimeoutMs = 0L)
        val background = machine.onBackground(10L)

        val pending = machine.onForeground(11L, appLockConfigured = false, lockTimeoutMs = 0L)
        assertEquals(SensitiveCleanupStatus.SECURING, pending.cleanupStatus)

        val failed = machine.onCleanupFailed(background.cleanupCycle)
        assertEquals(SensitiveCleanupStatus.FAILED, failed.cleanupStatus)

        val stillFailed = machine.onForeground(20L, appLockConfigured = false, lockTimeoutMs = 0L)
        assertEquals(SensitiveCleanupStatus.FAILED, stillFailed.cleanupStatus)

        machine.onBackground(21L)
        val failedAfterSecondReturn = machine.onForeground(
            22L,
            appLockConfigured = false,
            lockTimeoutMs = 0L
        )
        assertEquals(SensitiveCleanupStatus.FAILED, failedAfterSecondReturn.cleanupStatus)
    }

    @Test
    fun `second background while cleanup is pending reuses the same cleanup cycle`() {
        val machine = AppProcessSecurityStateMachine()
        machine.onForeground(0L, appLockConfigured = false, lockTimeoutMs = 0L)
        val firstBackground = machine.onBackground(10L)
        machine.onForeground(11L, appLockConfigured = false, lockTimeoutMs = 0L)
        val secondBackground = machine.onBackground(12L)

        assertEquals(SensitiveCleanupStatus.SECURING, secondBackground.cleanupStatus)
        assertEquals(firstBackground.cleanupCycle, secondBackground.cleanupCycle)
        val completed = machine.onCleanupSucceeded(firstBackground.cleanupCycle)
        assertEquals(SensitiveCleanupStatus.SECURED_IN_BACKGROUND, completed.cleanupStatus)
    }

    @Test
    fun `completed background cleanup becomes ready only when foreground resumes`() {
        val machine = AppProcessSecurityStateMachine()
        machine.onForeground(0L, appLockConfigured = false, lockTimeoutMs = 0L)
        val background = machine.onBackground(10L)

        val secured = machine.onCleanupSucceeded(background.cleanupCycle)
        assertEquals(SensitiveCleanupStatus.SECURED_IN_BACKGROUND, secured.cleanupStatus)

        val foreground = machine.onForeground(20L, appLockConfigured = false, lockTimeoutMs = 0L)
        assertEquals(SensitiveCleanupStatus.READY, foreground.cleanupStatus)
        assertTrue(foreground.isForeground)
    }

    @Test
    fun `failed cleanup retries through a new cycle before access becomes ready`() {
        val machine = AppProcessSecurityStateMachine()
        machine.onForeground(0L, appLockConfigured = false, lockTimeoutMs = 0L)
        val background = machine.onBackground(10L)
        machine.onForeground(11L, appLockConfigured = false, lockTimeoutMs = 0L)
        machine.onCleanupFailed(background.cleanupCycle)

        val retry = machine.retryCleanup()
        assertEquals(SensitiveCleanupStatus.SECURING, retry.cleanupStatus)
        assertTrue(retry.cleanupCycle > background.cleanupCycle)

        val succeeded = machine.onCleanupSucceeded(retry.cleanupCycle)
        assertEquals(SensitiveCleanupStatus.READY, succeeded.cleanupStatus)
    }

    @Test
    fun `authentication from an old foreground generation cannot clear the new lock gate`() {
        val machine = AppProcessSecurityStateMachine()
        machine.onForeground(0L, appLockConfigured = true, lockTimeoutMs = 1_000L)
        val oldAuthorization = machine.captureForegroundAuthorization()!!

        machine.onBackground(10L)
        val backgroundCleanup = machine.current().cleanupCycle
        machine.onCleanupSucceeded(backgroundCleanup)
        val newForeground = machine.onForeground(
            nowElapsedRealtime = 1_010L,
            appLockConfigured = true,
            lockTimeoutMs = 1_000L
        )

        assertTrue(newForeground.appLockRequired)
        assertFalse(machine.isForegroundAuthorizationCurrent(oldAuthorization))
        assertNull(machine.satisfyAppLock(oldAuthorization))
        assertTrue(machine.current().appLockRequired)

        val currentAuthorization = machine.captureForegroundAuthorization(
            allowPendingAppLock = true
        )!!
        assertFalse(machine.satisfyAppLock(currentAuthorization)!!.appLockRequired)
    }

    @Test
    fun `authentication remains valid across Clench-owned activity transition only`() {
        val machine = AppProcessSecurityStateMachine()
        machine.onForeground(0L, appLockConfigured = false, lockTimeoutMs = 0L)
        val authorization = machine.captureForegroundAuthorization()!!

        // MainActivity -> PortraitCaptureActivity never reaches the process state machine.
        assertTrue(machine.isForegroundAuthorizationCurrent(authorization))
    }

    @Test
    fun `cleanup plan attempts both independent passes and retry must fully succeed`() = runBlocking {
        var legacyAttempts = 0
        var walletAttempts = 0
        var legacyShouldFail = true

        val first = runSensitiveCleanupPlan(
            deleteLegacyPassphrases = {
                legacyAttempts++
                if (legacyShouldFail) error("simulated legacy cleanup failure")
            },
            evictWalletState = { walletAttempts++ },
            restartRequired = { false }
        )

        assertEquals(CleanupFailureDisposition.RETRYABLE, first)
        assertEquals(1, legacyAttempts)
        assertEquals(1, walletAttempts)

        legacyShouldFail = false
        val retry = runSensitiveCleanupPlan(
            deleteLegacyPassphrases = { legacyAttempts++ },
            evictWalletState = { walletAttempts++ },
            restartRequired = { false }
        )

        assertEquals(CleanupFailureDisposition.NONE, retry)
        assertEquals(2, legacyAttempts)
        assertEquals(2, walletAttempts)
    }

    @Test
    fun `cleanup plan still attempts legacy pass when wallet eviction fails`() = runBlocking {
        var legacyAttempts = 0
        var walletAttempts = 0

        val result = runSensitiveCleanupPlan(
            deleteLegacyPassphrases = { legacyAttempts++ },
            evictWalletState = {
                walletAttempts++
                error("simulated wallet cleanup failure")
            },
            restartRequired = { true }
        )

        assertEquals(CleanupFailureDisposition.RESTART_REQUIRED, result)
        assertEquals(1, legacyAttempts)
        assertEquals(1, walletAttempts)
    }

    @Test
    fun `restart-required dominates a simultaneous retryable legacy cleanup failure`() = runBlocking {
        val result = runSensitiveCleanupPlan(
            deleteLegacyPassphrases = { error("legacy cleanup failed") },
            evictWalletState = { throw IllegalStateException("native close failed") },
            restartRequired = { true }
        )

        assertEquals(CleanupFailureDisposition.RESTART_REQUIRED, result)
    }

    @Test
    fun `restart-required failure remains latched and cannot be retried`() {
        val machine = AppProcessSecurityStateMachine()
        machine.onForeground(0L, appLockConfigured = false, lockTimeoutMs = 0L)
        val background = machine.onBackground(1L)
        machine.onCleanupFailed(background.cleanupCycle, restartRequired = true)

        machine.onForeground(2L, appLockConfigured = false, lockTimeoutMs = 0L)
        machine.onBackground(3L)
        val returned = machine.onForeground(4L, appLockConfigured = false, lockTimeoutMs = 0L)

        assertEquals(SensitiveCleanupStatus.FAILED_RESTART_REQUIRED, returned.cleanupStatus)
        assertThrows(IllegalStateException::class.java) { machine.retryCleanup() }

        val cannotDowngrade = machine.onCleanupFailed(
            background.cleanupCycle,
            restartRequired = false
        )
        assertEquals(
            SensitiveCleanupStatus.FAILED_RESTART_REQUIRED,
            cannotDowngrade.cleanupStatus
        )
    }

    @Test
    fun `sensitive auth is blocked while only app unlock auth is admitted`() {
        val machine = AppProcessSecurityStateMachine()
        machine.onForeground(0L, appLockConfigured = true, lockTimeoutMs = 1L)
        machine.onBackground(1L)
        val cycle = machine.current().cleanupCycle
        machine.onCleanupSucceeded(cycle)
        machine.onForeground(2L, appLockConfigured = true, lockTimeoutMs = 1L)

        assertNull(machine.captureForegroundAuthorization())
        assertTrue(machine.captureForegroundAuthorization(allowPendingAppLock = true) != null)
    }

    @Test
    fun `pending app lock remains latched across another short background`() {
        val machine = AppProcessSecurityStateMachine()
        machine.onForeground(0L, appLockConfigured = true, lockTimeoutMs = 100L)
        machine.onBackground(100L)
        val firstCycle = machine.current().cleanupCycle
        machine.onCleanupSucceeded(firstCycle)
        machine.onForeground(200L, appLockConfigured = true, lockTimeoutMs = 100L)
        assertTrue(machine.current().appLockRequired)

        machine.onBackground(201L)
        val secondCycle = machine.current().cleanupCycle
        machine.onCleanupSucceeded(secondCycle)
        val returned = machine.onForeground(
            nowElapsedRealtime = 202L,
            appLockConfigured = true,
            lockTimeoutMs = 100L
        )
        assertTrue(returned.appLockRequired)
        assertNull(machine.captureForegroundAuthorization())
        assertTrue(
            machine.captureForegroundAuthorization(allowPendingAppLock = true) != null
        )
    }

    @Test
    fun `stale authentication delivery never invokes sensitive action`() {
        var successCount = 0
        var staleCount = 0
        deliverAuthenticatedActionIfCurrent(
            isCurrent = { false },
            onSuccess = { successCount++ },
            onStale = { staleCount++ }
        )
        assertEquals(0, successCount)
        assertEquals(1, staleCount)

        deliverAuthenticatedActionIfCurrent(
            isCurrent = { true },
            onSuccess = { successCount++ },
            onStale = { staleCount++ }
        )
        assertEquals(1, successCount)
        assertEquals(1, staleCount)
    }

    @Test
    fun `foreground native quarantine immediately closes global authorization gate`() {
        val machine = AppProcessSecurityStateMachine()
        val barrier = SensitiveWalletOperationBarrier()
        machine.onForeground(0L, appLockConfigured = false, lockTimeoutMs = 0L)
        barrier.registerRestartRequiredListener {
            val current = machine.current()
            machine.onCleanupFailed(current.cleanupCycle, restartRequired = true)
        }

        assertThrows(WalletCacheRestartRequiredException::class.java) {
            barrier.closeNativeResourcesOrFail(
                listOf(NativeWalletResourceCleanup.CloseAction(Any()) { error("close failed") })
            )
        }

        assertEquals(
            SensitiveCleanupStatus.FAILED_RESTART_REQUIRED,
            machine.current().cleanupStatus
        )
        assertNull(machine.captureForegroundAuthorization())
        assertNull(machine.captureForegroundAuthorization(allowPendingAppLock = true))
        assertThrows(WalletCacheRestartRequiredException::class.java) { barrier.acquire() }
    }
}
