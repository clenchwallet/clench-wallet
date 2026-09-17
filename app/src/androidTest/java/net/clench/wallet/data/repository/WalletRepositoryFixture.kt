package net.clench.wallet.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.ElectrumConnectionFactory
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.security.BdkWalletMnemonicFactory
import net.clench.wallet.security.SecureRandomWalletEntropySource
import net.clench.wallet.security.WalletMnemonicGenerator

/** Only disposable local data: independent preferences, Room database and native wallet files. */
internal class WalletRepositoryFixture : AutoCloseable {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "wallet-policy-test-${UUID.randomUUID()}-"
    private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
    private val preferenceNames = mutableSetOf<String>()
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(directory, name)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = prefix + name
            preferenceNames.add(scoped)
            return base.getSharedPreferences(scoped, mode)
        }
    }
    val database = Room.inMemoryDatabaseBuilder(context, ClenchDatabase::class.java).build()
    val settings = SettingsManager(context).apply { setNetwork("testnet"); setOfflineMode(true) }
    val keystore = KeystoreManager(context)
    val barrier = SensitiveWalletOperationBarrier()
    val repository = BdkBitcoinRepository(context, database.walletDao(), database.transactionDao(),
        database.transactionLabelDao(), database.utxoMetadataDao(), database.addressBookDao(), keystore,
        settings, ElectrumConnectionFactory(settings), TorAwareHttpClient(settings),
        WalletMnemonicGenerator(SecureRandomWalletEntropySource(), BdkWalletMnemonicFactory()), barrier)

    override fun close() {
        runBlocking { database.walletDao().getAll().forEach { repository.deleteWallet(it.id) } }
        database.close()
        preferenceNames.forEach { base.deleteSharedPreferences(it) }
        directory.deleteRecursively()
    }
}
