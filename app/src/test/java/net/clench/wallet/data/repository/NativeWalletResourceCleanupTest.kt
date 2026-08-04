package net.clench.wallet.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeWalletResourceCleanupTest {

    @Test
    fun `attempts both resources for every entry after failures`() {
        val calls = mutableListOf<String>()
        val states = listOf("one", "two", "three").map {
            NativeWalletResourceCleanup.CloseState(it)
        }

        val failures = NativeWalletResourceCleanup.closeAll(
            entries = states,
            closeWallet = { entry ->
                calls += "wallet-$entry"
                if (entry == "one") error("descriptor-like native detail")
            },
            closePersister = { entry ->
                calls += "persister-$entry"
                if (entry == "two") error("private database path")
            }
        )

        assertEquals(2, failures)
        assertEquals(
            listOf(
                "wallet-one", "persister-one",
                "wallet-two", "persister-two",
                "wallet-three", "persister-three"
            ),
            calls
        )

        // No attempted native close is ever invoked again. A close can free state then throw.
        NativeWalletResourceCleanup.closeAll(
            entries = states,
            closeWallet = { calls += "retry-wallet-$it" },
            closePersister = { calls += "retry-persister-$it" }
        )
        assertEquals(
            listOf(
                "wallet-one", "persister-one",
                "wallet-two", "persister-two",
                "wallet-three", "persister-three"
            ),
            calls
        )
    }

    @Test
    fun `reports success only after every close succeeds`() {
        var walletCloses = 0
        var persisterCloses = 0

        val failures = NativeWalletResourceCleanup.closeAll(
            entries = listOf(1, 2, 3).map { NativeWalletResourceCleanup.CloseState(it) },
            closeWallet = { walletCloses++ },
            closePersister = { persisterCloses++ }
        )

        assertEquals(0, failures)
        assertEquals(3, walletCloses)
        assertEquals(3, persisterCloses)
    }

    @Test
    fun `public failure text cannot expose native exception details`() {
        val error = WalletCacheSecurityCleanupException()

        assertEquals(
            "Wallet security cleanup did not complete. Restart Clench before continuing.",
            error.message
        )
        assertEquals(null, error.cause)
    }
}
