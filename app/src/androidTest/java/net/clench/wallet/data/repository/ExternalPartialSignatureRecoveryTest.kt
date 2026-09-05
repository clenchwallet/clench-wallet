package net.clench.wallet.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.clench.wallet.data.local.KeystoreManager
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.*
import net.clench.wallet.data.network.ElectrumConnectionFactory
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.security.WalletMnemonicGenerator
import net.clench.wallet.ui.components.TapsignerPsbtSigning
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/** Finite offline native/repository integration. Public fixture keys, fictional prevout, no wallet. */
@RunWith(AndroidJUnit4::class)
class ExternalPartialSignatureRecoveryTest {
    @Test fun originalTransactionRemainsRecoverableAfterUnusablePartial() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context)
        val repository = BdkBitcoinRepository(
            context, unused<WalletDao>(), unused<TransactionDao>(), unused<TransactionLabelDao>(),
            unused<UtxoMetadataDao>(), unused<AddressBookDao>(), KeystoreManager(context), settings,
            ElectrumConnectionFactory(settings), TorAwareHttpClient(settings),
            WalletMnemonicGenerator({ error("No entropy access in this fixture") },
                { error("No mnemonic access in this fixture") }), SensitiveWalletOperationBarrier()
        )
        val fixture = Fixture()
        val original = fixture.psbt(emptyList())
        val invalid = fixture.psbt(listOf(1 to 3)) // canonical DER, but signed by a different fixture key
        val corrected = fixture.psbt(listOf(1 to 1, 2 to 2))

        var rejected = false
        val partial = try {
            repository.mergeSignedPsbt(original, original, invalid)
        } catch (_: SecurityException) {
            rejected = true
            null
        }
        if (partial != null) {
            assertFalse("An unusable partial must not become broadcast-ready", partial.readyToBroadcast)
            assertNotEquals(original, partial.psbtBase64)
            try {
                repository.mergeSignedPsbt(original, partial.psbtBase64, corrected)
                fail("Conflicting returned material must not silently replace an earlier field")
            } catch (_: SecurityException) {
                // The canonical merge's conflict boundary remains intact.
            }
        }
        Log.i("ClenchPartialRegression", "unusable_partial_rejected=$rejected; retained=${partial != null}")

        // Starting from the reviewed original, not the accumulated return, must recover.
        // This is also the positive control proving the fixture has valid math/policy.
        val recovered = repository.mergeSignedPsbt(original, original, corrected)
        assertTrue("Valid fixture cosigners must finalize the original transaction", recovered.readyToBroadcast)
        assertEquals("The reviewed source must remain untouched", fixture.psbt(emptyList()), original)
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected persistence access: ${method.name}") } as T

    private class Fixture {
        private val curve = SECNamedCurves.getByName("secp256k1")
        private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        private fun key(index: Int) = curve.g.multiply(BigInteger.valueOf(index.toLong())).getEncoded(true)
        private val script = bytes {
            write(0x52)
            (1..3).forEach { write(0x21); write(key(it)) }
            write(0x53); write(0xae)
        }
        private val transaction = bytes {
            write(le(2, 4)); write(1)
            write(ByteArray(32) { 0x44 }); write(le(0, 4)); write(0); write(le(0xffff_fffdL, 4))
            write(1); write(le(49_500, 8)); write(22)
            write(byteArrayOf(0, 0x14)); write(ByteArray(20) { 0x24 }); write(le(0, 4))
        }
        private val digest = TapsignerPsbtSigning.bip143SighashAll(transaction, 0, script, le(50_000, 8))

        fun psbt(partials: List<Pair<Int, Int>>): String = Base64.getEncoder().encodeToString(bytes {
            write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
            entry(byteArrayOf(0), transaction); write(0)
            val program = byteArrayOf(0, 0x20) + MessageDigest.getInstance("SHA-256").digest(script)
            entry(byteArrayOf(1), le(50_000, 8) + byteArrayOf(program.size.toByte()) + program)
            entry(byteArrayOf(5), script)
            partials.forEach { (publicIndex, signingIndex) ->
                entry(byteArrayOf(2) + key(publicIndex), signature(signingIndex))
            }
            write(0); write(0)
        })

        private fun signature(index: Int): ByteArray {
            val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
            signer.init(true, ECPrivateKeyParameters(BigInteger.valueOf(index.toLong()), domain))
            val (r, s) = signer.generateSignature(digest)
            val lowS = if (s > curve.n.shiftRight(1)) curve.n.subtract(s) else s
            return DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(lowS))).encoded + byteArrayOf(1)
        }

        private fun ByteArrayOutputStream.entry(key: ByteArray, value: ByteArray) {
            require(key.size < 253 && value.size < 253)
            write(key.size); write(key); write(value.size); write(value)
        }
        private fun le(value: Long, size: Int) = ByteArray(size) { (value ushr (8 * it)).toByte() }
        private fun bytes(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()
    }
}
