package net.clench.wallet.ui.screens

import net.clench.wallet.ui.components.NfcImportSession
import net.clench.wallet.ui.viewmodel.HardwareWalletPsbtViewModel

/** Each screen attempt owns only its own connection and unclaimed credential. */
internal sealed interface TapsignerNfcAttempt {
    val id: Long
    val io: NfcImportSession.Attempt

    class Status(override val id: Long) : TapsignerNfcAttempt {
        override val io = NfcImportSession.Attempt(CharArray(0))
    }

    class Sign(
        override val id: Long,
        val token: HardwareWalletPsbtViewModel.TapsignerSigningToken,
        cvc: CharArray
    ) : TapsignerNfcAttempt {
        override val io = NfcImportSession.Attempt(cvc)
    }
}

internal fun TapsignerNfcAttempt.clearSecret() = io.cancel()
