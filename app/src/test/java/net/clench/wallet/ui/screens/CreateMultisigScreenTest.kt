package net.clench.wallet.ui.screens

import net.clench.wallet.ui.viewmodel.CreateMultisigViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class CreateMultisigScreenTest {

    @Test
    fun `add cosigner progress uses signer key count`() {
        val signers = listOf(
            CreateMultisigViewModel.SignerInfo(xpub = "xpub6Alpha"),
            CreateMultisigViewModel.SignerInfo(xpub = ""),
            CreateMultisigViewModel.SignerInfo(xpub = "xpub6Charlie"),
            CreateMultisigViewModel.SignerInfo(xpub = ""),
            CreateMultisigViewModel.SignerInfo(xpub = "xpub6Echo")
        )

        val status = createMultisigProgressIndicatorState(currentStep = 2, signers = signers)

        assertEquals("Signer keys 3 of 5", status.label)
        assertEquals(0.6f, status.progress, 0.0001f)
    }

    @Test
    fun `wizard progress still uses three steps outside cosigner entry`() {
        val signers = List(5) { CreateMultisigViewModel.SignerInfo() }

        val status = createMultisigProgressIndicatorState(currentStep = 3, signers = signers)

        assertEquals("Step 3 of 3", status.label)
        assertEquals(1f, status.progress, 0.0001f)
    }
}
