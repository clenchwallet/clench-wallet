package net.clench.wallet.data.local

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyPassphraseCleanupTest {

    @Test
    fun `removes all legacy keys in one synchronous commit and verifies absence`() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.all } returnsMany listOf(
            mapOf(
                "passphrase_wallet-a" to "secret-a",
                "mnemonic_wallet-a" to "still-encrypted",
                "passphrase_wallet-b" to "secret-b"
            ),
            mapOf("mnemonic_wallet-a" to "still-encrypted")
        )
        every { prefs.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns true

        LegacyPassphraseCleanup.deleteAndVerify(prefs)

        verify(exactly = 1) { prefs.edit() }
        verify(exactly = 1) { editor.remove("passphrase_wallet-a") }
        verify(exactly = 1) { editor.remove("passphrase_wallet-b") }
        verify(exactly = 0) { editor.remove("mnemonic_wallet-a") }
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun `fails closed when synchronous commit reports failure`() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.all } returns mapOf("passphrase_wallet-a" to "secret-a")
        every { prefs.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns false

        val error = assertThrows(SecureStorageCleanupException::class.java) {
            LegacyPassphraseCleanup.deleteAndVerify(prefs)
        }

        assertEquals(
            "Secure wallet storage cleanup could not be verified. Wallet access was stopped.",
            error.message
        )
        assertEquals(null, error.cause)
    }

    @Test
    fun `fails closed when a legacy key remains after successful commit`() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.all } returns mapOf("passphrase_wallet-a" to "secret-a")
        every { prefs.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns true

        assertThrows(SecureStorageCleanupException::class.java) {
            LegacyPassphraseCleanup.deleteAndVerify(prefs)
        }
    }

    @Test
    fun `does not write when no legacy passphrases exist`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.all } returns mapOf("mnemonic_wallet-a" to "encrypted")

        LegacyPassphraseCleanup.deleteAndVerify(prefs)

        verify(exactly = 0) { prefs.edit() }
    }
}
