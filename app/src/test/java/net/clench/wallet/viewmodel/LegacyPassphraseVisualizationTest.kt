package net.clench.wallet.viewmodel

import net.clench.wallet.ui.viewmodel.CreateWalletViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyPassphraseVisualizationTest {
    @Test fun `legacy fallback images are not silently rewritten by identity fixes`() {
        // Fixed public fingerprint fixture; current LifeHash uses only the native fingerprint.
        // These are retained SHA256 compatibility vectors, not BIP39 wallet identities.
        val fingerprint = byteArrayOf(1, 2, 3, 4)
        fun legacy(value: String) = CreateWalletViewModel.computeFingerprint(fingerprint, value)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("541a50252c29353a384356ecaf806be60af75d37a92a62c6a5699c6eb331da4d", legacy("é"))
        assertEquals("91db647d3f879d9a254fd6b6373c887a86b85aa939a2cf15da70ffd6f41e6f50", legacy("e\u0301"))
    }
}
