package net.clench.wallet.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletStateQuarantinePolicyTest {
    private val walletId = "1234567890abcdef"
    private val recoveryId = "1234567890ab-1783721000000"

    @Test
    fun `valid recovery id only matches its own preserved files`() {
        WalletStateQuarantinePolicy.validateId(walletId, recoveryId)
        assertTrue(WalletStateQuarantinePolicy.matches(recoveryId, "$recoveryId-0-wallet.db"))
        assertFalse(WalletStateQuarantinePolicy.matches(recoveryId, "other-0-wallet.db"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recovery id for another wallet is rejected`() {
        WalletStateQuarantinePolicy.validateId("different-wallet", recoveryId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `path traversal is rejected`() {
        WalletStateQuarantinePolicy.validateId(walletId, "$recoveryId/../escape")
    }
}
