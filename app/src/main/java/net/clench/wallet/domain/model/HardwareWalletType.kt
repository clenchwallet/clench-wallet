package net.clench.wallet.domain.model

enum class HardwareWalletType(val displayName: String, val connectionMethod: String) {
    SEEDSIGNER("SeedSigner", "QR"),
    KEYSTONE("Keystone", "QR / File"),
    FOUNDATION_PASSPORT("Foundation Passport", "QR / File"),
    COLDCARD_Q("Coldcard Q", "QR / NFC / File"),
    COLDCARD_MK4("Coldcard Mk4", "NFC / File / SD Card"),
    COLDCARD_MK5("Coldcard Mk5", "NFC / File / SD Card"),
    TAPSIGNER("TAPSIGNER", "NFC"),
    JADE("Blockstream Jade", "QR");

    val supportsQr: Boolean get() = connectionMethod.contains("QR")
    val supportsNfc: Boolean get() = connectionMethod.contains("NFC")
    val supportsSdCard: Boolean get() = connectionMethod.contains("SD")
    val usesColdcardNfcPayload: Boolean
        get() = this == COLDCARD_Q || this == COLDCARD_MK4 || this == COLDCARD_MK5
    val usesCoinkiteTapProtocol: Boolean get() = this == TAPSIGNER
    val isScreenlessSigner: Boolean get() = this == TAPSIGNER
}
