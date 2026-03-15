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
    }

    /** Store a mnemonic (space-separated words) for a wallet id. */
    fun storeMnemonic(walletId: String, mnemonic: String) {
        prefs.edit().putString(mnemonicKey(walletId), mnemonic).apply()
    }

    /** Retrieve a mnemonic. Returns null if not found. */
    fun getMnemonic(walletId: String): String? =
        prefs.getString(mnemonicKey(walletId), null)

    /** Store a passphrase for a wallet id. */
    fun storePassphrase(walletId: String, passphrase: String) {
        prefs.edit().putString(passphraseKey(walletId), passphrase).apply()
    }

    /** Retrieve a passphrase. Returns null if none was set. */
    fun getPassphrase(walletId: String): String? =
        prefs.getString(passphraseKey(walletId), null)

    /** Delete all stored secrets for a wallet. Call when deleting a wallet. */
    fun deleteWalletSecrets(walletId: String) {
        prefs.edit()
            .remove(mnemonicKey(walletId))
            .remove(passphraseKey(walletId))
            .apply()
    }

    /** Check if we have a mnemonic (i.e. wallet is not watch-only). */
    fun hasMnemonic(walletId: String): Boolean =
        prefs.contains(mnemonicKey(walletId))

    private fun mnemonicKey(walletId: String) = "mnemonic_$walletId"
    private fun passphraseKey(walletId: String) = "passphrase_$walletId"
}
