package net.clench.wallet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultisigPolicyTest {

    @Test
    fun `generated account wildcard is removed before descriptor branches are appended`() {
        assertEquals("tpubAccount", MultisigAccountKeyPolicy.normalizeGeneratedAccountKey("tpubAccount"))
        assertEquals("tprvAccount", MultisigAccountKeyPolicy.normalizeGeneratedAccountKey(" tprvAccount "))
        assertEquals("tpubAccount", MultisigAccountKeyPolicy.normalizeGeneratedAccountKey("tpubAccount/*"))
        assertEquals("tpubAccount", MultisigAccountKeyPolicy.normalizeGeneratedAccountKey("tpubAccount/**"))
        assertEquals("tpubAccount", MultisigAccountKeyPolicy.normalizeGeneratedAccountKey("tpubAccount/0/*"))
        assertEquals("tpubAccount", MultisigAccountKeyPolicy.normalizeGeneratedAccountKey("tpubAccount/1/*"))
        assertEquals("tprvAccount", MultisigAccountKeyPolicy.normalizeGeneratedAccountKey(" tprvAccount/* "))
        assertEquals(
            "tpubAccount/0/*",
            MultisigAccountKeyPolicy.normalizeGeneratedAccountKey("tpubAccount") + "/0/*"
        )
    }

    @Test
    fun `two of two p2wsh estimate includes final witness`() {
        val witnessScript = byteArrayOf(0x52) +
            byteArrayOf(0x21) + ByteArray(33) +
            byteArrayOf(0x21) + ByteArray(33) +
            byteArrayOf(0x52, 0xae.toByte())

        val estimated = MultisigPsbtVsizeEstimator.estimateFinalVsize(
            unsignedWeight = 500,
            inputs = listOf(MultisigPsbtInputSize(witnessScript, emptyList()))
        )

        assertEquals(181L, estimated)
    }

    @Test
    fun `known signature lengths improve the final estimate`() {
        val witnessScript = byteArrayOf(0x52) +
            byteArrayOf(0x21) + ByteArray(33) +
            byteArrayOf(0x21) + ByteArray(33) +
            byteArrayOf(0x52, 0xae.toByte())

        val estimated = MultisigPsbtVsizeEstimator.estimateFinalVsize(
            unsignedWeight = 500,
            inputs = listOf(MultisigPsbtInputSize(witnessScript, listOf(70, 71)))
        )

        assertEquals(180L, estimated)
    }

    @Test
    fun `non multisig witness script is not guessed`() {
        assertNull(
            MultisigPsbtVsizeEstimator.estimateFinalVsize(
                unsignedWeight = 500,
                inputs = listOf(MultisigPsbtInputSize(byteArrayOf(0x51), emptyList()))
            )
        )
    }
}
