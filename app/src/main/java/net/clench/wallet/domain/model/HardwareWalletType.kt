package net.clench.wallet.domain.model

enum class PsbtQrFormat {
    CRYPTO_PSBT_UR,
    BBQR
}

enum class HardwareWalletType(
    val displayName: String,
    val connectionMethod: String,
    val psbtQrFormat: PsbtQrFormat? = null,
    val supportsFileTransfer: Boolean = false
) {
    SEEDSIGNER("SeedSigner", "QR", PsbtQrFormat.CRYPTO_PSBT_UR),
    KEYSTONE("Keystone", "QR / File", PsbtQrFormat.CRYPTO_PSBT_UR, supportsFileTransfer = true),
    ONEKEY_PRO("OneKey Pro", "QR", PsbtQrFormat.CRYPTO_PSBT_UR),
    KRUX("Krux", "QR / microSD", PsbtQrFormat.CRYPTO_PSBT_UR, supportsFileTransfer = true),
    SPECTER_DIY("Specter DIY", "QR / microSD", PsbtQrFormat.CRYPTO_PSBT_UR, supportsFileTransfer = true),
    FOUNDATION_PASSPORT(
        "Foundation Passport",
        "QR / File",
        PsbtQrFormat.CRYPTO_PSBT_UR,
        supportsFileTransfer = true
    ),
    COLDCARD_Q("Coldcard Q", "QR / NFC / File", PsbtQrFormat.BBQR, supportsFileTransfer = true),
    COLDCARD_MK4("Coldcard Mk4", "NFC / File / SD Card", supportsFileTransfer = true),
    COLDCARD_MK5("Coldcard Mk5", "NFC / File / SD Card", supportsFileTransfer = true),
    TAPSIGNER("TAPSIGNER", "NFC"),
    JADE("Blockstream Jade", "QR", PsbtQrFormat.CRYPTO_PSBT_UR);

    val supportsQr: Boolean get() = psbtQrFormat != null
    val supportsNfc: Boolean get() = connectionMethod.contains("NFC")
    val supportsSdCard: Boolean get() = connectionMethod.contains("SD")
    val usesColdcardNfcPayload: Boolean
        get() = this == COLDCARD_Q || this == COLDCARD_MK4 || this == COLDCARD_MK5
    val usesCoinkiteTapProtocol: Boolean get() = this == TAPSIGNER
    val isScreenlessSigner: Boolean get() = this == TAPSIGNER
}
