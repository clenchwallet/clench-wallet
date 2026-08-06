package net.clench.wallet.data.local

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Verifies an existing encrypted wallet database without mutating or replacing it.
 *
 * Any SQLCipher failure is deliberately allowed to propagate. Release startup uses this
 * preflight before constructing Room so an unreadable wallet fails closed and remains available
 * for recovery. The caller-owned verification key is always wiped after the database handle has
 * closed, including when open fails.
 */
object SqlCipherDatabasePreflight {
    fun verifyExisting(databaseFile: File, databaseKey: ByteArray) {
        try {
            if (!databaseFile.exists()) return

            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                databaseKey,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null
            ).use { database ->
                database.rawQuery("SELECT COUNT(*) FROM sqlite_master", emptyArray()).use { cursor ->
                    check(cursor.moveToFirst()) { "SQLCipher preflight returned no result" }
                }
            }
        } finally {
            databaseKey.fill(0)
        }
    }
}
