package net.clench.wallet.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenInputPolicyTest {
    private val allowed = "11".repeat(32) + ":0"
    private val frozen = "22".repeat(32) + ":1"

    @Test fun `legacy uppercase and padded metadata still freezes the native outpoint`() {
        val legacy = "AB".repeat(32) + ":0001"
        val actual = "ab".repeat(32) + ":1"
        val frozen = setOf(FrozenInputPolicy.canonicalizeStoredOutpoint(legacy))
        assertTrue(runCatching { FrozenInputPolicy.requireAllowed(listOf(actual), frozen) }.isFailure)
        for (invalid in listOf("bad:1", "ab".repeat(32) + ":4294967296", "ab".repeat(32) + ":+1")) {
            assertTrue(runCatching { FrozenInputPolicy.canonicalizeStoredOutpoint(invalid) }.isFailure)
        }
    }

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
