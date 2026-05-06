package net.clench.wallet.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.dao.TransactionDao
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.dao.AddressBookDao
import net.clench.wallet.data.local.dao.WalletKeystoreMetadataDao
import net.clench.wallet.data.repository.BdkBitcoinRepository
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keystoreManager: KeystoreManager
    ): ClenchDatabase {
        // SQLCipher encryption for Room DB.
        // In debug builds, skip encryption to avoid key rotation issues during development
        // (adb install -r can invalidate the Android Keystore master key, causing the
        // EncryptedSharedPreferences to regenerate with a new DB key, which wipes the DB).
        // In release builds, enable encryption.
        val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

        if (!isDebug) {
            System.loadLibrary("sqlcipher")

            val dbFile = context.getDatabasePath("clench.db")
            if (dbFile.exists()) {
                try {
                    val dbKey = keystoreManager.getOrCreateDatabaseKey()
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("ClenchDB", "Verifying encrypted DB...")
                    val testDb = net.sqlcipher.database.SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        String(dbKey),
                        null,
                        net.sqlcipher.database.SQLiteDatabase.OPEN_READONLY
                    )
                    testDb.close()
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("ClenchDB", "Encrypted DB verified OK")
                } catch (e: Exception) {
                    // [S-2] SECURITY: in release builds, fail-closed rather than destructively
                    // deleting the database on verification failure. An unreadable encrypted DB
                    // should surface as a recovery-required state, not silently delete wallet data.
                    if (isDebug) {
                        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("ClenchDB", "DB verification FAILED in debug — deleting (${e.javaClass.simpleName})")
                        dbFile.delete()
                        context.getDatabasePath("clench.db-journal").delete()
                        context.getDatabasePath("clench.db-shm").delete()
                        context.getDatabasePath("clench.db-wal").delete()
                    } else {
                        if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.e("ClenchDB", "DB verification FAILED in release — failing closed.")
                        throw e
                    }
                }
            }
        }

        val builder = Room.databaseBuilder(context, ClenchDatabase::class.java, "clench.db")

        if (!isDebug) {
            val dbKey = keystoreManager.getOrCreateDatabaseKey()
            val factory = SupportFactory(dbKey)
            builder.openHelperFactory(factory)
        } else {
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("ClenchDB", "Debug build — using unencrypted Room DB")
        }

        // Delete the old encrypted DB if switching from encrypted to unencrypted (debug)
        if (isDebug) {
            val dbFile = context.getDatabasePath("clench.db")
            if (dbFile.exists()) {
                try {
                    val testDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                        dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    )
                    testDb.close()
                } catch (_: Exception) {
                    // Was encrypted — delete so Room can create fresh unencrypted
                    if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.d("ClenchDB", "Migrating from encrypted to unencrypted DB (debug)")
                    dbFile.delete()
                    context.getDatabasePath("clench.db-journal").delete()
                    context.getDatabasePath("clench.db-shm").delete()
                    context.getDatabasePath("clench.db-wal").delete()
                }
            }
        }

        return builder
            .addMigrations(ClenchDatabase.MIGRATION_1_2, ClenchDatabase.MIGRATION_3_4, ClenchDatabase.MIGRATION_4_5, ClenchDatabase.MIGRATION_5_6, ClenchDatabase.MIGRATION_6_7, ClenchDatabase.MIGRATION_7_8, ClenchDatabase.MIGRATION_8_9, ClenchDatabase.MIGRATION_9_10, ClenchDatabase.MIGRATION_10_11, ClenchDatabase.MIGRATION_11_12)
            // [S-1] SECURITY: remove destructive migration fallback entirely.
            // With Room 2.6.1, the compatible fail-closed approach is simply to avoid
            // fallbackToDestructiveMigration() so missing/invalid migrations throw.
            .build()
    }

    @Provides
    fun provideWalletDao(db: ClenchDatabase): WalletDao = db.walletDao()

    @Provides
    fun provideTransactionDao(db: ClenchDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideUtxoMetadataDao(db: ClenchDatabase): UtxoMetadataDao = db.utxoMetadataDao()

    @Provides
    fun provideTransactionLabelDao(db: ClenchDatabase): TransactionLabelDao = db.transactionLabelDao()

    @Provides
    fun provideAddressBookDao(db: ClenchDatabase): AddressBookDao = db.addressBookDao()

    @Provides
    fun provideWalletKeystoreMetadataDao(db: ClenchDatabase): WalletKeystoreMetadataDao = db.walletKeystoreMetadataDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBitcoinRepository(
        impl: BdkBitcoinRepository
    ): BitcoinRepository
}
