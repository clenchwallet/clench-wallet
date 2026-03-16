package net.clench.wallet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.clench.wallet.data.local.dao.TransactionDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.entity.TransactionEntity
import net.clench.wallet.data.local.entity.WalletEntity

@Database(
    entities = [WalletEntity::class, TransactionEntity::class],
    version = 6,
    exportSchema = true
)
abstract class ClenchDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE wallets ADD COLUMN network TEXT NOT NULL DEFAULT 'mainnet'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE wallets ADD COLUMN preferredHardwareWallet TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE wallets ADD COLUMN hasPassphrase INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Recreate transactions table with composite PK
                database.execSQL("DROP TABLE IF EXISTS transactions")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        txid TEXT NOT NULL,
                        walletId TEXT NOT NULL,
                        amountSat INTEGER NOT NULL,
                        feeSat INTEGER,
                        timestampEpochMs INTEGER,
                        confirmations INTEGER NOT NULL,
                        direction TEXT NOT NULL,
                        address TEXT,
                        PRIMARY KEY(txid, walletId)
                    )
                """.trimIndent())
            }
        }
    }
}
