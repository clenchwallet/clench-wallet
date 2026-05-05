package net.clench.wallet.viewmodel

import net.clench.wallet.ui.viewmodel.ReceiveViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiveViewModelTest {

    @Test
    fun `buildBip21Uri address only`() {
        val result = ReceiveViewModel.buildBip21Uri(
            "bc1qexampleaddress",
            ""
        )

        assertEquals("bitcoin:bc1qexampleaddress", result)
    }

    @Test
    fun `buildBip21Uri with sats amount`() {
        val result = ReceiveViewModel.buildBip21Uri(
            "bc1qexampleaddress",
            "123456789"
        )

        assertEquals("bitcoin:bc1qexampleaddress?amount=1.23456789", result)
    }

    @Test
    fun `buildBip21Uri trims trailing btc zeros`() {
        val result = ReceiveViewModel.buildBip21Uri(
            "bc1qexampleaddress",
            "50000"
        )

        assertEquals("bitcoin:bc1qexampleaddress?amount=0.0005", result)
    }

    @Test
    fun `buildBip21Uri ignores zero amount`() {
        val result = ReceiveViewModel.buildBip21Uri(
            "bc1qexampleaddress",
            "0"
        )

        assertEquals("bitcoin:bc1qexampleaddress", result)
    }
}
