package net.clench.wallet.data.backup

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.entity.WalletEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class ClenchStateBackupManagerTest {

    private fun manager(walletDao: WalletDao = mockk(relaxed = true)) = ClenchStateBackupManager(
        database = mockk<ClenchDatabase>(relaxed = true),
        walletDao = walletDao,
        transactionLabelDao = mockk<TransactionLabelDao>(relaxed = true),
        utxoMetadataDao = mockk<UtxoMetadataDao>(relaxed = true),
        settingsManager = mockk<SettingsManager>(relaxed = true)
    )

    @Test
    fun `private descriptors are detected before database writes`() {
        listOf("xprv", "yprv", "zprv", "uprv", "vprv", "tprv").forEach { prefix ->
            val privateKey = prefix + "A".repeat(107)
            assertTrue(
                "$prefix must be rejected",
                ClenchStateBackupManager.containsPrivateKeyMaterial("wpkh($privateKey/0/*)")
            )
        }
    }

    @Test
    fun `state export refuses a stored private descriptor`() = runTest {
        val walletDao = mockk<WalletDao>(relaxed = true)
        val privateKey = "tprv" + "A".repeat(107)
        coEvery { walletDao.getAll() } returns listOf(
            WalletEntity(
                id = "private-test-wallet",
                name = "Must not export",
                descriptor = "wpkh($privateKey/0/*)",
                changeDescriptor = "wpkh($privateKey/1/*)",
                isWatchOnly = true,
                isMultisig = false,
                createdAtEpochMs = 0L,
                network = "testnet"
            )
        )

        val failure = runCatching { manager(walletDao).exportStateBackupJson() }.exceptionOrNull()

        assertTrue(failure?.message?.contains("private descriptor") == true)
    }

    @Test
    fun `backup import rejects files over size limit`() = runTest {
        val oversized = "x".repeat(ClenchStateBackupManager.MAX_BACKUP_CHARS + 1)

        val failure = runCatching { manager().importStateBackupJson(oversized) }.exceptionOrNull()

        assertTrue(failure?.message?.contains("too large") == true)
    }
}
