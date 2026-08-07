package net.clench.wallet.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.domain.model.toNetworkKind
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SweepDescriptorFactoryTest {
    private val operationBarrier = SensitiveWalletOperationBarrier()

    @Test
    fun accountZeroMatchesBdkStandardDescriptors() {
        withRootKey { key ->
            SweepSeedScriptType.entries.forEach { type ->
                val actual = SweepDescriptorFactory.create(
                    key,
                    Network.BITCOIN,
                    type,
                    0u,
                    operationBarrier
                )
                val expected = when (type) {
                    SweepSeedScriptType.LEGACY ->
                        Descriptor.newBip44(key, KeychainKind.EXTERNAL, Network.BITCOIN.toNetworkKind()) to
                            Descriptor.newBip44(key, KeychainKind.INTERNAL, Network.BITCOIN.toNetworkKind())
                    SweepSeedScriptType.NESTED_SEGWIT ->
                        Descriptor.newBip49(key, KeychainKind.EXTERNAL, Network.BITCOIN.toNetworkKind()) to
                            Descriptor.newBip49(key, KeychainKind.INTERNAL, Network.BITCOIN.toNetworkKind())
                    SweepSeedScriptType.NATIVE_SEGWIT ->
                        Descriptor.newBip84(key, KeychainKind.EXTERNAL, Network.BITCOIN.toNetworkKind()) to
                            Descriptor.newBip84(key, KeychainKind.INTERNAL, Network.BITCOIN.toNetworkKind())
                    SweepSeedScriptType.TAPROOT ->
                        Descriptor.newBip86(key, KeychainKind.EXTERNAL, Network.BITCOIN.toNetworkKind()) to
                            Descriptor.newBip86(key, KeychainKind.INTERNAL, Network.BITCOIN.toNetworkKind())
                }
                try {
                    check(actual.first.toString() == expected.first.toString())
                    check(actual.second.toString() == expected.second.toString())
                    check(actual.first.hasWildcard())
                    check(actual.second.hasWildcard())
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
                0u,
                operationBarrier
            )
            val accountOne = SweepDescriptorFactory.create(
                key,
                Network.BITCOIN,
                SweepSeedScriptType.NATIVE_SEGWIT,
                1u,
                operationBarrier
            )
            try {
                val zeroAddress = deriveAddress(accountZero.first, Network.BITCOIN)
                val oneAddress = deriveAddress(accountOne.first, Network.BITCOIN)
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
            val descriptors = SweepWifDescriptorFactory.create(
                wif,
                Network.TESTNET,
                type,
                operationBarrier
            )
            try {
                check(descriptors.first.toString() != descriptors.second.toString())
                check(!descriptors.second.toString().contains(wif))
                deriveAddress(descriptors.second, Network.TESTNET)
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
        val key = DescriptorSecretKey(Network.BITCOIN.toNetworkKind(), mnemonic, "")
        try {
            check('*' !in key.toString())
            block(key)
        } finally {
            key.destroy()
            mnemonic.destroy()
        }
    }

    private fun deriveAddress(descriptor: Descriptor, network: Network): String {
        val address = descriptor.deriveAddress(0u, network)
        return try {
            address.toString()
        } finally {
            address.close()
        }
    }
}
