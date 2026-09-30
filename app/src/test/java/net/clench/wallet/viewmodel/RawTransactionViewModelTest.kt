package net.clench.wallet.viewmodel

import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.RawTransactionPreview
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.ui.viewmodel.RawTransactionViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RawTransactionViewModelTest {

    @Test
    fun `weak recognizable signature fails before network configuration or broadcast`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<BitcoinRepository>(relaxed = true)
            val settings = mockk<SettingsManager>(relaxed = true)
            every { settings.isOfflineMode() } returns false
            every { settings.isTestnet() } returns false
            val viewModel = RawTransactionViewModel(repository, settings)
            val rawHex = rawTransactionWithWeakEcdsa().toHex()
            val stateField = RawTransactionViewModel::class.java.getDeclaredField("_uiState").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(viewModel) as MutableStateFlow<RawTransactionViewModel.UiState>
            state.value = RawTransactionViewModel.UiState(
                input = rawHex,
                preview = RawTransactionPreview(
                    normalizedHex = rawHex,
                    txid = "test-txid",
                    vsize = 1,
                    totalSize = 1,
                    isRbf = false,
                    outputs = emptyList()
                )
            )

            assertTrue(viewModel.uiState.value.preview != null)

            viewModel.broadcast()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.error?.startsWith("Security check failed:") == true)
            assertTrue(viewModel.uiState.value.error?.contains("0x82") == true)
            assertFalse(viewModel.uiState.value.isBroadcasting)
            coVerify(exactly = 0) { repository.broadcastTransaction(any(), any()) }
            verify(exactly = 0) { settings.loadElectrumConfig() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `pending broadcast is reserved before dispatch and cannot be submitted twice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = mockk<BitcoinRepository>(relaxed = true)
            val settings = mockk<SettingsManager>(relaxed = true)
            every { settings.isOfflineMode() } returns false
            val completion = CompletableDeferred<String>()
            coEvery { repository.broadcastTransaction(any(), any(), any()) } coAnswers { completion.await() }
            val vm = RawTransactionViewModel(repository, settings)
            installPreview(vm)
            vm.broadcast()
            assertTrue(vm.uiState.value.isBroadcasting)
            vm.broadcast()
            runCurrent()
            completion.complete("accepted")
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.broadcastTransaction(any(), any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `late broadcast completion does not describe replacement input`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = mockk<BitcoinRepository>(relaxed = true)
            val settings = mockk<SettingsManager>(relaxed = true)
            every { settings.isOfflineMode() } returns false
            val completion = CompletableDeferred<String>()
            coEvery { repository.broadcastTransaction(any(), any(), any()) } coAnswers { completion.await() }
            val vm = RawTransactionViewModel(repository, settings)
            installPreview(vm)
            vm.broadcast(); runCurrent()
            vm.setInput("replacement input")
            completion.complete("old-accepted")
            advanceUntilIdle()
            assertEquals("replacement input", vm.uiState.value.input)
            assertNull(vm.uiState.value.broadcastTxid)
            assertFalse(vm.uiState.value.isBroadcasting)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `edit before dispatch revokes queued broadcast and releases reservation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = mockk<BitcoinRepository>(relaxed = true)
            val settings = mockk<SettingsManager>(relaxed = true)
            val vm = RawTransactionViewModel(repository, settings)
            installPreview(vm); vm.broadcast(); vm.setInput("replacement")
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.broadcastTransaction(any(), any(), any()) }
            verify(exactly = 0) { settings.loadElectrumConfig() }
            assertFalse(vm.uiState.value.isBroadcasting)
            installPreview(vm); vm.broadcast(); advanceUntilIdle()
            coVerify(exactly = 1) { repository.broadcastTransaction(any(), any(), any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `late failure preserves current error and pending request prevents overlap`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = mockk<BitcoinRepository>(relaxed = true)
            val settings = mockk<SettingsManager>(relaxed = true)
            val completion = CompletableDeferred<String>()
            coEvery { repository.broadcastTransaction(any(), any(), any()) } coAnswers { completion.await() }
            val vm = RawTransactionViewModel(repository, settings)
            installPreview(vm); vm.broadcast(); runCurrent()
            vm.setError("new import failed"); vm.broadcast(); runCurrent()
            completion.completeExceptionally(IllegalStateException("old request failed"))
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.broadcastTransaction(any(), any(), any()) }
            assertEquals("new import failed", vm.uiState.value.error)
            assertFalse(vm.uiState.value.isBroadcasting)
        } finally { Dispatchers.resetMain() }
    }

    private fun installPreview(vm: RawTransactionViewModel) {
        // Synthetic no-funds fixture: parser/native acceptance is covered separately.
        val raw = rawTransactionWithWeakEcdsa().also { it[5 + 32 + 4 + 1 + 1 + 70] = 1 }.toHex()
        val field = RawTransactionViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(vm) as MutableStateFlow<RawTransactionViewModel.UiState>
        state.value = RawTransactionViewModel.UiState(input = raw,
            preview = RawTransactionPreview(raw, "fixture", 1, 1, false, emptyList()))
    }

    private fun rawTransactionWithWeakEcdsa(): ByteArray {
        val signature = byteArrayOf(0x30, 0x44, 0x02, 0x20) +
            ByteArray(32) { 0x11 } +
            byteArrayOf(0x02, 0x20) +
            ByteArray(32) { 0x22 } +
            byteArrayOf(0x82.toByte())
        val scriptSig = byteArrayOf(signature.size.toByte()) + signature
        return byteArrayOf(
            0x02, 0x00, 0x00, 0x00, // version
            0x01 // input count
        ) + ByteArray(32) + byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // vout
            scriptSig.size.toByte()
        ) + scriptSig + byteArrayOf(
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), // sequence
            0x01, // output count
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // value
            0x00, // empty scriptPubKey
            0x00, 0x00, 0x00, 0x00 // locktime
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
