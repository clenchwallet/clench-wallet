package net.clench.wallet.security

import org.bitcoindevkit.Mnemonic
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies cryptographically secure entropy for newly generated wallet material.
 *
 * Implementations must throw when entropy cannot be obtained. Callers deliberately do not
 * provide a deterministic fallback: a failed entropy source must stop wallet generation.
 */
fun interface WalletEntropySource {
    fun fill(destination: ByteArray)
}

fun interface WalletMnemonicFactory {
    fun fromEntropy(entropy: ByteArray): Mnemonic
}

@Singleton
class SecureRandomWalletEntropySource @Inject constructor() : WalletEntropySource {
    private val secureRandom = SecureRandom()

    override fun fill(destination: ByteArray) {
        require(destination.isNotEmpty()) { "Entropy destination must not be empty" }
        secureRandom.nextBytes(destination)
    }
}

@Singleton
class BdkWalletMnemonicFactory @Inject constructor() : WalletMnemonicFactory {
    override fun fromEntropy(entropy: ByteArray): Mnemonic = Mnemonic.fromEntropy(entropy)
}

/**
 * Generates BIP39 mnemonics from explicit app-owned entropy.
 *
 * Twelve words use 128 bits and 24 words use 256 bits. The temporary entropy buffer is wiped
 * whether entropy acquisition, BDK mnemonic construction, or a later native call fails.
 */
@Singleton
class WalletMnemonicGenerator @Inject constructor(
    private val entropySource: WalletEntropySource,
    private val mnemonicFactory: WalletMnemonicFactory
) {
    fun generate(wordCount: Int): Mnemonic {
        val entropy = ByteArray(entropyBytesFor(wordCount))
        return try {
            entropySource.fill(entropy)
            mnemonicFactory.fromEntropy(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    internal fun entropyBytesFor(wordCount: Int): Int = when (wordCount) {
        12 -> 16
        24 -> 32
        else -> throw IllegalArgumentException("Only 12-word or 24-word wallets are supported")
    }
}
