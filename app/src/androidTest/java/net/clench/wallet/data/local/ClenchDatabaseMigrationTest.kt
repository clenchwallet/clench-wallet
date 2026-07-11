package net.clench.wallet.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClenchDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClenchDatabase::class.java
    )

    @Test
    fun migrate12To13CreatesAndValidatesSignerVaultSchema() {
        helper.createDatabase(TEST_DATABASE, 12).close()

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            13,
            true,
            ClenchDatabase.MIGRATION_12_13
        ).use { database ->
            database.query("SELECT COUNT(*) FROM saved_signers").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(0) == 0)
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-12-13"
    }
}
