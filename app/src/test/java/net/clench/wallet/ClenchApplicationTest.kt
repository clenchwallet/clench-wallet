package net.clench.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import net.clench.wallet.security.CrashReportSanitizer

/**
 * Tests for ClenchApplication.sanitizeCrashReport() — ensures sensitive data is stripped.
 *
 * Note: sanitizeCrashReport is an instance method on ClenchApplication, but we can
 * test it by creating a testable wrapper since it doesn't use Android APIs.
 */
class ClenchApplicationTest {

    private fun sanitizeCrashReport(report: String): String = CrashReportSanitizer.sanitize(report)

    @Test
    fun `strips xprv from crash report`() {
        val fakeXprv = "xprv" + "A".repeat(107) // typical xprv is ~111 chars
        val report = "Error at $fakeXprv in wallet"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_EXTENDED_KEY]"))
        assertFalse(sanitized.contains(fakeXprv))
    }

    @Test
    fun `strips xpub from crash report`() {
        val fakeXpub = "xpub" + "B".repeat(107)
        val report = "Descriptor: $fakeXpub"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_EXTENDED_KEY]"))
        assertFalse(sanitized.contains(fakeXpub))
    }

    @Test
    fun `strips tprv from crash report`() {
        val fakeTprv = "tprv" + "C".repeat(107)
        val report = "Key: $fakeTprv"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_EXTENDED_KEY]"))
        assertFalse(sanitized.contains(fakeTprv))
    }

    @Test
    fun `strips tpub from crash report`() {
        val fakeTpub = "tpub" + "D".repeat(107)
        val report = "Key: $fakeTpub"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_EXTENDED_KEY]"))
        assertFalse(sanitized.contains(fakeTpub))
    }

    @Test
    fun `strips zpub from crash report`() {
        val fakeZpub = "zpub" + "E".repeat(107)
        val report = "Import: $fakeZpub"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_EXTENDED_KEY]"))
        assertFalse(sanitized.contains(fakeZpub))
    }

    @Test
    fun `strips 12 word mnemonic from crash report`() {
        val mnemonic = "abandon ability able about above absent absorb abstract absurd abuse access acid"
        val report = "Error: mnemonic was $mnemonic in log"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_MNEMONIC]"))
        assertFalse(sanitized.contains(mnemonic))
    }

    @Test
    fun `strips 24 word mnemonic from crash report`() {
        val mnemonic = "abandon ability able about above absent absorb abstract absurd abuse access acid " +
            "abandon ability able about above absent absorb abstract absurd abuse access acid"
        val report = "Seed: $mnemonic"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_MNEMONIC]"))
        assertFalse(sanitized.contains(mnemonic))
    }

    @Test
    fun `does not strip normal text`() {
        val report = "NullPointerException at HomeViewModel.load()"
        val sanitized = sanitizeCrashReport(report)
        assertEquals(report, sanitized)
    }

    @Test
    fun `does not strip short key-like strings`() {
        // xprv with too few chars should not be redacted
        val shortKey = "xprv123"
        val report = "Debug: $shortKey"
        val sanitized = sanitizeCrashReport(report)
        assertEquals(report, sanitized)
    }

    @Test
    fun `handles empty report`() {
        assertEquals("", sanitizeCrashReport(""))
    }

    @Test
    fun `handles multiple sensitive items in one report`() {
        val fakeXprv = "xprv" + "A".repeat(107)
        val fakeXpub = "xpub" + "B".repeat(107)
        val report = "Keys: $fakeXprv and $fakeXpub"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_EXTENDED_KEY]"))
        assertFalse(sanitized.contains(fakeXprv))
        assertFalse(sanitized.contains(fakeXpub))
    }

    @Test
    fun `strips WIF private key from crash report`() {
        val wif = "K" + "A".repeat(51)
        val sanitized = sanitizeCrashReport("WIF=$wif")

        assertTrue(sanitized.contains("[REDACTED_WIF]"))
        assertFalse(sanitized.contains(wif))
    }

    @Test
    fun `strips raw 32 byte secret from crash report`() {
        val secret = "ab".repeat(32)
        val sanitized = sanitizeCrashReport("privateKey=$secret")

        assertTrue(sanitized.contains("[REDACTED_32_BYTE_SECRET]"))
        assertFalse(sanitized.contains(secret))
    }
}
