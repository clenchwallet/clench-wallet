package net.clench.wallet.ui.screens

import net.clench.wallet.ui.components.TapsignerAccountXpubResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletInfoTapsignerMatchTest {

    @Test
    fun `wallet verification accepts canonical Tapsigner xpub`() {
        val result = accountResult(
            canonical = "xpubCanonicalAccount",
            cardReturned = "xpubLegacyAccount"
        )

        assertTrue(
            descriptorContainsTapsignerAccountXpub(
                descriptor = "wpkh([aabbccdd/84'/0'/0']xpubCanonicalAccount/0/*)",
                changeDescriptor = "wpkh([aabbccdd/84'/0'/0']xpubCanonicalAccount/1/*)",
                result = result
            )
        )
    }

    @Test
    fun `wallet verification accepts legacy card xpub parent fingerprint encoding`() {
        val result = accountResult(
            canonical = "xpubCanonicalAccount",
            cardReturned = "xpubLegacyAccount"
        )

        assertTrue(
            descriptorContainsTapsignerAccountXpub(
                descriptor = "wpkh([aabbccdd/84'/0'/0']xpubLegacyAccount/0/*)",
                changeDescriptor = "wpkh([aabbccdd/84'/0'/0']xpubLegacyAccount/1/*)",
                result = result
            )
        )
    }

    @Test
    fun `wallet verification rejects unrelated account xpub`() {
        val result = accountResult(
            canonical = "xpubCanonicalAccount",
            cardReturned = "xpubLegacyAccount"
        )

        assertFalse(
            descriptorContainsTapsignerAccountXpub(
                descriptor = "wpkh([aabbccdd/84'/0'/0']xpubDifferentAccount/0/*)",
                changeDescriptor = "wpkh([aabbccdd/84'/0'/0']xpubDifferentAccount/1/*)",
                result = result
            )
        )
    }

    private fun accountResult(
        canonical: String,
        cardReturned: String
    ): TapsignerAccountXpubResult {
        return TapsignerAccountXpubResult(
            xpub = canonical,
            cardReturnedXpub = cardReturned,
            originWrappedXpub = "[aabbccdd/84'/0'/0']$canonical",
            masterFingerprint = "aabbccdd",
            derivationPath = "m/84'/0'/0'",
            isTestnet = false,
            summary = "verified"
        )
    }
}
