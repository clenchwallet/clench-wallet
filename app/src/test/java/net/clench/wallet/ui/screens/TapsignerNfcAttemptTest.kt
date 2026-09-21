package net.clench.wallet.ui.screens

import net.clench.wallet.ui.viewmodel.HardwareWalletPsbtViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class TapsignerNfcAttemptTest {
    private fun attempt(id: Long, pin: CharArray) = TapsignerNfcAttempt.Sign(
        id, HardwareWalletPsbtViewModel.TapsignerSigningToken(id, "synthetic-psbt"), pin
    )

    @Test fun `screen cancellation closes active connection without corrupting worker credential`() {
        val pending = charArrayOf('t', 'e', 's', 't')
        val active = attempt(1, pending)
        val worker = checkNotNull(active.io.claimPin())
        var closed = 0
        active.io.attach { closed++ }
        active.clearSecret()
        assertEquals(1, closed)
        assertArrayEquals(charArrayOf('t', 'e', 's', 't'), worker)
        assertTrue(pending.all { it == '0' })
        assertThrows(CancellationException::class.java) { active.io.requireActive() }
        assertNull(active.io.claimPin())
        worker.fill('0')
    }

    @Test fun `old attempt cleanup never closes or revokes replacement attempt`() {
        val old = attempt(1, CharArray(0))
        val fresh = attempt(2, CharArray(0))
        var freshClosed = false
        fresh.io.attach { freshClosed = true }
        old.clearSecret(); old.clearSecret()
        fresh.io.requireActive()
        assertFalse(freshClosed)
        fresh.clearSecret()
        assertTrue(freshClosed)
    }

    @Test fun `connection arriving after cancellation is closed before it may sign`() {
        val active = attempt(1, CharArray(0))
        active.clearSecret()
        var closed = false
        assertThrows(CancellationException::class.java) { active.io.attach { closed = true } }
        assertTrue(closed)
    }
}
