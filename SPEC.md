# Coinkite Tap Protocol Status Slice

## Goal

Add SATSCARD-aware Coinkite Tap Protocol status support without exposing CVC entry, unseal, private-key export, TAPSIGNER xpub import, or PSBT signing.

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
- Add a SATSCARD status-only panel to the sweep screen:
  - uses ISO-DEP Tap Protocol, not NDEF
  - shows clear status/error text
  - says CVC-authenticated unseal/sweep is not enabled yet
  - does not ask for, store, or transmit CVC
  - does not unseal SATSCARD slots or import private keys
- Update docs/privacy wording to include SATSCARD NFC status checks.

## Scope Boundary

- Do not add native/JNI code.
- Do not copy Nunchuk implementation code.
- Do not add CVC prompts.
- Do not implement TAPSIGNER xpub import.
- Do not implement TAPSIGNER PSBT signing.
- Do not implement SATSCARD unseal, WIF/private-key display, or sweep transaction building from SATSCARD slots.
- Do not add SATSCARD to `HardwareWalletType`, because it is a sweep/source-card flow rather than a wallet signer.

## Verification

- Run:
  - `./gradlew testDebugUnitTest --tests 'net.clench.wallet.ui.components.TapsignerTapProtocolTest'`
  - `./gradlew testDebugUnitTest --tests 'net.clench.wallet.domain.model.HardwareWalletTypeTest'`
- Expected: both commands pass.
