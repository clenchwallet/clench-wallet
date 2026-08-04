package net.clench.wallet.data.local

import android.content.SharedPreferences

/** Synchronously removes and verifies legacy passphrases without exposing preference keys. */
internal object LegacyPassphraseCleanup {
    private const val LEGACY_PREFIX = "passphrase_"

    fun deleteAndVerify(prefs: SharedPreferences) {
        val legacyKeys = readLegacyKeys(prefs)
        if (legacyKeys.isEmpty()) return

        val committed = try {
            val editor = prefs.edit()
            legacyKeys.forEach(editor::remove)
            editor.commit()
        } catch (_: Exception) {
            throw SecureStorageCleanupException()
        }
        if (!committed || readLegacyKeys(prefs).isNotEmpty()) {
            throw SecureStorageCleanupException()
        }
    }

    private fun readLegacyKeys(prefs: SharedPreferences): List<String> = try {
        prefs.all.keys.filter { it.startsWith(LEGACY_PREFIX) }
    } catch (_: Exception) {
        throw SecureStorageCleanupException()
    }
}

class SecureStorageCleanupException : IllegalStateException(
    "Secure wallet storage cleanup could not be verified. Wallet access was stopped."
)
