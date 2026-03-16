package net.clench.wallet.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import net.clench.wallet.data.local.ClenchDatabase
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.dao.TransactionDao
import net.clench.wallet.data.local.dao.WalletDao
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
        // Load SQLCipher native libs
        System.loadLibrary("sqlcipher")

        val dbKey = keystoreManager.getOrCreateDatabaseKey()
        val factory = SupportOpenHelperFactory(dbKey)

        return Room.databaseBuilder(context, ClenchDatabase::class.java, "clench.db")
            .openHelperFactory(factory)
            .addMigrations(ClenchDatabase.MIGRATION_1_2, ClenchDatabase.MIGRATION_3_4, ClenchDatabase.MIGRATION_4_5, ClenchDatabase.MIGRATION_5_6)
            .fallbackToDestructiveMigration() // safety net for any future unhandled versions
            .build()
    }

    @Provides
    fun provideWalletDao(db: ClenchDatabase): WalletDao = db.walletDao()

    @Provides
    fun provideTransactionDao(db: ClenchDatabase): TransactionDao = db.transactionDao()
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
