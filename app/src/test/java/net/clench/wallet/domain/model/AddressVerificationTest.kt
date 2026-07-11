package net.clench.wallet.domain.model

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressVerificationTest {

    @Test
    fun `parseBip21 extracts address amount label and payjoin warning`() {
        val parsed = BitcoinAddressVerifier.parseBip21(
            "bitcoin:BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KYGT080?amount=0.001&label=Alice%20Savings&pj=https%3A%2F%2Fexample.com"
        )

        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080", parsed.address)
        assertEquals(100_000L, parsed.amountSat)
        assertEquals("Alice Savings", parsed.label)
        assertEquals("Payjoin parameters are ignored by Clench.", parsed.warning)
    }

    @Test
    fun `parseBip21 rejects unsupported required parameters`() {
        val failure = runCatching {
            BitcoinAddressVerifier.parseBip21("bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT?req-extra=1")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("Unsupported required BIP-21 parameter"))
    }

    @Test
    fun `parseBip21 lowercases bech32 addresses without touching non bech32`() {
        val bech32 = BitcoinAddressVerifier.parseBip21("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KYGT080")
        val legacy = BitcoinAddressVerifier.parseBip21("1BoatSLRHtKNngkdXEeobR76b53LETtpyT")

        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080", bech32.address)
        assertEquals("1BoatSLRHtKNngkdXEeobR76b53LETtpyT", legacy.address)
    }

    @Test
    fun `parseBip21 rejects invalid amount`() {
        val failure = runCatching {
            BitcoinAddressVerifier.parseBip21("bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT?amount=-1")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("amount is invalid"))
    }

    @Test
    fun `parseBip21 preserves exact satoshi amount`() {
        val parsed = BitcoinAddressVerifier.parseBip21(
            "bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT?amount=0.00000003"
        )

        assertEquals(3L, parsed.amountSat)
    }

    @Test
    fun `parseBip21 rejects sub-satoshi precision`() {
        val failure = runCatching {
            BitcoinAddressVerifier.parseBip21(
                "bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT?amount=0.000000001"
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("amount is invalid"))
    }

    @Test
    fun `parseBip21 accepts exact amounts with trailing zeroes`() {
        val parsed = BitcoinAddressVerifier.parseBip21(
            "bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT?amount=1.000000000"
        )

        assertEquals(100_000_000L, parsed.amountSat)
    }
}
