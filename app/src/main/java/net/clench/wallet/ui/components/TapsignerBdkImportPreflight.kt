package net.clench.wallet.ui.components

import android.util.Log
import net.clench.wallet.BuildConfig
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import net.clench.wallet.data.repository.nativeCloseAction
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorPublicKey
import org.bitcoindevkit.NetworkKind

/**
 * Validate the exact TAPSIGNER import value with the same native parser used
 * later by the wallet repository. Keeping this check at the NFC boundary
 * prevents an authenticated card result from being mistaken for a valid BDK
 * descriptor when a runtime handoff or native-parser incompatibility exists.
 */
internal object TapsignerBdkImportPreflight {
    fun validatedReceiveDescriptor(
        originWrappedXpub: String,
        isTestnet: Boolean,
        operationBarrier: SensitiveWalletOperationBarrier
    ): String = operationBarrier.withSynchronousLease {
        require(originWrappedXpub.none(Char::isWhitespace)) {
            "TAPSIGNER xpub contained unexpected whitespace"
        }

        val keyStart = originWrappedXpub.indexOf(']') + 1
        require(keyStart > 1 && keyStart < originWrappedXpub.length) {
            "TAPSIGNER xpub origin was invalid"
        }
        val encoded = originWrappedXpub.substring(keyStart)
        val expectedPrefix = if (isTestnet) "tpub" else "xpub"
        require(encoded.length == 111 && encoded.startsWith(expectedPrefix)) {
            "TAPSIGNER xpub serialization was invalid"
        }

        val networkKind = if (isTestnet) NetworkKind.TEST else NetworkKind.MAIN
        val receiveText = "wpkh($originWrappedXpub/0/*)"
        val changeText = "wpkh($originWrappedXpub/1/*)"

        var parsedKey: DescriptorPublicKey? = null
        var receive: Descriptor? = null
        var change: Descriptor? = null
        var stage = "key"
        try {
            parsedKey = DescriptorPublicKey.fromString(originWrappedXpub)
            stage = "receive descriptor"
            receive = Descriptor(receiveText, networkKind)
            receive.sanityCheck()
            stage = "change descriptor"
            change = Descriptor(changeText, networkKind)
            change.sanityCheck()
            stage = "descriptor serialization"
            val normalizedReceive = receive.toString()
            if (BuildConfig.DEBUG) {
                Log.d("Tapsigner", "xpub preflight passed: key, receive descriptor, change descriptor")
            }
            // Feed the repository a descriptor already accepted and normalized by
            // its own BDK native library. The repository strips/recomputes the
            // checksum and constructs the matching change branch.
            return@withSynchronousLease normalizedReceive
        } catch (failure: Exception) {
            throw IllegalStateException(
                "TAPSIGNER xpub failed local BDK validation at $stage " +
                    "(${failure.javaClass.simpleName})"
            )
        } finally {
            operationBarrier.closeNativeResourcesOrFail(
                listOfNotNull(
                    nativeCloseAction(change) { it.close() },
                    nativeCloseAction(receive) { it.close() },
                    nativeCloseAction(parsedKey) { it.close() }
                )
            )
        }
    }
}
