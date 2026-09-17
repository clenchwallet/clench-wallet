package net.clench.wallet.security

import org.junit.Assert.*
import org.junit.Test

class RelockTimeoutChangeControllerTest {
    private class Fixture {
        var key = "30s"
        var mode = "pin"
        var foreground = true
        var writes = 0
        var success: () -> Unit = {}
        var abort: () -> Unit = {}
        val controller = RelockTimeoutChangeController({ key }, { mode }) { key = it; writes++ }
        fun request(value: String = "never") = controller.request(value, { foreground }) { ok, no ->
            success = ok; abort = no
        }
    }

    @Test fun `every timeout relaxation requires a single use success`() {
        for (key in listOf("1min", "5min", "never")) {
            val f = Fixture()
            f.request(key)
            assertEquals("30s", f.key)
            f.success(); f.success()
            assertEquals(key, f.key)
            assertEquals(1, f.writes)
        }
    }

    @Test fun `failed cancelled superseded or background authentication never persists`() {
        for (invalidate in listOf<(Fixture) -> Unit>(
            { it.abort() }, { it.controller.cancel() }, { it.foreground = false },
            { it.mode = "biometric" }, { it.key = "1min" }, { it.request("30s") }
        )) {
            val f = Fixture()
            f.request()
            val stale = f.success
            invalidate(f)
            stale()
            assertEquals(0, f.writes)
        }
    }

    @Test fun `old callbacks cannot approve or cancel a new request`() {
        val f = Fixture()
        f.request()
        val staleSuccess = f.success
        val staleAbort = f.abort
        f.request("1min")
        staleSuccess(); staleAbort()
        assertEquals(0, f.writes)
        f.success()
        assertEquals("1min", f.key)
    }

    @Test fun `stronger and unchanged policies do not require authentication`() {
        val f = Fixture()
        f.key = "never"
        f.controller.request("30s", { true }) { _, _ -> fail("Must not authenticate a stronger policy") }
        assertEquals("30s", f.key)
        assertEquals(1, f.writes)
        f.controller.request("30s", { true }) { _, _ -> fail("Unchanged") }
        assertEquals(1, f.writes)
    }

    @Test fun `unavailable session and unsupported keys fail closed`() {
        val f = Fixture()
        f.foreground = false
        f.request()
        f.success()
        assertEquals(0, f.writes)
        assertThrows(IllegalArgumentException::class.java) { f.request("invalid") }
        assertEquals(0, f.writes)
    }

    @Test fun `throwing authenticator revokes a captured success`() {
        val f = Fixture()
        var stale: () -> Unit = {}
        assertThrows(IllegalStateException::class.java) {
            f.controller.request("never", { true }) { ok, _ -> stale = ok; error("Unavailable") }
        }
        stale()
        assertEquals(0, f.writes)
    }
}
