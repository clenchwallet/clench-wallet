package net.clench.wallet.viewmodel

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
