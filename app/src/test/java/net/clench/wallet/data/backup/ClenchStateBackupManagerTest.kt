package net.clench.wallet.data.backup

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.dao.WalletDao
import org.junit.Assert.assertTrue
import org.junit.Test

class ClenchStateBackupManagerTest {

    private fun manager() = ClenchStateBackupManager(
        database = mockk<ClenchDatabase>(relaxed = true),
        walletDao = mockk<WalletDao>(relaxed = true),
        transactionLabelDao = mockk<TransactionLabelDao>(relaxed = true),
        utxoMetadataDao = mockk<UtxoMetadataDao>(relaxed = true),
        settingsManager = mockk<SettingsManager>(relaxed = true)
    )

    @Test
    fun `private descriptors are detected before database writes`() {
        val privateKey = "xprv" + "A".repeat(107)

        assertTrue(ClenchStateBackupManager.containsPrivateKeyMaterial("wpkh($privateKey/0/*)"))
    }

    @Test
    fun `backup import rejects files over size limit`() = runTest {
        val oversized = "x".repeat(ClenchStateBackupManager.MAX_BACKUP_CHARS + 1)

        val failure = runCatching { manager().importStateBackupJson(oversized) }.exceptionOrNull()

        assertTrue(failure?.message?.contains("too large") == true)
    }
}
