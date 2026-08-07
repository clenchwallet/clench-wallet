package net.clench.wallet.verification

import org.junit.Assert.assertEquals
import org.junit.Test

class HostileFuzzExecutionContractTest {
    @Test
    fun executesAndRecordsTheRequestedCaseCount() {
        val requested = VerificationPropertyHarness.defaultCases
        var executed = 0

        VerificationPropertyHarness.forAll(
            seed = 0x43415345434F554EL,
            cases = requested
        ) { _, caseIndex ->
            assertEquals(caseIndex, executed)
            executed++
        }

        assertEquals(requested, executed)
        println("CLENCH_HOSTILE_FUZZ_EXECUTED=$executed;")
    }
}
