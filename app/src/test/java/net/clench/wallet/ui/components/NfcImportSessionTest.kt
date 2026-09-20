package net.clench.wallet.ui.components

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NfcImportSessionTest {
    @Test fun cancellationClosesIoWithoutCorruptingWorkerCredential() {
        val sessions = NfcImportSession()
        val pending = charArrayOf('a', 'b') // synthetic sentinel, not a card PIN
        val attempt = sessions.begin(pending)
        val worker = attempt.claimPin()!!
        var closed = 0
        attempt.attach { closed++ }
        sessions.cancel()
        assertEquals(1, closed)
        assertArrayEquals(charArrayOf('a', 'b'), worker)
        assertTrue(pending.all { it == '0' })
        assertFalse(sessions.isCurrent(attempt))
        assertThrows(CancellationException::class.java) { attempt.requireActive() }
        worker.fill('0')
    }

    @Test fun cancelledReaderCannotClaimCredentialOrPublishLateSuccess() {
        val sessions = NfcImportSession()
        val old = sessions.begin(charArrayOf('a'))
        sessions.cancel()
        assertNull(old.claimPin())
        assertFalse(sessions.isCurrent(old))
    }

    @Test fun connectionArrivingAfterCancellationIsClosedBeforeUse() {
        val sessions = NfcImportSession()
        val attempt = sessions.begin(null)
        sessions.cancel()
        var closed = false
        assertThrows(CancellationException::class.java) { attempt.attach { closed = true } }
        assertTrue(closed)
    }

    @Test fun oldCompletionCannotCancelNewReader() {
        val sessions = NfcImportSession()
        val old = sessions.begin(charArrayOf('a'))
        old.claimPin()
        val current = sessions.begin(charArrayOf('b'))
        var closed = false
        current.attach { closed = true }
        if (sessions.isCurrent(old)) sessions.cancel()
        assertTrue(sessions.isCurrent(current))
        assertFalse(closed)
        assertFalse(sessions.isCurrent(old))
        sessions.cancel()
        assertTrue(closed)
    }

    @Test fun repeatedTagCallbackCanClaimOnlyOnce() {
        val attempt = NfcImportSession().begin(null)
        assertNotNull(attempt.claimPin())
        assertNull(attempt.claimPin())
    }

    @Test fun cleanupFailureStillRevokesResultAndWipesPendingCredential() {
        val sessions = NfcImportSession()
        val pin = charArrayOf('a')
        val attempt = sessions.begin(pin)
        attempt.attach { throw java.io.IOException("synthetic close failure") }
        sessions.cancel()
        assertFalse(sessions.isCurrent(attempt))
        assertTrue(pin.all { it == '0' })
    }
}
