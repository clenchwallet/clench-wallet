package net.clench.wallet.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultisigWalletConfigParserTest {

    @Test
    fun `BSMS descriptor record expands receive path restrictions`() {
        val bsms = """
            BSMS 1.0
            wsh(sortedmulti(2,[AABBCCDD/48'/0'/0'/2']xpub6Alpha/**,[11223344/48'/0'/0'/2']xpub6Bravo/**,[55667788/48'/0'/0'/2']xpub6Charlie/**))#abcd1234
            /0/*,/1/*
            bc1qexampleaddress
        """.trimIndent()

        val parsed = MultisigWalletConfigParser.parse(bsms)

        assertEquals(
            "wsh(sortedmulti(2,[AABBCCDD/48'/0'/0'/2']xpub6Alpha/0/*,[11223344/48'/0'/0'/2']xpub6Bravo/0/*,[55667788/48'/0'/0'/2']xpub6Charlie/0/*))",
            parsed
        )
    }

    @Test
    fun `Coldcard multisig setup file becomes nested segwit sortedmulti descriptor`() {
        val coldcard = """
            # Coldcard Multisig setup file
            Name: Vault
            Policy: 2 of 3
            Format: P2SH-P2WSH

            Derivation: m/48h/0h/0h/1h

            AABBCCDD: xpub6Alpha
            11223344: xpub6Bravo
            55667788: xpub6Charlie
        """.trimIndent()

        val parsed = MultisigWalletConfigParser.parse(coldcard)

        assertEquals(
            "sh(wsh(sortedmulti(2,[AABBCCDD/48'/0'/0'/1']xpub6Alpha/0/*,[11223344/48'/0'/0'/1']xpub6Bravo/0/*,[55667788/48'/0'/0'/1']xpub6Charlie/0/*)))",
            parsed
        )
    }

    @Test
    fun `Coldcard parser rejects incomplete signer list`() {
        val coldcard = """
            Policy: 2 of 3
            Format: P2WSH
            Derivation: m/48h/0h/0h/2h
            AABBCCDD: xpub6Alpha
            11223344: xpub6Bravo
        """.trimIndent()

        assertNull(MultisigWalletConfigParser.parse(coldcard))
    }

    @Test
    fun `plain cosigner xpub is not treated as multisig config`() {
        assertNull(MultisigWalletConfigParser.parse("[AABBCCDD/48'/0'/0'/2']xpub6Alpha"))
    }

    @Test
    fun `descriptor line can be embedded in labeled export text`() {
        val parsed = MultisigWalletConfigParser.parse(
            "Descriptor: wsh(sortedmulti(2,[AABBCCDD/48'/0'/0'/2']xpub6Alpha/0/*,[11223344/48'/0'/0'/2']xpub6Bravo/0/*))#abcd1234"
        )

        assertTrue(parsed?.startsWith("wsh(sortedmulti(2,") == true)
    }
}
