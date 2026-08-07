package net.clench.wallet.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SweepAccountKeyPolicyTest {
    @Test
    fun `bdk three account key gains an explicit external wildcard`() {
        assertEquals(
            "[fingerprint/84'/0'/0']xprvAccount/0/*",
            SweepAccountKeyPolicy.appendBranchAndWildcard(
                "[fingerprint/84'/0'/0']xprvAccount",
                branch = 0
            )
        )
    }

    @Test
    fun `legacy implicit wildcard is replaced by the requested change branch`() {
        assertEquals(
            "xprvAccount/1/*",
            SweepAccountKeyPolicy.appendBranchAndWildcard("xprvAccount/*", branch = 1)
        )
    }

    @Test
    fun `unexpected branch and wildcard forms fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            SweepAccountKeyPolicy.appendBranchAndWildcard("xprvAccount", branch = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SweepAccountKeyPolicy.appendBranchAndWildcard("xprvAccount/*h", branch = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SweepAccountKeyPolicy.appendBranchAndWildcard("", branch = 0)
        }
    }
}
