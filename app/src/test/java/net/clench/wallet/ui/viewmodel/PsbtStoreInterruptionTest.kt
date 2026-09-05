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
    fun `restart invalidates only its own picker generation`() {
        val store = PsbtStore()
        fun stage() = store.stageForPicker("wallet", validEnvelope(), validEnvelope(), "JADE", 9,
            PsbtPickerPurpose.HARDWARE_IMPORT)
        val token = stage()
        store.discardSessionStage("other-wallet", 9)
        store.discardSessionStage("wallet", 8)
        assertTrue(store.hasPendingForTest())
        store.discardSessionStage("wallet", 9)
        assertTrue(!store.hasPendingForTest())
        assertNull(store.consume("wallet", "JADE", token, PsbtPickerPurpose.HARDWARE_IMPORT))
        store.store("wallet", validEnvelope(), "JADE")
        store.discardSessionStage("wallet", 0)
        assertTrue(store.hasPendingForTest())
        store.clear()
    }
    @Test
    fun `cancel or interrupted signing clears all pending authorization state`() {
        val store = PsbtStore()
        store.store("wallet", validEnvelope(), "COLDCARD_Q")
        assertTrue(store.hasPendingForTest())

        store.clear()

        assertTrue(!store.hasPendingForTest())
        assertNull(store.consume("wallet", "COLDCARD_Q"))
    }

    @Test
    fun `pending PSBT is single use and a concurrent signing session is rejected`() {
        val store = PsbtStore()
        store.store("wallet-a", validEnvelope(), "SEEDSIGNER")

        assertThrows(IllegalStateException::class.java) {
            store.store("wallet-b", validEnvelope(), "FOUNDATION_PASSPORT")
        }
        assertTrue(store.consume("wallet-a", "SEEDSIGNER")?.walletId == "wallet-a")
        assertNull(store.consume("wallet-a", "SEEDSIGNER"))
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
        assertTrue(store.hasPendingForTest())
        store.clear()
    }

    @Test
    fun `malformed signing payload never enters the pending store`() {
        val store = PsbtStore()

        assertThrows(IllegalArgumentException::class.java) {
            store.store("wallet", Base64.getEncoder().encodeToString("not-a-psbt".toByteArray()), "JADE")
        }
        assertNull(store.consume("wallet", "JADE"))
    }

    @Test
    fun `picker stage preserves canonical unsigned policy and is exact-token one shot`() {
        var now = 10L
        val store = PsbtStore(
            monotonicNanos = { now },
            tokenBytes = { size -> ByteArray(size) { 0x2a } }
        )
        val original = validEnvelope(globalValue = 1)
        val partial = validEnvelope(globalValue = 2)
        val token = store.stageForPicker(
            walletId = "wallet",
            originalUnsignedPsbtBase64 = original,
            currentPsbtBase64 = partial,
            deviceType = "COLDCARD_Q",
            sourceSessionGeneration = 7,
            purpose = PsbtPickerPurpose.HARDWARE_IMPORT
        )

        assertTrue(token == "2a".repeat(16))
        val restored = store.consume(
            expectedWalletId = "wallet",
            expectedDeviceType = "COLDCARD_Q",
            pickerToken = token,
            pickerPurpose = PsbtPickerPurpose.HARDWARE_IMPORT
        )
        assertTrue(restored?.originalUnsignedPsbtBase64 == original)
        assertTrue(restored?.currentPsbtBase64 == partial)
        assertTrue(restored?.sourceSessionGeneration == 7L)
        assertNull(
            store.consume(
                "wallet",
                "COLDCARD_Q",
                token,
                PsbtPickerPurpose.HARDWARE_IMPORT
            )
        )
    }

    @Test
    fun `picker mismatch and timeout destroy staged PSBT`() {
        var now = 10L
        val store = PsbtStore(
            monotonicNanos = { now },
            tokenBytes = { size -> ByteArray(size) { 0x11 } }
        )
        val token = store.stageForPicker(
            "wallet",
            validEnvelope(1),
            validEnvelope(2),
            "COLDCARD_Q",
            3,
            PsbtPickerPurpose.HARDWARE_EXPORT
        )
        assertNull(
            store.consume(
                "other-wallet",
                "COLDCARD_Q",
                token,
                PsbtPickerPurpose.HARDWARE_EXPORT
            )
        )
        assertTrue(!store.hasPendingForTest())

        store.stageForPicker(
            "wallet",
            validEnvelope(1),
            validEnvelope(2),
            "COLDCARD_Q",
            4,
            PsbtPickerPurpose.HARDWARE_EXPORT
        )
        now += PsbtStore.PICKER_TTL_NANOS
        assertTrue(!store.hasPendingForTest())
    }

    private fun validEnvelope(globalValue: Int = 0): String {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
        output.write(1)
        output.write(globalValue)
        output.write(1)
        output.write(0)
        output.write(0)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }
}
