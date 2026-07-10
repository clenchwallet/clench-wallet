package net.clench.wallet.viewmodel

import io.mockk.every
import io.mockk.mockk
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.local.dao.AddressBookDao
import net.clench.wallet.data.network.TorAwareHttpClient
import net.clench.wallet.domain.model.FeeEstimates
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.domain.repository.BuiltTransactionReview
import net.clench.wallet.domain.repository.TransactionReviewOutput
import net.clench.wallet.ui.viewmodel.PsbtStore
import net.clench.wallet.ui.viewmodel.SendViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for fee calculation logic and amount validation.
 * Tests pure data classes and validation rules without Android framework.
 */
class SendViewModelTest {

    private val recipient = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"

    private fun viewModel(
        repository: BitcoinRepository,
        settings: SettingsManager = mockk(relaxed = true)
    ): SendViewModel {
        every { settings.isTestnet() } returns false
        every { settings.isOfflineMode() } returns true
        every { settings.isBtcPriceEnabled() } returns false
        every { settings.isBiometricForSendEnabled() } returns false
        return SendViewModel(
            repository,
            settings,
            mockk<AddressBookDao>(relaxed = true),
            mockk<PsbtStore>(relaxed = true),
            mockk<TorAwareHttpClient>(relaxed = true)
        )
    }

    @Test
    fun `editing transaction fields invalidates signed transaction`() {
        val viewModel = viewModel(mockk(relaxed = true))
        val review = BuiltTransactionReview(
            txid = "txid",
            feeSat = 100,
            vsize = 100,
            feeRateSatPerVbyte = 1.0,
            inputs = listOf("input:0"),
            outputs = listOf(TransactionReviewOutput(0, 10_000, recipient, false))
        )
        val stateField = SendViewModel::class.java.getDeclaredField("_uiState").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(viewModel) as MutableStateFlow<SendViewModel.UiState>
        stateFlow.value = SendViewModel.UiState(
            amountSat = "10000",
            txHex = "00",
            transactionReview = review,
            proposalFingerprint = "fingerprint"
        )

        viewModel.setAmount("12000")

        assertNull(viewModel.uiState.value.txHex)
        assertNull(viewModel.uiState.value.transactionReview)
        assertNull(viewModel.uiState.value.proposalFingerprint)
    }

    @Test
    fun `exact fee review requires acknowledgement above five percent`() {
        val review = BuiltTransactionReview(
            txid = "txid",
            feeSat = 600,
            vsize = 100,
            feeRateSatPerVbyte = 6.0,
            inputs = listOf("input:0"),
            outputs = listOf(TransactionReviewOutput(0, 10_000, recipient, false))
        )

        assertTrue(SendViewModel.requiresHighFeeConfirmation(review))
        assertNull(SendViewModel.feeSafetyError(review))
    }

    @Test
    fun `built transaction review excludes wallet change from external amount`() {
        val review = BuiltTransactionReview(
            txid = "txid",
            feeSat = 200,
            vsize = 100,
            feeRateSatPerVbyte = 2.0,
            inputs = emptyList(),
            outputs = listOf(
                TransactionReviewOutput(0, 25_000, recipient, false),
                TransactionReviewOutput(1, 74_800, "bc1qchange", true)
            )
        )

        assertEquals(25_000L, review.externalAmountSat)
    }

    @Test
    fun `wallet-only cancellation replacement still receives relative fee warning`() {
        val review = BuiltTransactionReview(
            txid = "txid",
            feeSat = 600,
            vsize = 100,
            feeRateSatPerVbyte = 6.0,
            inputs = listOf("input:0"),
            outputs = listOf(TransactionReviewOutput(0, 10_000, "bc1qcancel", true))
        )

        assertTrue(SendViewModel.requiresHighFeeConfirmation(review))
    }

    @Test
    fun `FeeEstimates holds correct values`() {
        val estimates = FeeEstimates(
            priority = 15f,
            standard = 8f,
            economy = 3f,
            timestamp = 1000L
        )
        assertEquals(15f, estimates.priority, 0.01f)
        assertEquals(8f, estimates.standard, 0.01f)
        assertEquals(3f, estimates.economy, 0.01f)
        assertEquals(1000L, estimates.timestamp)
    }

    @Test
    fun `FeeEstimates minimum values are reasonable`() {
        val estimates = FeeEstimates(
            priority = 1f,
            standard = 1f,
            economy = 1f,
            timestamp = System.currentTimeMillis()
        )
        assertTrue(estimates.priority >= 1f)
        assertTrue(estimates.standard >= 1f)
        assertTrue(estimates.economy >= 1f)
    }

    @Test
    fun `fee rate validation - valid integer`() {
        val rate = "5"
        val parsed = rate.toFloatOrNull()
        assertTrue(parsed != null && parsed >= 1f)
    }

    @Test
    fun `fee rate validation - below minimum`() {
        val rate = "0"
        val parsed = rate.toFloatOrNull()
        assertTrue(parsed == null || parsed < 1f)
    }

    @Test
    fun `fee rate validation - empty string`() {
        val rate = ""
        val parsed = rate.toFloatOrNull()
        assertTrue(parsed == null)
    }

    @Test
    fun `fee rate validation - non-numeric`() {
        val rate = "abc"
        val parsed = rate.toFloatOrNull()
        assertTrue(parsed == null)
    }

    @Test
    fun `amount validation - valid amount`() {
        val amount = "50000"
        val parsed = amount.toLongOrNull()
        assertTrue(parsed != null && parsed > 0)
    }

    @Test
    fun `amount validation - zero is invalid`() {
        val amount = "0"
        val parsed = amount.toLongOrNull()
        assertTrue(parsed == null || parsed <= 0)
    }

    @Test
    fun `amount validation - negative is invalid`() {
        val amount = "-100"
        val parsed = amount.toLongOrNull()
        assertTrue(parsed == null || parsed <= 0)
    }

    @Test
    fun `amount validation - empty string`() {
        val amount = ""
        val parsed = amount.toLongOrNull()
        assertTrue(parsed == null)
    }

    @Test
    fun `address validation - blank is invalid`() {
        val address = "   "
        assertTrue(address.isBlank())
    }

    @Test
    fun `address validation - non-blank is valid`() {
        val address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        assertTrue(address.isNotBlank())
    }

    @Test
    fun `fee percentage calculation - high fee warning`() {
        val amountSat = 1000L
        val feeRate = 50f
        val estimatedVbytes = 140
        val estimatedFeeSat = (feeRate * estimatedVbytes).toLong()
        val feePercent = (estimatedFeeSat.toDouble() / amountSat.toDouble()) * 100
        assertTrue("Fee should be >5% of amount for warning", feePercent > 5)
    }

    @Test
    fun `fee percentage calculation - normal fee no warning`() {
        val amountSat = 1_000_000L
        val feeRate = 5f
        val estimatedVbytes = 140
        val estimatedFeeSat = (feeRate * estimatedVbytes).toLong()
        val feePercent = (estimatedFeeSat.toDouble() / amountSat.toDouble()) * 100
        assertTrue("Fee should be <5% of amount", feePercent < 5)
    }
}
