package net.clench.wallet.domain.model

object PhoneSigner {
    const val DEVICE_TYPE = "CLENCH_PHONE_SIGNER"
    const val DISPLAY_NAME = "Clench Phone Signer"

    fun displayName(raw: String): String = when (raw) {
        DEVICE_TYPE -> DISPLAY_NAME
        else -> raw
    }
}
