package net.clench.wallet.verification

import net.clench.wallet.domain.repository.BuiltTransactionReview
import net.clench.wallet.domain.repository.TransactionReviewOutput
import net.clench.wallet.ui.viewmodel.SendViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeeAttackPropertyTest {
    @Test
    fun `fee policy is monotonic across generated absolute and relative attacks`() {
        VerificationPropertyHarness.forAll(seed = 0xFEEA77ACL) { random, _ ->
            val amount = (random.nextLong().ushr(1) % 2_000_000_000L) + 1L
            val fee = random.nextLong().ushr(1) % 2_000_000L
            val review = review(amount, fee)
            val percent = fee.toDouble() / amount.toDouble() * 100.0
            val mustReject = fee > SendViewModel.MAX_ABSOLUTE_FEE_SAT ||
                percent > SendViewModel.MAX_RELATIVE_FEE_PERCENT

            if (mustReject) {
                assertNotNull(SendViewModel.feeSafetyError(review))
            } else {
                assertNull(SendViewModel.feeSafetyError(review))
            }
            assertEquals(
                percent > SendViewModel.HIGH_FEE_WARNING_PERCENT,
                SendViewModel.requiresHighFeeConfirmation(review)
            )
        }
    }

    @Test
    fun `negative non finite and overflow-shaped review metadata fails closed`() {
        assertNotNull(SendViewModel.feeSafetyError(review(1_000, -1)))
        assertNotNull(
            SendViewModel.feeSafetyError(
                review(1_000, 100).copy(feeRateSatPerVbyte = Double.NaN)
            )
        )
        val overflow = BuiltTransactionReview(
            txid = "hostile",
            feeSat = 1,
            vsize = 1,
            feeRateSatPerVbyte = 1.0,
            inputs = emptyList(),
            outputs = listOf(
                TransactionReviewOutput(0, Long.MAX_VALUE, "tb1qone", false),
                TransactionReviewOutput(1, Long.MAX_VALUE, "tb1qtwo", false)
            )
        )

        assertEquals(Long.MAX_VALUE, overflow.externalAmountSat)
        assertFalse(overflow.externalAmountSat < 0)
        assertNotNull(SendViewModel.feeSafetyError(overflow))
    }

    @Test
    fun `fee thresholds have explicit boundary behavior`() {
        val atWarning = review(2_000, 100)
        val aboveWarning = review(1_999, 100)
        val atReject = review(200, 100)
        val aboveReject = review(199, 100)

        assertFalse(SendViewModel.requiresHighFeeConfirmation(atWarning))
        assertTrue(SendViewModel.requiresHighFeeConfirmation(aboveWarning))
        assertNull(SendViewModel.feeSafetyError(atReject))
        assertNotNull(SendViewModel.feeSafetyError(aboveReject))
    }

    private fun review(amount: Long, fee: Long): BuiltTransactionReview =
        BuiltTransactionReview(
            txid = "fixture",
            feeSat = fee,
            vsize = 100,
            feeRateSatPerVbyte = fee.toDouble() / 100.0,
            inputs = listOf("00".repeat(32) + ":0"),
            outputs = listOf(TransactionReviewOutput(0, amount, "tb1qfixture", false))
        )
}
