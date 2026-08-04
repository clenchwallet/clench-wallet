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
        createPrefs()
    }

    private fun createPrefs(): android.content.SharedPreferences {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                "clench_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Never delete or replace encrypted wallet material automatically. A transient
            // Keystore error, device migration, or key invalidation must enter a recoverable
            // failure state so the original ciphertext remains available for diagnosis or
            // restoration from backup.
            throw SecureStorageUnavailableException(
                "Secure wallet storage is unavailable. Wallet secrets were preserved; restore Keystore access or recover from your seed backup.",
                e
            )
        }
    }

    private fun android.content.SharedPreferences.Editor.commitOrThrow(operation: String) {
        check(commit()) { "Secure wallet storage failed while $operation" }
    }

    /** Atomically commit all secret material created or imported for one wallet. */
    fun storeWalletSecrets(
        walletId: String,
        mnemonic: String? = null,
        secretDescriptor: String? = null,
        secretChangeDescriptor: String? = null
    ) {
        require(mnemonic != null || secretDescriptor != null || secretChangeDescriptor != null)
        val editor = prefs.edit()
        mnemonic?.let { editor.putString(mnemonicKey(walletId), it) }
        secretDescriptor?.let { editor.putString(secretDescriptorKey(walletId), it) }
        secretChangeDescriptor?.let { editor.putString(secretChangeDescriptorKey(walletId), it) }
        editor.commitOrThrow("saving wallet secret material")
    }

    /** Atomically commit a multisig wallet's local descriptor keys and phone-signer seeds. */
    fun storeMultisigWalletSecrets(
        walletId: String,
        secretDescriptor: String,
        secretChangeDescriptor: String,
        signerMnemonicsByKeyId: Map<String, String>
    ) {
        val editor = prefs.edit()
            .putString(secretDescriptorKey(walletId), secretDescriptor)
            .putString(secretChangeDescriptorKey(walletId), secretChangeDescriptor)
        signerMnemonicsByKeyId.forEach { (keyId, mnemonic) ->
            editor.putString(multisigSignerMnemonicKey(walletId, keyId), mnemonic)
        }
        editor.commitOrThrow("saving multisig phone-signer material")
    }

    /** Retrieve a mnemonic. Returns null if not found. */
    fun getMnemonic(walletId: String): String? =
        prefs.getString(mnemonicKey(walletId), null)

    /** Retrieve the secret (xprv) descriptor. Returns null for watch-only wallets. */
    fun getSecretDescriptor(walletId: String): String? =
        prefs.getString(secretDescriptorKey(walletId), null)

    /** Retrieve the secret change descriptor. Returns null for watch-only wallets. */
    fun getSecretChangeDescriptor(walletId: String): String? =
        prefs.getString(secretChangeDescriptorKey(walletId), null)

    /** Check if we have a mnemonic (i.e. wallet is not watch-only). */
    fun hasMnemonic(walletId: String): Boolean =
        prefs.contains(mnemonicKey(walletId))

    /** Retrieve a generated multisig phone-signer mnemonic. */
    fun getMultisigSignerMnemonic(walletId: String, keyId: String): String? =
        prefs.getString(multisigSignerMnemonicKey(walletId, keyId), null)

    /**
     * Delete all stale passphrase entries from encrypted prefs.
     * Passphrases are no longer stored for security — this is a one-time migration cleanup.
     */
    fun deleteAllPassphrases() {
        // SharedPreferences.commit() is synchronous and commits the removals as one editor
        // transaction. Re-read the encrypted preferences afterward so a false-successing or
        // partially applied storage implementation cannot silently retain a passphrase.
        LegacyPassphraseCleanup.deleteAndVerify(prefs)
    }

    /** Delete all stored secrets for a wallet. Call when deleting a wallet. */
    fun deleteWalletSecrets(walletId: String) {
        val editor = prefs.edit()
            .remove(mnemonicKey(walletId))
            .remove(passphraseKey(walletId))
            .remove(secretDescriptorKey(walletId))
            .remove(secretChangeDescriptorKey(walletId))
        val signerPrefix = multisigSignerMnemonicPrefix(walletId)
        prefs.all.keys
            .filter { it.startsWith(signerPrefix) }
            .forEach { editor.remove(it) }
        editor.commitOrThrow("deleting wallet secrets")
    }

    /**
     * Get or create a 32-byte random key for SQLCipher database encryption.
     * The key is stored encrypted via Android Keystore AES-GCM in EncryptedSharedPreferences.
     */
    @Synchronized
    fun getOrCreateDatabaseKey(): ByteArray {
        val existing = prefs.getString(DATABASE_KEY, null)
        if (existing != null) {
            return android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
        }
        val key = ByteArray(32)
        java.security.SecureRandom().nextBytes(key)
        prefs.edit()
            .putString(DATABASE_KEY, android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP))
            .commitOrThrow("saving the database encryption key")
        return key
    }

    /**
     * Report the actual runtime protection Android assigned to the wallet-secret master key.
     * Accessing [prefs] first ensures the key exists. No key bytes are exported.
     */
    fun walletSecretKeyProtection(): AndroidKeystoreProtection {
        return runCatching {
            prefs.contains(DATABASE_KEY)
            inspectAndroidKeystoreProtection(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }.getOrDefault(AndroidKeystoreProtection.UNKNOWN)
    }

    private fun mnemonicKey(walletId: String) = "mnemonic_$walletId"
    private fun passphraseKey(walletId: String) = "passphrase_$walletId"
    private fun secretDescriptorKey(walletId: String) = "secret_descriptor_$walletId"
    private fun secretChangeDescriptorKey(walletId: String) = "secret_change_descriptor_$walletId"
    private fun multisigSignerMnemonicKey(walletId: String, keyId: String) =
        "${multisigSignerMnemonicPrefix(walletId)}$keyId"
    private fun multisigSignerMnemonicPrefix(walletId: String) = "multisig_signer_mnemonic_${walletId}_"

    companion object {
        private const val DATABASE_KEY = "clench_database_encryption_key"
    }
}

class SecureStorageUnavailableException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)
