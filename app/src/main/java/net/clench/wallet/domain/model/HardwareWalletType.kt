package net.clench.wallet.domain.model

enum class HardwareWalletType(val displayName: String, val connectionMethod: String) {
    SEEDSIGNER("SeedSigner", "QR"),
    KEYSTONE("Keystone", "QR / File"),
    FOUNDATION_PASSPORT("Foundation Passport", "QR / File"),
    COLDCARD_Q("Coldcard Q", "QR / NFC / File"),
    COLDCARD_MK4("Coldcard Mk4", "NFC / SD Card / Virtual Disk"),
    COLDCARD_MK5("Coldcard Mk5", "NFC / SD Card / Virtual Disk"),
    JADE("Blockstream Jade", "QR");

    val supportsQr: Boolean get() = connectionMethod.contains("QR")
    val supportsNfc: Boolean get() = connectionMethod.contains("NFC")
    val supportsSdCard: Boolean get() = connectionMethod.contains("SD")
}
