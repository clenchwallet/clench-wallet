package net.clench.wallet.ui.picker

import net.clench.wallet.security.ForegroundAuthorizationToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PickerRoundTripBrokerTest {
    private val launchAuthorization = ForegroundAuthorizationToken(
        foregroundGeneration = 4,
        cleanupCycle = 7
    )
    private val returnedAuthorization = ForegroundAuthorizationToken(
        foregroundGeneration = 5,
        cleanupCycle = 8
    )

    @Test
    fun `picker result requires current new foreground authorization`() {
        val broker = PickerRoundTripBroker()
        val request = PickerRequest.WalletSetupImport(hardwareWalletMode = true)
        assertNotNull(broker.begin(request, launchAuthorization))
        broker.recordResult("content://documents/wallet.txt")

        broker.authorizeResult(launchAuthorization, authorizationIsCurrent = true)
        assertNull(broker.resume.value)

        broker.authorizeResult(returnedAuthorization, authorizationIsCurrent = false)
        assertNull(broker.resume.value)

        broker.authorizeResult(returnedAuthorization, authorizationIsCurrent = true)
        assertEquals(PickerPurpose.WALLET_SETUP_IMPORT, broker.resume.value?.purpose)
        assertEquals(
            PickerDestination.WalletImport(hardwareWalletMode = true),
            broker.resume.value?.destination
        )
    }

    @Test
    fun `result is destination bound and consumed exactly once`() {
        val broker = PickerRoundTripBroker()
        val request = PickerRequest.WalletLabelImport("wallet_1")
        broker.begin(request, launchAuthorization)
        broker.recordResult("content://documents/labels.jsonl")
        broker.authorizeResult(returnedAuthorization, authorizationIsCurrent = true)

        val result = broker.consume(
            purpose = PickerPurpose.WALLET_LABEL_IMPORT,
            destination = PickerDestination.WalletInfo("wallet_1"),
            authorization = returnedAuthorization,
            authorizationIsCurrent = true
        )
        assertEquals("content://documents/labels.jsonl", result?.uri)
        assertSame(request, result?.request)
        assertNull(broker.resume.value)
        assertNull(
            broker.consume(
                purpose = PickerPurpose.WALLET_LABEL_IMPORT,
                destination = PickerDestination.WalletInfo("wallet_1"),
                authorization = returnedAuthorization,
                authorizationIsCurrent = true
            )
        )
    }

    @Test
    fun `activity recreation keeps singleton request but not an old callback`() {
        val processBroker = PickerRoundTripBroker()
        val oldActivityReference = processBroker
        oldActivityReference.begin(
            PickerRequest.TapsignerWalletBackup(
                "wallet_1",
                "tapsigner-backup-card-2026-08-04.aes"
            ),
            launchAuthorization
        )

        // Hilt gives the replacement Activity the same process-scoped broker instance.
        val replacementActivityReference = processBroker
        replacementActivityReference.recordResult("content://documents/tapsigner-backup.aes")
        replacementActivityReference.authorizeResult(
            returnedAuthorization,
            authorizationIsCurrent = true
        )

        val result = replacementActivityReference.consume(
            purpose = PickerPurpose.TAPSIGNER_WALLET_BACKUP,
            destination = PickerDestination.WalletInfo("wallet_1"),
            authorization = returnedAuthorization,
            authorizationIsCurrent = true
        )
        assertEquals("content://documents/tapsigner-backup.aes", result?.uri)
    }

    @Test
    fun `cancel is a one-shot result and another request cannot replace pending state`() {
        val broker = PickerRoundTripBroker()
        assertNotNull(broker.begin(PickerRequest.SettingsBackupImport, launchAuthorization))
        assertNull(
            broker.begin(
                PickerRequest.SettingsBackupExport("clench-state-backup-2026-08-04.json"),
                launchAuthorization
            )
        )
        broker.recordResult(null)
        broker.authorizeResult(returnedAuthorization, authorizationIsCurrent = true)

        val cancelled = broker.consume(
            purpose = PickerPurpose.SETTINGS_BACKUP_IMPORT,
            destination = PickerDestination.Settings,
            authorization = returnedAuthorization,
            authorizationIsCurrent = true
        )
        assertNotNull(cancelled)
        assertNull(cancelled?.uri)
        assertNotNull(
            broker.begin(
                PickerRequest.SettingsBackupExport("clench-state-backup-2026-08-04.json"),
                returnedAuthorization
            )
        )
    }

    @Test
    fun `cancel is exposed without disclosing a URI so route can clear staged state eagerly`() {
        val broker = PickerRoundTripBroker()
        broker.begin(PickerRequest.SettingsBackupImport, launchAuthorization)
        broker.recordResult(null)
        broker.authorizeResult(returnedAuthorization, authorizationIsCurrent = true)

        assertEquals(true, broker.resume.value?.cancelled)
    }

    @Test
    fun `expired and mismatched requests are destroyed instead of wedging the broker`() {
        var now = 1L
        val broker = PickerRoundTripBroker { now }
        broker.begin(PickerRequest.SettingsBackupImport, launchAuthorization)
        now += PickerRoundTripBroker.PICKER_TTL_NANOS
        broker.recordResult("content://documents/stale.json")
        assertNull(broker.resume.value)
        assertNotNull(
            broker.begin(
                PickerRequest.SettingsBackupExport("clench-state-backup-2026-08-04.json"),
                launchAuthorization
            )
        )

        broker.recordResult("content://documents/new.json")
        broker.authorizeResult(returnedAuthorization, authorizationIsCurrent = true)
        assertNull(
            broker.consume(
                PickerPurpose.SETTINGS_BACKUP_EXPORT,
                PickerDestination.RecoveryWizard,
                returnedAuthorization,
                authorizationIsCurrent = true
            )
        )
        assertNotNull(broker.begin(PickerRequest.SettingsBackupImport, returnedAuthorization))
    }

    @Test
    fun `authorization is revoked when admission closes`() {
        val broker = PickerRoundTripBroker()
        broker.begin(PickerRequest.RecoveryBackupImport, launchAuthorization)
        broker.recordResult("content://documents/backup.json")
        broker.authorizeResult(returnedAuthorization, authorizationIsCurrent = true)
        assertNotNull(broker.resume.value)

        broker.revokeAuthorization()
        assertNull(broker.resume.value)
        assertNull(
            broker.consume(
                purpose = PickerPurpose.RECOVERY_BACKUP_IMPORT,
                destination = PickerDestination.RecoveryWizard,
                authorization = returnedAuthorization,
                authorizationIsCurrent = true
            )
        )
    }

    @Test
    fun `typed requests reject secret-shaped filenames and unsafe route identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            PickerRequest.SettingsBackupExport("abandon abandon abandon.json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PickerRequest.TapsignerWalletBackup(
                "wallet_1",
                "seed-words.aes"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PickerRequest.WalletLabelImport("wallet/escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PickerRequest.PhonePsbtExport(
                "wallet_1",
                "wallet_1_phone_signed.psbt",
                "not-a-valid-handoff-token"
            )
        }
    }
}
