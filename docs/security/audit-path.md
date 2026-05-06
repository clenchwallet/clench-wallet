# Audit Path

This document defines how Clench Wallet security-sensitive changes should be reviewed before release.

## Security-Sensitive Areas

Treat these areas as high risk:

- Seed phrase, passphrase, descriptor, and key storage flows.
- SQLCipher, Android Keystore, PIN, biometric, and backup logic.
- Transaction creation, RBF, CPFP, coin control, PSBT, finalized transaction, and broadcast logic.
- Hardware-wallet QR, NFC, file, and signed-return paths.
- Electrum, Tor, TLS certificate pinning, fee lookup, price lookup, and offline mode.
- Room migrations and recovery wizard imports.
- Gradle dependencies, dependency verification metadata, CI, release signing, and published artifacts.

## Required Review Gate

Before release:

1. Run unit tests and debug build.
2. Run lint or record why lint cannot run.
3. Run `git diff --check`.
4. Review every changed dependency, lockfile, and verification-metadata update.
5. Review every changed wallet-signing, broadcast, descriptor, recovery, or network-privacy path.
6. Complete the manual ship gate in `docs/qa/manual-test-plan.md`.
7. Build the signed release from a clean tag.
8. Publish APK, `SHA256SUMS`, and release notes.
9. Verify the published APK signature and checksum from a fresh download.

## Dependency Review

For dependency changes:

- Explain why the dependency is needed.
- Prefer mature libraries with active maintenance.
- Check release notes for security or breaking changes.
- Commit lockfiles and `gradle/verification-metadata.xml` together.
- Avoid adding dependencies to key-management or signing paths unless there is a strong reason.

## External Audit Intake

An external audit should receive:

- Current release tag.
- `docs/security/threat-model.md`.
- `docs/security/security-hardening.md`.
- `docs/qa/manual-test-plan.md`.
- `docs/release/reproducible-builds.md`.
- `docs/release/signed-release-verification.md`.
- A list of known residual risks and out-of-scope items.

## Finding Severity

| Severity | Meaning |
| --- | --- |
| Critical | Direct loss of funds, seed/private-key disclosure, unauthorized signing, or release-key compromise |
| High | Broadcast bypass, address substitution, recovery data corruption, severe privacy leak, or destructive wallet-data loss |
| Medium | Security control bypass with user interaction, misleading wallet state, denial of wallet access with recovery path |
| Low | Defense-in-depth issue, documentation gap, or hardening improvement |

## Release Blockers

Block release for:

- Any critical or high finding without a documented mitigation.
- New unsigned or debug release artifacts.
- Changed release signer without key-rotation notice.
- Missing or failing dependency verification.
- Missing manual-test coverage for changed signing, recovery, or network-privacy paths.
- Unexplained dependency, lockfile, or Gradle wrapper changes.
