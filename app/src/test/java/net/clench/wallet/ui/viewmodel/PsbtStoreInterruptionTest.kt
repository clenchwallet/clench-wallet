package net.clench.wallet.ui.viewmodel

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PsbtStoreInterruptionTest {
    @Test
    fun `cancel or interrupted signing clears all pending authorization state`() {
        val store = PsbtStore()
        store.store("wallet", validEnvelope(), "COLDCARD_Q")
        assertTrue(store.peekPsbtBase64() != null)

        store.clear()

        assertNull(store.peekPsbtBase64())
        assertNull(store.consume())
    }

    @Test
    fun `pending PSBT is single use and a concurrent signing session is rejected`() {
        val store = PsbtStore()
        store.store("wallet-a", validEnvelope(), "SEEDSIGNER")

        assertThrows(IllegalStateException::class.java) {
            store.store("wallet-b", validEnvelope(), "FOUNDATION_PASSPORT")
        }
        assertTrue(store.consume()?.first == "wallet-a")
        assertNull(store.consume())
    }

    @Test
    fun `simultaneous signing requests cannot replace one another`() {
        val store = PsbtStore()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val failures = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            listOf("wallet-a", "wallet-b").forEach { walletId ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    runCatching {
                        store.store(walletId, validEnvelope(), "COLDCARD_Q")
                    }.onSuccess {
                        successes.incrementAndGet()
                    }.onFailure {
                        failures.incrementAndGet()
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        assertTrue(successes.get() == 1)
        assertTrue(failures.get() == 1)
        assertTrue(store.consume() != null)
    }

    @Test
    fun `malformed signing payload never enters the pending store`() {
        val store = PsbtStore()

        assertThrows(IllegalArgumentException::class.java) {
            store.store("wallet", Base64.getEncoder().encodeToString("not-a-psbt".toByteArray()), "JADE")
        }
        assertNull(store.consume())
    }

    private fun validEnvelope(): String {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
        output.write(1)
        output.write(0)
        output.write(1)
        output.write(0)
        output.write(0)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }
}
