package net.clench.wallet.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SweepDescriptorFactoryTest {

    @Test
    fun accountZeroMatchesBdkStandardDescriptors() {
        withRootKey { key ->
            SweepSeedScriptType.entries.forEach { type ->
                val actual = SweepDescriptorFactory.create(key, Network.BITCOIN, type, 0u)
                val expected = when (type) {
                    SweepSeedScriptType.LEGACY ->
                        Descriptor.newBip44(key, KeychainKind.EXTERNAL, Network.BITCOIN) to
                            Descriptor.newBip44(key, KeychainKind.INTERNAL, Network.BITCOIN)
                    SweepSeedScriptType.NESTED_SEGWIT ->
                        Descriptor.newBip49(key, KeychainKind.EXTERNAL, Network.BITCOIN) to
                            Descriptor.newBip49(key, KeychainKind.INTERNAL, Network.BITCOIN)
                    SweepSeedScriptType.NATIVE_SEGWIT ->
                        Descriptor.newBip84(key, KeychainKind.EXTERNAL, Network.BITCOIN) to
                            Descriptor.newBip84(key, KeychainKind.INTERNAL, Network.BITCOIN)
                    SweepSeedScriptType.TAPROOT ->
                        Descriptor.newBip86(key, KeychainKind.EXTERNAL, Network.BITCOIN) to
                            Descriptor.newBip86(key, KeychainKind.INTERNAL, Network.BITCOIN)
                }
                try {
                    check(actual.first.toString() == expected.first.toString())
                    check(actual.second.toString() == expected.second.toString())
                } finally {
                    actual.first.close()
                    actual.second.close()
                    expected.first.close()
                    expected.second.close()
                }
            }
        }
    }

    @Test
    fun accountOneDerivesDifferentAddresses() {
        withRootKey { key ->
            val accountZero = SweepDescriptorFactory.create(
                key,
                Network.BITCOIN,
                SweepSeedScriptType.NATIVE_SEGWIT,
                0u
            )
            val accountOne = SweepDescriptorFactory.create(
                key,
                Network.BITCOIN,
                SweepSeedScriptType.NATIVE_SEGWIT,
                1u
            )
            try {
                val zeroAddress = accountZero.first.deriveAddress(0u, Network.BITCOIN).toString()
                val oneAddress = accountOne.first.deriveAddress(0u, Network.BITCOIN).toString()
                check(zeroAddress != oneAddress)
            } finally {
                accountZero.first.close()
                accountZero.second.close()
                accountOne.first.close()
                accountOne.second.close()
            }
        }
    }

    @Test
    fun wifDescriptorsUseAValidDistinctNonSpendableChangeDescriptor() {
        val privateKey = ByteArray(32).also { it[31] = 1 }
        val wif = WifPrivateKeyParser.fromRawPrivateKey(
            privateKey,
            Network.TESTNET,
            compressed = true
        ).value

        SweepWifScriptType.entries.forEach { type ->
            val descriptors = SweepWifDescriptorFactory.create(wif, Network.TESTNET, type)
            try {
                check(descriptors.first.toString() != descriptors.second.toString())
                check(!descriptors.second.toString().contains(wif))
                descriptors.second.deriveAddress(0u, Network.TESTNET)
            } finally {
                descriptors.first.close()
                descriptors.second.close()
            }
        }
        privateKey.fill(0)
    }

    private fun withRootKey(block: (DescriptorSecretKey) -> Unit) {
        val mnemonic = Mnemonic.fromString(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        )
        val key = DescriptorSecretKey(Network.BITCOIN, mnemonic, "")
        try {
            block(key)
        } finally {
            key.destroy()
            mnemonic.destroy()
        }
    }
}
