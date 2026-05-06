package net.clench.wallet.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for sensitive wallet data (mnemonics, descriptors).
 * Uses Android Keystore + EncryptedSharedPreferences.
 *
 * ⚠️ Seeds are encrypted at rest using AES-256-GCM via Android Keystore.
 * ⚠️ Never log or transmit values retrieved from this manager.
 */
@Singleton
class KeystoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        createPrefs() ?: run {
            // Keystore corrupted (e.g. after backup restore or device migration).
            // Delete the corrupted prefs file and retry with a fresh key.
            // NOTE: all stored mnemonics will be lost — user must re-import wallets.
            if (net.clench.wallet.BuildConfig.DEBUG) android.util.Log.w("KeystoreManager", "Keystore corrupted — clearing encrypted prefs and retrying")
            context.deleteSharedPreferences("clench_secure_prefs")
            createPrefs() ?: throw IllegalStateException("Android Keystore unavailable — cannot secure wallet data")
        }
    }

    private fun createPrefs(): android.content.SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "clench_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Store a mnemonic (space-separated words) for a wallet id. */
    fun storeMnemonic(walletId: String, mnemonic: String) {
        prefs.edit().putString(mnemonicKey(walletId), mnemonic).apply()
    }

    /** Retrieve a mnemonic. Returns null if not found. */
    fun getMnemonic(walletId: String): String? =
        prefs.getString(mnemonicKey(walletId), null)

    /** @deprecated Passphrase is no longer stored. Kept for migration/cleanup only. */
    @Deprecated("Passphrase is no longer stored — do not call from new code")
    fun storePassphrase(walletId: String, passphrase: String) {
        prefs.edit().putString(passphraseKey(walletId), passphrase).apply()
    }

    /** @deprecated Passphrase is no longer stored. Kept for migration/cleanup only. */
    @Deprecated("Passphrase is no longer stored — do not call from new code")
    fun getPassphrase(walletId: String): String? =
        prefs.getString(passphraseKey(walletId), null)

    /** Store the secret (xprv) descriptor for a wallet in encrypted storage. */
    fun storeSecretDescriptor(walletId: String, descriptor: String) {
        prefs.edit().putString(secretDescriptorKey(walletId), descriptor).apply()
    }

    /** Retrieve the secret (xprv) descriptor. Returns null for watch-only wallets. */
    fun getSecretDescriptor(walletId: String): String? =
        prefs.getString(secretDescriptorKey(walletId), null)

    /** Store the secret change descriptor for a wallet. */
    fun storeSecretChangeDescriptor(walletId: String, descriptor: String) {
        prefs.edit().putString(secretChangeDescriptorKey(walletId), descriptor).apply()
    }

    /** Retrieve the secret change descriptor. Returns null for watch-only wallets. */
    fun getSecretChangeDescriptor(walletId: String): String? =
        prefs.getString(secretChangeDescriptorKey(walletId), null)

    /** Check if we have a mnemonic (i.e. wallet is not watch-only). */
    fun hasMnemonic(walletId: String): Boolean =
        prefs.contains(mnemonicKey(walletId))

    /**
     * Delete all stale passphrase entries from encrypted prefs.
     * Passphrases are no longer stored for security — this is a one-time migration cleanup.
     */
    fun deleteAllPassphrases() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("passphrase_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    /** Delete all stored secrets for a wallet. Call when deleting a wallet. */
    fun deleteWalletSecrets(walletId: String) {
        prefs.edit()
            .remove(mnemonicKey(walletId))
            .remove(passphraseKey(walletId))
            .remove(secretDescriptorKey(walletId))
            .remove(secretChangeDescriptorKey(walletId))
            .apply()
    }

    /**
     * Get or create a 32-byte random key for SQLCipher database encryption.
     * The key is stored encrypted via Android Keystore AES-GCM in EncryptedSharedPreferences.
     */
    fun getOrCreateDatabaseKey(): ByteArray {
        val existing = prefs.getString(DATABASE_KEY, null)
        if (existing != null) {
            return android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
        }
        val key = ByteArray(32)
        java.security.SecureRandom().nextBytes(key)
        prefs.edit().putString(DATABASE_KEY, android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)).apply()
        return key
    }

    private fun mnemonicKey(walletId: String) = "mnemonic_$walletId"
    private fun passphraseKey(walletId: String) = "passphrase_$walletId"
    private fun secretDescriptorKey(walletId: String) = "secret_descriptor_$walletId"
    private fun secretChangeDescriptorKey(walletId: String) = "secret_change_descriptor_$walletId"

    companion object {
        private const val DATABASE_KEY = "clench_database_encryption_key"
    }
}
