package net.clench.wallet.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoAuthenticationPolicyTest {

    @Test
    fun `Android 11 and newer prefer combined prompt-bound authentication`() {
        assertEquals(
            CryptoAuthenticationPolicy.MODERN_STRONG_OR_DEVICE_CREDENTIAL,
            selectCryptoAuthenticationPolicy(
                apiLevel = 30,
                strongBiometricAvailable = false,
                modernCombinedAvailable = true
            )
        )
    }

    @Test
    fun `modern Android falls back to prompt-bound strong biometric only`() {
        assertEquals(
            CryptoAuthenticationPolicy.MODERN_STRONG_BIOMETRIC,
            selectCryptoAuthenticationPolicy(
                apiLevel = 31,
                strongBiometricAvailable = true,
                modernCombinedAvailable = false
            )
        )
    }

    @Test
    fun `Android 8 through 10 never accepts device credential without CryptoObject`() {
        assertEquals(
            CryptoAuthenticationPolicy.UNAVAILABLE,
            selectCryptoAuthenticationPolicy(
                apiLevel = 29,
                strongBiometricAvailable = false,
                modernCombinedAvailable = true
            )
        )
        assertEquals(
            CryptoAuthenticationPolicy.LEGACY_STRONG_BIOMETRIC,
            selectCryptoAuthenticationPolicy(
                apiLevel = 26,
                strongBiometricAvailable = true,
                modernCombinedAvailable = false
            )
        )
    }

    @Test
    fun `wrapped invalidation cause can be detected without matching unrelated failures`() {
        val invalidated = TestInvalidationException()
        val wrapped = IllegalStateException("provider wrapper", IllegalArgumentException(invalidated))

        assertTrue(wrapped.hasCauseMatching { it is TestInvalidationException })
        assertFalse(wrapped.hasCauseMatching { it is UnsupportedOperationException })
    }

    private class TestInvalidationException : Exception()
}
