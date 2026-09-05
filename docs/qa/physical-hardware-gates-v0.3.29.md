# Clench Wallet 0.3.29 physical acceptance boundary

All exact-0.3.29 physical-device checks below are **NOT RUN**. Existing detailed
device rows in `physical-hardware-gates-v0.3.28.md` remain the test definitions,
not evidence for the new binary. Prior release authorization and the current
instruction to continue through publication do not turn a deferral into a pass.

- Representative seed/send system authentication on actual Android devices,
  including lifecycle interruption, unavailable credentials and older APIs.
- Real TAPSIGNER and SATSCARD NFC interactions, cancellation and cleanup.
- Camera scans and interruption/conflicting-frame recovery for supported QR formats.
- Hardware signer return collection and original-PSBT restart, followed by fresh
  output/fee review and a separately authorized broadcast.
- Existing wallet/database upgrade on representative physical devices.
- OneKey Pro, Krux, Specter DIY and previously listed signer model/firmware rows.

No USB or Bluetooth signer data path is introduced. SATSCARD remains
fund/verify/unseal/sweep-only. Testing after publication must identify exact
source SHA, signed APK hash/certificate, Android API/device, hardware firmware,
and sanitized outcome. No real secrets or reusable wallet data belong in reports.
