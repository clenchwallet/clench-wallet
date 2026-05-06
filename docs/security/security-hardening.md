# Security Hardening

This document summarizes Clench Wallet's current security controls for reviewers and release auditors. It intentionally describes the current posture rather than preserving stale implementation notes from older hardening passes.

## Data Protection

- Wallet metadata is stored in a SQLCipher-encrypted Room database.
- Seed material is encrypted with Android Keystore-backed AES-GCM storage.
- Release builds fail closed on missing Room migrations instead of destructively recreating wallet data.
- Release builds do not delete encrypted database files after database verification failure.
- Startup recovery paths are detection-oriented and do not silently reconstruct wallet records from inferred state.

## Signing and Broadcast

- Hardware-wallet signed PSBT and finalized transaction returns require explicit user review before broadcast.
- Raw transaction imports are previewed and require explicit broadcast confirmation.
- Multisig wallet information presents each signer independently so users can verify cosigner fingerprints and policies.
- Screenless signer flows are guarded where authenticated signing support is incomplete.

## Privacy Controls

- Wallet sync can use a custom Electrum server and optional Tor SOCKS5 routing.
- Electrum diagnostics expose route, Tor mode, TLS pin state, server version, and tip height for verification.
- External fee and price lookups are opt-in controls.
- The app does not include analytics or third-party tracking SDKs.

## Release Controls

- Production APKs are built from `v*` tags by the signed-release workflow.
- Release artifacts include checksum and signature verification documentation.
- Debug APKs produced by CI are short-retention artifacts and are not production releases.
- Dependency verification metadata is committed with Gradle dependency changes.

## Residual Risks

- Users remain responsible for verifying receive addresses, signer fingerprints, multisig policy, Electrum server identity, and release signatures before trusting funds to the wallet.
- Android process memory cannot guarantee zeroization of all JVM `String` instances.
- New code touching seed handling, signing, recovery, networking, release signing, or dependency metadata requires the review gate in [audit-path.md](audit-path.md).
