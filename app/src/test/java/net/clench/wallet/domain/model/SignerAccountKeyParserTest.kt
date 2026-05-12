package net.clench.wallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignerAccountKeyParserTest {

    @Test
    fun `origin wrapped xpub is normalized with fingerprint and path`() {
        val parsed = SignerAccountKeyParser.parse("[73c5da0a/48'/0'/0'/2']xpub6Alpha/0/*")

        assertEquals("73C5DA0A", parsed?.fingerprint)
        assertEquals("m/48'/0'/0'/2'", parsed?.derivationPath)
        assertEquals("xpub6Alpha", parsed?.xpub)
        assertEquals("[73C5DA0A/48'/0'/0'/2']xpub6Alpha", parsed?.keyWithOrigin)
    }

    @Test
    fun `bare xpub can be origin wrapped from fallback metadata`() {
        val parsed = SignerAccountKeyParser.parse(
            raw = "xpub6Bravo",
            fallbackFingerprint = "aabbccdd",
            fallbackDerivationPath = "48'/0'/0'/2'"
        )

        assertEquals("[AABBCCDD/48'/0'/0'/2']xpub6Bravo", parsed?.keyWithOrigin)
    }

    @Test
    fun `validation requires signer origin data`() {
        val error = SignerAccountKeyParser.validationError("xpub6Alpha", isTestnet = false)

        assertEquals(
            "missing key origin. Add the master fingerprint and derivation path before using this signer",
            error
        )
    }

    @Test
    fun `validation accepts complete mainnet native multisig account key`() {
        val error = SignerAccountKeyParser.validationError(
            "[73C5DA0A/48'/0'/0'/2']xpub6Alpha",
            isTestnet = false
        )

        assertNull(error)
    }

    @Test
    fun `validation rejects single sig derivation for multisig signer`() {
        val error = SignerAccountKeyParser.validationError(
            "[73C5DA0A/84'/0'/0']xpub6Alpha",
            isTestnet = false
        )

        assertTrue(error?.contains("BIP48") == true)
    }

    @Test
    fun `validation rejects wrong network key`() {
        val error = SignerAccountKeyParser.validationError(
            "[73C5DA0A/48'/1'/0'/2']tpubDAlpha",
            isTestnet = false
        )

        assertEquals("testnet public key used while Clench is set to mainnet", error)
    }

    @Test
    fun `validation rejects wrong derivation coin type`() {
        val error = SignerAccountKeyParser.validationError(
            "[73C5DA0A/48'/1'/0'/2']xpub6Alpha",
            isTestnet = false
        )

        assertEquals("origin path coin type 1 does not match mainnet", error)
    }

    @Test
    fun `json export prefers multisig key material over single sig material`() {
        val parsed = SignerAccountKeyParser.parse(
            """
            {
              "xfp": "73c5da0a",
              "p2wpkh": {
                "deriv": "m/84'/0'/0'",
                "xpub": "xpub6SingleSig"
              },
              "p2wsh": {
                "deriv": "m/48'/0'/0'/2'",
                "xpub": "Zpub6Multisig"
              }
            }
            """.trimIndent()
        )

        assertEquals("[73C5DA0A/48'/0'/0'/2']Zpub6Multisig", parsed?.keyWithOrigin)
    }

    @Test
    fun `validation accepts complete mainnet native single sig account key`() {
        val error = SignerAccountKeyParser.validationError(
            "[73C5DA0A/84'/0'/0']xpub6Alpha",
            isTestnet = false,
            scriptType = SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT
        )

        assertNull(error)
    }

    @Test
    fun `validation rejects multisig derivation for single sig signer`() {
        val error = SignerAccountKeyParser.validationError(
            "[73C5DA0A/48'/0'/0'/2']xpub6Alpha",
            isTestnet = false,
            scriptType = SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT
        )

        assertTrue(error?.contains("BIP84") == true)
    }

    @Test
    fun `json export prefers single sig key material for single sig signer`() {
        val parsed = SignerAccountKeyParser.parse(
            raw = """
            {
              "xfp": "73c5da0a",
              "p2wpkh": {
                "deriv": "m/84'/0'/0'",
                "xpub": "xpub6SingleSig"
              },
              "p2wsh": {
                "deriv": "m/48'/0'/0'/2'",
                "xpub": "Zpub6Multisig"
              }
            }
            """.trimIndent(),
            scriptType = SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT
        )

        assertEquals("[73C5DA0A/84'/0'/0']xpub6SingleSig", parsed?.keyWithOrigin)
    }

    @Test
    fun `validation rejects multisig prefix for single sig signer`() {
        val error = SignerAccountKeyParser.validationError(
            "[73C5DA0A/84'/0'/0']Zpub6Alpha",
            isTestnet = false,
            scriptType = SignerAccountKeyParser.SCRIPT_SINGLE_SIG_NATIVE_SEGWIT
        )

        assertTrue(error?.contains("multisig extended key prefix") == true)
    }

    @Test
    fun `validation rejects single sig prefix for multisig signer`() {
        val error = SignerAccountKeyParser.validationError(
            "[73C5DA0A/48'/0'/0'/2']zpub6Alpha",
            isTestnet = false
        )

        assertTrue(error?.contains("single-sig extended key prefix") == true)
    }
}
