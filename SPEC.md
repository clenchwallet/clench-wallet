# Coinkite Tap Protocol SATSCARD Sweep Slice

## Goal

Add SATSCARD-aware Coinkite Tap Protocol status support and CVC-authenticated active-slot sweep without exposing raw private keys in the UI, TAPSIGNER xpub import, or TAPSIGNER PSBT signing.

## Files

- `/home/clawd/.openclaw/workspace/clench/android/app/src/main/java/net/clench/wallet/ui/components/TapsignerTapProtocol.kt`
- `/home/clawd/.openclaw/workspace/clench/android/app/src/test/java/net/clench/wallet/ui/components/TapsignerTapProtocolTest.kt`
- `/home/clawd/.openclaw/workspace/clench/android/app/src/main/java/net/clench/wallet/ui/screens/SweepScreen.kt`
- `/home/clawd/.openclaw/workspace/clench/android/app/src/main/java/net/clench/wallet/ui/screens/PrivacyPolicyScreen.kt`
- `/home/clawd/.openclaw/workspace/clench/android/docs/qa/manual-test-plan.md`
- `/home/clawd/.openclaw/workspace/clench/android/README.md`

## Behavior

- Parse Coinkite Tap Protocol status responses into a generic card status model that distinguishes:
  - TAPSIGNER
  - SATSCARD
  - unknown Coinkite card
- Preserve existing TAPSIGNER status behavior and guardrails.
- Add a generic NFC reader for Coinkite Tap Protocol status that accepts TAPSIGNER or SATSCARD.
- Keep the existing TAPSIGNER reader strict: it must still reject non-TAPSIGNER cards in TAPSIGNER-only flows.
- Add a SATSCARD panel to the sweep screen:
  - uses ISO-DEP Tap Protocol, not NDEF
  - shows clear status/error text
  - asks for CVC only when the user explicitly chooses Unseal and Sweep
  - requires explicit confirmation that unsealing the active slot is irreversible
  - verifies the SATSCARD read signature, payment address, card network, and Coinkite certificate chain before sending any CVC-authenticated command
  - sends authenticated `unseal`, verifies the returned private key matches the verified slot pubkey/address, sweeps via a temporary native SegWit wallet, and zeroes key buffers
- Update docs/privacy wording to include SATSCARD NFC status and authenticated sweep checks.

## Scope Boundary

- Do not add native/JNI code.
- Do not copy Nunchuk implementation code.
- Do not implement TAPSIGNER xpub import.
- Do not implement TAPSIGNER PSBT signing.
- Do not display SATSCARD private keys or persist CVC/private-key material.
- Do not add SATSCARD to `HardwareWalletType`, because it is a sweep/source-card flow rather than a wallet signer.

## Verification

- Run:
  - `./gradlew testDebugUnitTest --tests 'net.clench.wallet.ui.components.TapsignerTapProtocolTest'`
  - `./gradlew testDebugUnitTest --tests 'net.clench.wallet.viewmodel.WifPrivateKeyParserTest'`
  - `./gradlew testDebugUnitTest --tests 'net.clench.wallet.domain.model.HardwareWalletTypeTest'`
- Expected: both commands pass.
