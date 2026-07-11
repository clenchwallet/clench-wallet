package net.clench.wallet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReplacementTransactionPolicyTest {
    @Test
    fun `eviction timestamp is newer than the original last seen time`() {
        assertEquals(101uL, ReplacementTransactionPolicy.evictionTimestamp(100uL, 90uL))
        assertEquals(200uL, ReplacementTransactionPolicy.evictionTimestamp(100uL, 200uL))
        assertEquals(201uL, ReplacementTransactionPolicy.evictionTimestamp(null, 200uL))
    }

    @Test
    fun `temporary eviction restores the original after a successful build`() {
        val calls = mutableListOf<String>()

        val result = ReplacementTransactionPolicy.withTemporaryEviction(
            evict = { calls += "evict" },
            restore = { calls += "restore" },
            build = {
                calls += "build"
                "replacement"
            }
        )

        assertEquals("replacement", result)
        assertEquals(listOf("evict", "build", "restore"), calls)
    }

    @Test
    fun `temporary eviction restores the original when replacement construction fails`() {
        val calls = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            ReplacementTransactionPolicy.withTemporaryEviction(
                evict = { calls += "evict" },
                restore = { calls += "restore" },
                build = {
                    calls += "build"
                    error("boom")
                }
            )
        }

        assertEquals(listOf("evict", "build", "restore"), calls)
    }
}
