package net.clench.wallet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.clench.wallet.data.local.dao.TransactionDao
import net.clench.wallet.data.local.dao.TransactionLabelDao
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.entity.TransactionEntity
import net.clench.wallet.data.local.entity.TransactionLabelEntity
import net.clench.wallet.data.local.entity.UtxoMetadataEntity
import net.clench.wallet.data.local.entity.WalletEntity

@Database(
    entities = [WalletEntity::class, TransactionEntity::class, UtxoMetadataEntity::class, TransactionLabelEntity::class],
    version = 9,
    exportSchema = true
)
abstract class ClenchDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun transactionDao(): TransactionDao
    abstract fun utxoMetadataDao(): UtxoMetadataDao
    abstract fun transactionLabelDao(): TransactionLabelDao

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

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE wallets ADD COLUMN identiconBytes BLOB")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS utxo_metadata (
                        outpoint TEXT NOT NULL PRIMARY KEY,
                        walletId TEXT NOT NULL,
                        label TEXT,
                        isFrozen INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS transaction_labels (
                        key TEXT NOT NULL PRIMARY KEY,
                        walletId TEXT NOT NULL,
                        txid TEXT NOT NULL,
                        label TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
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
