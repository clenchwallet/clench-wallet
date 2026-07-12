package net.clench.wallet.ui.components

import net.clench.wallet.domain.repository.BuiltTransactionReview
import net.clench.wallet.domain.repository.TransactionReviewOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionReviewPresentationTest {
    @Test
    fun `fee percentage uses external amount rather than change`() {
        val review = review(
            feeSat = 500,
            outputs = listOf(
                TransactionReviewOutput(0, 10_000, "bc1recipient", false),
                TransactionReviewOutput(1, 89_500, "bc1change", true)
            )
        )

        assertEquals(5.0, TransactionReviewPresentation.feePercentage(review)!!, 0.0001)
        assertTrue(TransactionReviewPresentation.feeSummary(review).contains("5.00% of sent amount"))
    }

    @Test
    fun `fee percentage falls back to all outputs for wallet-only transaction`() {
        val review = review(
            feeSat = 250,
            outputs = listOf(TransactionReviewOutput(0, 9_750, "bc1wallet", true))
        )

        assertEquals(250.0 / 9_750.0 * 100.0, TransactionReviewPresentation.feePercentage(review)!!, 0.0001)
    }

    @Test
    fun `fee percentage is absent for zero value transaction`() {
        assertNull(TransactionReviewPresentation.feePercentage(review(1, emptyList())))
    }

    @Test
    fun `long identifiers are grouped without changing data`() {
        val source = "0123456789abcdef"
        val grouped = TransactionReviewPresentation.grouped(source, 4)
        assertEquals("0123 4567 89ab cdef", grouped)
        assertEquals(source, grouped.replace(" ", ""))
    }

    @Test
    fun `estimated final vsize is identified in fee summary`() {
        val review = review(181, emptyList()).copy(
            vsize = 181,
            feeRateSatPerVbyte = 1.0,
            vsizeIsEstimate = true
        )

        assertTrue(TransactionReviewPresentation.feeSummary(review).contains("estimated final: 1.00 sat/vB, 181 vB"))
    }

    private fun review(feeSat: Long, outputs: List<TransactionReviewOutput>) = BuiltTransactionReview(
        txid = "00".repeat(32),
        feeSat = feeSat,
        vsize = 100,
        feeRateSatPerVbyte = feeSat / 100.0,
        inputs = listOf("input:0"),
        outputs = outputs
    )
}
