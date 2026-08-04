package net.clench.wallet.data.local

import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidKeystoreProtectionTest {

    @Test
    fun `modern Android reports each exact platform security level`() {
        assertEquals(
            AndroidKeystoreProtection.STRONGBOX,
            classifyAndroidKeystoreProtection(31, KeyProperties.SECURITY_LEVEL_STRONGBOX, true)
        )
        assertEquals(
            AndroidKeystoreProtection.TRUSTED_ENVIRONMENT,
            classifyAndroidKeystoreProtection(31, KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, true)
        )
        assertEquals(
            AndroidKeystoreProtection.SOFTWARE,
            classifyAndroidKeystoreProtection(31, KeyProperties.SECURITY_LEVEL_SOFTWARE, false)
        )
        assertEquals(
            AndroidKeystoreProtection.UNKNOWN_SECURE,
            classifyAndroidKeystoreProtection(31, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE, true)
        )
    }

    @Test
    fun `legacy Android never guesses TEE or StrongBox`() {
        assertEquals(
            AndroidKeystoreProtection.SECURE_HARDWARE_UNSPECIFIED,
            classifyAndroidKeystoreProtection(30, null, true)
        )
        assertEquals(
            AndroidKeystoreProtection.SOFTWARE,
            classifyAndroidKeystoreProtection(30, null, false)
        )
    }

    @Test
    fun `missing or unrecognized platform evidence remains unknown`() {
        assertEquals(
            AndroidKeystoreProtection.UNKNOWN,
            classifyAndroidKeystoreProtection(31, Int.MAX_VALUE, true)
        )
        assertEquals(
            AndroidKeystoreProtection.UNKNOWN,
            classifyAndroidKeystoreProtection(30, null, null)
        )
    }
}
