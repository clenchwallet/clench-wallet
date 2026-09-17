package net.clench.wallet.domain.model

import org.junit.Assert.*
import org.junit.Test

class Bip39PassphraseTest {
    @Test fun `only null and empty mean no passphrase`() {
        listOf(null, "").forEach {
            assertFalse(Bip39Passphrase.isPresent(it))
            assertNull(Bip39Passphrase.optional(it))
            assertEquals("", Bip39Passphrase.value(it))
        }
    }

    @Test fun `every nonempty value is passed unchanged for native bip39 normalization`() {
        listOf(" ", "   ", "\t", "ordinary", " surrounded ", "é", "e\u0301", "\u3000").forEach {
            assertTrue(Bip39Passphrase.isPresent(it))
            assertSame(it, Bip39Passphrase.optional(it))
            assertSame(it, Bip39Passphrase.value(it))
        }
    }
}
