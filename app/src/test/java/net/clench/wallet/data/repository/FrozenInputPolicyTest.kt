package net.clench.wallet.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenInputPolicyTest {
    private val allowed = "11".repeat(32) + ":0"
    private val frozen = "22".repeat(32) + ":1"

    @Test fun `allowed inputs and empty freeze set remain usable`() {
        FrozenInputPolicy.requireAllowed(listOf(allowed), setOf(frozen))
        FrozenInputPolicy.requireAllowed(listOf(allowed, frozen), emptySet())
    }

    @Test fun `explicit selection is not permission to spend frozen coins`() {
        assertTrue(runCatching { FrozenInputPolicy.requireAllowed(listOf(allowed, frozen), setOf(frozen)) }.isFailure)
    }

    @Test fun `freeze after draft rejects unchanged signed inputs and unfreeze recovers`() {
        val signedInputs = listOf(allowed)
        FrozenInputPolicy.requireAllowed(signedInputs, emptySet())
        assertTrue(runCatching { FrozenInputPolicy.requireAllowed(signedInputs, setOf(allowed)) }.isFailure)
        FrozenInputPolicy.requireAllowed(signedInputs, emptySet())
    }

    @Test fun `invalid or overflowing outpoints cannot silently disappear from coin control`() {
        for (value in listOf("", "$allowed:0", "ff:0", "11".repeat(32) + ":-1", "11".repeat(32) + ":4294967296")) {
            assertTrue(runCatching { FrozenInputPolicy.requireAllowed(listOf(value), emptySet()) }.isFailure)
        }
    }
}
