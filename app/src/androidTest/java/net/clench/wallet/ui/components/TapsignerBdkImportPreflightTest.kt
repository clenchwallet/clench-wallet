package net.clench.wallet.ui.components

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.clench.wallet.data.repository.SensitiveWalletOperationBarrier
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TapsignerBdkImportPreflightTest {
    @Test
    fun canonicalMainnetAndTestnetAccountKeysPassTheRealBdkParser() {
        listOf(false, true).forEach { isTestnet ->
            val path = TapsignerNfcReader.singleSigAccountPath(isTestnet)
            val chainCode = ByteArray(32) { (it + 3).toByte() }
            val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(
                ByteArray(32).also { it[31] = if (isTestnet) 10 else 9 }
            )
            val returned = serializedXpub(isTestnet, path, chainCode, pubkey)
            val xpub = TapsignerNfcReader.canonicalTapsignerAccountXpub(
                returnedXpub = returned,
                expectedChainCode = chainCode,
                expectedPubkey = pubkey,
                expectedPath = path,
                isTestnet = isTestnet
            )
            val accountPath = if (isTestnet) "84'/1'/0'" else "84'/0'/0'"
            val origin = "[deadbeef/$accountPath]$xpub"

            val descriptor = TapsignerBdkImportPreflight.validatedReceiveDescriptor(
                originWrappedXpub = origin,
                isTestnet = isTestnet,
                operationBarrier = SensitiveWalletOperationBarrier()
            )

            assertTrue(descriptor.startsWith("wpkh("))
            assertTrue(descriptor.contains("/0/*)"))
        }
    }

    @Test
    fun preflightRejectsNetworkMismatchAndMalformedOrigin() {
        val path = TapsignerNfcReader.singleSigAccountPath(isTestnet = false)
        val chainCode = ByteArray(32) { (it + 11).toByte() }
        val pubkey = CoinkiteTapCardVerifier.publicKeyFromPrivateKey(
            ByteArray(32).also { it[31] = 12 }
        )
        val xpub = TapsignerNfcReader.canonicalTapsignerAccountXpub(
            returnedXpub = serializedXpub(false, path, chainCode, pubkey),
            expectedChainCode = chainCode,
            expectedPubkey = pubkey,
            expectedPath = path,
            isTestnet = false
        )

        val mismatch = assertThrows(IllegalArgumentException::class.java) {
            TapsignerBdkImportPreflight.validatedReceiveDescriptor(
                originWrappedXpub = "[deadbeef/84'/0'/0']$xpub",
                isTestnet = true,
                operationBarrier = SensitiveWalletOperationBarrier()
            )
        }
        assertTrue(mismatch.message!!.contains("serialization was invalid"))

        assertThrows(IllegalArgumentException::class.java) {
            TapsignerBdkImportPreflight.validatedReceiveDescriptor(
                originWrappedXpub = xpub,
                isTestnet = false,
                operationBarrier = SensitiveWalletOperationBarrier()
            )
        }
    }

    private fun serializedXpub(
        isTestnet: Boolean,
        path: List<Long>,
        chainCode: ByteArray,
        pubkey: ByteArray
    ): ByteArray = ByteArray(78).also { bytes ->
        val version = if (isTestnet) {
            byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xcf.toByte())
        } else {
            byteArrayOf(0x04, 0x88.toByte(), 0xb2.toByte(), 0x1e)
        }
        version.copyInto(bytes, 0)
        bytes[4] = path.size.toByte()
        val child = path.last()
        bytes[9] = (child ushr 24).toByte()
        bytes[10] = (child ushr 16).toByte()
        bytes[11] = (child ushr 8).toByte()
        bytes[12] = child.toByte()
        chainCode.copyInto(bytes, 13)
        pubkey.copyInto(bytes, 45)
    }
}
