package net.clench.wallet.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import net.clench.wallet.data.local.dao.TransactionDao
import net.clench.wallet.data.local.dao.WalletDao
import net.clench.wallet.data.local.entity.TransactionEntity
import net.clench.wallet.data.local.entity.WalletEntity

@Database(
    entities = [WalletEntity::class, TransactionEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ClenchDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun transactionDao(): TransactionDao
}
