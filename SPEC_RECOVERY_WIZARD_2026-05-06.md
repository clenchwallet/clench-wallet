# Recovery Wizard Spec - 2026-05-06

## Scope

Implement wallet gap item #4:

> Backup/recovery: restore wizard, descriptor backup warnings, cross-wallet recovery docs for Sparrow/BlueWallet/Nunchuk.

## Constraints

- Do not add a new secret backup format.
- Do not store seed phrases, private descriptors, passphrases, PINs, or biometric secrets in state backups.
- Reuse existing import and backup primitives where possible.
- Treat descriptors/xpubs as public but privacy-sensitive.
- Multisig recovery must emphasize that seed words alone are not sufficient.

## Changes

1. Recovery wizard route
   - Add a top-level recovery wizard route reachable from Welcome, Home, and Settings.
   - Let users choose recovery source:
     - Clench state backup file.
     - Seed phrase/passphrase.
     - Descriptor/xpub/BSMS/multisig config.
     - Hardware wallet public export.
   - State backup import runs directly in the wizard using the existing state backup manager.
   - Seed/descriptor/hardware routes navigate to the existing import screens.

2. Verification checklist
   - Show a clear post-import checklist:
     - network
     - first receive address
     - script type
     - master fingerprint / derivation path
     - multisig M-of-N and cosigner set
     - small receive/spend rehearsal

3. Descriptor backup warnings
   - Strengthen descriptor backup copy around watch-only vs spend authority and privacy.
   - Remind multisig users to preserve every cosigner origin/fingerprint/path plus threshold policy.

4. Cross-wallet recovery docs
   - Add in-app guidance for Sparrow, Nunchuk, and BlueWallet recovery paths.
   - Add README/manual-test coverage for recovery wizard flows.

## Verification

- Focused build compile through `assembleDebug`.
- Full `testDebugUnitTest`.
- `git diff --check`.
