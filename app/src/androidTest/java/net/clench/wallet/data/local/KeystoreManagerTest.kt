package net.clench.wallet.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreManagerTest {

    @Test
    fun walletSecretsCommitAndDeleteAsOneRecordSet() {
        val manager = KeystoreManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val walletId = "instrumentation-${UUID.randomUUID()}"
        try {
            manager.storeWalletSecrets(
                walletId = walletId,
                mnemonic = "test mnemonic material",
                secretDescriptor = "test receive descriptor",
                secretChangeDescriptor = "test change descriptor"
            )

            check(manager.getMnemonic(walletId) == "test mnemonic material")
            check(manager.getSecretDescriptor(walletId) == "test receive descriptor")
            check(manager.getSecretChangeDescriptor(walletId) == "test change descriptor")
        } finally {
            manager.deleteWalletSecrets(walletId)
        }

        check(manager.getMnemonic(walletId) == null)
        check(manager.getSecretDescriptor(walletId) == null)
        check(manager.getSecretChangeDescriptor(walletId) == null)
    }

    @Test
    fun multisigSecretsCommitTogether() {
        val manager = KeystoreManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val walletId = "instrumentation-${UUID.randomUUID()}"
        try {
            manager.storeMultisigWalletSecrets(
                walletId = walletId,
                secretDescriptor = "test multisig receive descriptor",
                secretChangeDescriptor = "test multisig change descriptor",
                signerMnemonicsByKeyId = mapOf("signer-a" to "test signer mnemonic")
            )

            check(manager.getSecretDescriptor(walletId) == "test multisig receive descriptor")
            check(manager.getSecretChangeDescriptor(walletId) == "test multisig change descriptor")
            check(manager.getMultisigSignerMnemonic(walletId, "signer-a") == "test signer mnemonic")
        } finally {
            manager.deleteWalletSecrets(walletId)
        }
    }
}
