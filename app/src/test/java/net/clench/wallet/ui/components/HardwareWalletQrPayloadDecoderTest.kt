package net.clench.wallet.ui.components

import com.sparrowwallet.hummingbird.registry.CryptoECKey
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.MultiKey
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.URHDKey
import com.sparrowwallet.hummingbird.registry.URKeypath
import com.sparrowwallet.hummingbird.registry.UROutputDescriptor
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.PairPathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.PathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.WildcardPathComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareWalletQrPayloadDecoderTest {

    @Test
    fun `crypto output multisig decodes to full sortedmulti descriptor`() {
        val output = CryptoOutput(
            listOf(ScriptExpression.WITNESS_SCRIPT_HASH, ScriptExpression.SORTED_MULTISIG),
            MultiKey(
                2,
                emptyList<CryptoECKey>(),
                listOf(testKey(1), testKey(2), testKey(3))
            )
        )

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNotNull(decoded)
        decoded!!
        assertTrue(decoded.startsWith("wsh(sortedmulti(2,"))
        assertTrue(decoded.contains("[AABBCC01/48'/0'/0'/2']xpub"))
        assertTrue(decoded.contains("[AABBCC02/48'/0'/0'/2']xpub"))
        assertTrue(decoded.contains("[AABBCC03/48'/0'/0'/2']xpub"))
        assertEquals(3, Regex("/0/\\*").findAll(decoded).count())
        assertFalse(decoded.matches(Regex("^\\[[^]]+]xpub.*")))
    }

    @Test
    fun `crypto output multisig uses external branch from paired child path`() {
        val output = CryptoOutput(
            listOf(
                ScriptExpression.SCRIPT_HASH,
                ScriptExpression.WITNESS_SCRIPT_HASH,
                ScriptExpression.SORTED_MULTISIG
            ),
            MultiKey(
                2,
                emptyList<CryptoECKey>(),
                listOf(testKey(1, pairedChildren()), testKey(2, pairedChildren()))
            )
        )

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNotNull(decoded)
        decoded!!
        assertTrue(decoded.startsWith("sh(wsh(sortedmulti(2,"))
        assertEquals(2, Regex("/0/\\*").findAll(decoded).count())
        assertFalse(decoded.contains("/1/*"))
    }

    @Test
    fun `ur output descriptor expands all multisig placeholders`() {
        val output = UROutputDescriptor(
            "wsh(sortedmulti(2,@0/**,@1/**,@2/**))",
            listOf(testKey(1), testKey(2), testKey(3))
        )

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNotNull(decoded)
        decoded!!
        assertTrue(decoded.startsWith("wsh(sortedmulti(2,"))
        assertTrue(decoded.contains("[AABBCC01/48'/0'/0'/2']xpub"))
        assertTrue(decoded.contains("[AABBCC02/48'/0'/0'/2']xpub"))
        assertTrue(decoded.contains("[AABBCC03/48'/0'/0'/2']xpub"))
        assertEquals(3, Regex("/0/\\*").findAll(decoded).count())
    }

    @Test
    fun `ur output descriptor with unresolved multisig placeholder does not return first key`() {
        val output = UROutputDescriptor(
            "wsh(sortedmulti(2,@0/**,@1/**,@2/**))",
            listOf(testKey(1), testKey(2))
        )

        val decoded = HardwareWalletQrPayloadDecoder.decodeUrPayload(output.toUR())

        assertNull(decoded)
    }

    private fun testKey(index: Int, children: URKeypath? = null): CryptoHDKey {
        val publicKey = ByteArray(33) { i -> (index + i).toByte() }.also { it[0] = 0x02 }
        val chainCode = ByteArray(32) { i -> (index * 3 + i).toByte() }
        val fingerprint = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), index.toByte())
        val origin = URKeypath(
            listOf<PathComponent>(
                IndexPathComponent(48, true),
                IndexPathComponent(0, true),
                IndexPathComponent(0, true),
                IndexPathComponent(2, true)
            ),
            fingerprint
        )
        return URHDKey(
            false,
            publicKey,
            chainCode,
            null,
            origin,
            children,
            fingerprint
        )
    }

    private fun pairedChildren(): URKeypath {
        return URKeypath(
            listOf<PathComponent>(
                PairPathComponent(
                    IndexPathComponent(0, false),
                    IndexPathComponent(1, false)
                ),
                WildcardPathComponent(false)
            ),
            null
        )
    }
}
