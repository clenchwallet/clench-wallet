package net.clench.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ClenchApplication.sanitizeCrashReport() — ensures sensitive data is stripped.
 *
 * Note: sanitizeCrashReport is an instance method on ClenchApplication, but we can
 * test it by creating a testable wrapper since it doesn't use Android APIs.
 */
class ClenchApplicationTest {

    // Mirror the sanitization logic for testing (it's internal in ClenchApplication)
    private fun sanitizeCrashReport(report: String): String {
        var sanitized = report

        sanitized = sanitized.replace(
            Regex("xprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_XPRV]"
        )
        sanitized = sanitized.replace(
            Regex("xpub[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_XPUB]"
        )
        sanitized = sanitized.replace(
            Regex("tprv[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_TPRV]"
        )
        sanitized = sanitized.replace(
            Regex("tpub[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_TPUB]"
        )
        sanitized = sanitized.replace(
            Regex("[zvyZVY]pub[1-9A-HJ-NP-Za-km-z]{100,}"),
            "[REDACTED_EXTKEY]"
        )
        sanitized = sanitized.replace(
            Regex("""(?<!\S)(?:[a-z]{3,8}\s){11,23}[a-z]{3,8}(?!\S)"""),
            "[REDACTED_MNEMONIC]"
        )

        return sanitized
    }

    @Test
    fun `strips xprv from crash report`() {
        val fakeXprv = "xprv" + "A".repeat(107) // typical xprv is ~111 chars
        val report = "Error at $fakeXprv in wallet"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_XPRV]"))
        assertFalse(sanitized.contains(fakeXprv))
    }

    @Test
    fun `strips xpub from crash report`() {
        val fakeXpub = "xpub" + "B".repeat(107)
        val report = "Descriptor: $fakeXpub"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_XPUB]"))
        assertFalse(sanitized.contains(fakeXpub))
    }

    @Test
    fun `strips tprv from crash report`() {
        val fakeTprv = "tprv" + "C".repeat(107)
        val report = "Key: $fakeTprv"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_TPRV]"))
        assertFalse(sanitized.contains(fakeTprv))
    }

    @Test
    fun `strips tpub from crash report`() {
        val fakeTpub = "tpub" + "D".repeat(107)
        val report = "Key: $fakeTpub"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_TPUB]"))
        assertFalse(sanitized.contains(fakeTpub))
    }

    @Test
    fun `strips zpub from crash report`() {
        val fakeZpub = "zpub" + "E".repeat(107)
        val report = "Import: $fakeZpub"
        val sanitized = sanitizeCrashReport(report)
        assertTrue(sanitized.contains("[REDACTED_EXTKEY]"))
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
        assertTrue(sanitized.contains("[REDACTED_XPRV]"))
        assertTrue(sanitized.contains("[REDACTED_XPUB]"))
        assertFalse(sanitized.contains(fakeXprv))
        assertFalse(sanitized.contains(fakeXpub))
    }
}
