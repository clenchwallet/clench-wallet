package net.clench.wallet.domain.model

enum class HardwareWalletType(val displayName: String, val connectionMethod: String) {
    SEEDSIGNER("SeedSigner", "QR"),
    KEYSTONE("Keystone", "QR"),
    FOUNDATION_PASSPORT("Foundation Passport", "QR"),
    COLDCARD_Q("Coldcard Q", "QR / NFC"),
    COLDCARD_MK4("Coldcard Mk4", "NFC / SD Card"),
    JADE("Blockstream Jade", "QR");

    val supportsQr: Boolean get() = connectionMethod.contains("QR")
    val supportsNfc: Boolean get() = connectionMethod.contains("NFC")
    val supportsSdCard: Boolean get() = connectionMethod.contains("SD")
}
