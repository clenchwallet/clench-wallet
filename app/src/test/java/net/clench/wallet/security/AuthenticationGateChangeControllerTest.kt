package net.clench.wallet.security

import org.junit.Assert.*
import org.junit.Test

class AuthenticationGateChangeControllerTest {
    private class Fixture {
        val values = mutableMapOf(AuthenticationGate.SEED to true, AuthenticationGate.SEND to true)
        var writes = 0
        val controller = AuthenticationGateChangeController(
            { values.getValue(it) }, { gate, value -> values[gate] = value; writes++ }
        )
        var success: () -> Unit = {}
        var abort: () -> Unit = {}
        fun request(gate: AuthenticationGate = AuthenticationGate.SEED, enabled: Boolean = false) {
            controller.request(gate, enabled) { ok, cancel -> success = ok; abort = cancel }
        }
    }

    @Test fun `weakening persists only after success and cannot replay`() {
        val f = Fixture()
        f.request()
        assertTrue(f.values.getValue(AuthenticationGate.SEED))
        assertEquals(0, f.writes)
        f.success()
        assertFalse(f.values.getValue(AuthenticationGate.SEED))
        assertTrue(f.values.getValue(AuthenticationGate.SEND))
        f.success()
        assertEquals(1, f.writes)
    }

    @Test fun `cancel failure unavailable auth and route disposal preserve protection`() {
        repeat(2) { scenario ->
            val f = Fixture()
            f.request()
            val lateSuccess = f.success
            if (scenario == 0) f.abort() else f.controller.cancel()
            lateSuccess()
            assertTrue(f.values.getValue(AuthenticationGate.SEED))
            assertEquals(0, f.writes)
        }
    }

    @Test fun `new request supersedes old prompt without letting its failure cancel the new one`() {
        val f = Fixture()
        f.request()
        val staleSuccess = f.success
        val staleAbort = f.abort
        f.request(AuthenticationGate.SEND)
        staleAbort(); staleSuccess()
        assertEquals(0, f.writes)
        f.success()
        assertTrue(f.values.getValue(AuthenticationGate.SEED))
        assertFalse(f.values.getValue(AuthenticationGate.SEND))
    }

    @Test fun `reenabling or keeping protection cancels pending weakening`() {
        val f = Fixture()
        f.request()
        val staleSuccess = f.success
        f.controller.request(AuthenticationGate.SEED, true) { _, _ -> fail("Must not prompt to keep protection") }
        staleSuccess()
        assertEquals(0, f.writes)
        f.values[AuthenticationGate.SEED] = false
        f.controller.request(AuthenticationGate.SEED, true) { _, _ -> fail("Must not prompt to enable") }
        assertTrue(f.values.getValue(AuthenticationGate.SEED))
        assertEquals(1, f.writes)
    }

    @Test fun `prompt initialization failure revokes callback`() {
        val f = Fixture()
        var lateSuccess: () -> Unit = {}
        assertThrows(IllegalStateException::class.java) {
            f.controller.request(AuthenticationGate.SEED, false) { ok, _ ->
                lateSuccess = ok
                error("Unavailable authenticator")
            }
        }
        lateSuccess()
        assertEquals(0, f.writes)
    }
}
