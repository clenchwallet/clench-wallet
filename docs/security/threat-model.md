# Threat Model

This threat model covers Clench Wallet as a Bitcoin-only, non-custodial Android wallet.

## Security Goals

- Keep seed phrases, private descriptors, PSBT signing authority, PIN material, and encrypted wallet data confidential.
- Prevent unauthorized transaction creation, signing, mutation, or broadcast.
- Prevent silent wallet-data deletion or misleading recovery state.
- Minimize network privacy leaks from Electrum, Tor, fee, and price lookup behavior.
- Make releases verifiable enough that users can avoid debug, tampered, or unsigned builds.

## In-Scope Assets

- BIP-39 seed phrases and passphrases.
- Private descriptors and signing descriptors.
- Hardware-wallet PSBTs and finalized transactions.
- PIN and biometric gate state.
- SQLCipher database and Android Keystore material.
- Wallet metadata, UTXOs, transaction history, labels, saved payees, and descriptors.
- Electrum server configuration, Tor routing configuration, and pinned TLS certificates.
- Release signing key and release artifacts.

## Trust Boundaries

- Android OS and secure hardware boundary.
- App process boundary.
- Android Keystore and biometric prompt boundary.
- SQLCipher encrypted Room database boundary and the separate plaintext BDK public-wallet-state boundary.
- Electrum server and Tor SOCKS proxy boundary.
- Hardware wallet QR/NFC/file-transfer boundary.
- GitHub CI/release boundary.
- Maintainer signing-key boundary.

## Threat Actors

- Malicious app on the same Android device.
- Compromised or malicious Electrum server.
- Network observer or exit-node observer.
- Malicious hardware-wallet payload source.
- User-interface spoofing or address substitution attacker.
- CI, dependency, or release-artifact attacker.
- Device thief with physical access.
- Malware on a developer or maintainer machine.

## Key Threats And Controls

| Threat | Control |
| --- | --- |
| Seed or private-descriptor disclosure | Android Keystore encryption, PIN/biometric UI gates, secure-window handling, no analytics SDKs |
| Public wallet-graph disclosure | Android app sandbox and device encryption; BDK SQLite state is explicitly treated as sensitive public metadata |
| Passphrase wallet confusion | Explicit passphrase restore paths, locked-state privacy, no silent passphrase recovery |
| Destructive migration data loss | Room migrations fail closed instead of destructive fallback |
| Silent database deletion | Release builds fail closed on database verification failure |
| Address substitution | Address verification, hardware-wallet PSBT review, explicit broadcast confirmation |
| Hardware-wallet auto-broadcast | Signed PSBT and finalized transaction returns require explicit broadcast |
| Malicious Electrum server | User-controlled Electrum server, TLS pinning, Tor routing, transaction validation before broadcast |
| Network privacy leak | Offline mode, Tor routing, opt-in price lookup, opt-in external fee fallback |
| Tampered dependency | Dependency locking and Gradle dependency verification |
| Tampered release artifact | Signed APKs, SHA-256 manifests, tag-based releases, release verification docs |
| Debug build mistaken for release | CI debug APKs are artifacts only; releases are tag-only signed builds |

## Assumptions

- Android Keystore and device screen lock are not fully compromised.
- Users protect seed phrase backups outside the app.
- Hardware wallets show trustworthy transaction details when used.
- Maintainers protect release keys and GitHub release credentials.
- Users verify release artifacts for high-value wallets.

## Residual Risks

- A fully compromised Android device can observe UI, memory, clipboard, or input.
- JVM strings cannot be reliably zeroed after use.
- A malicious Electrum server can degrade availability and may learn wallet activity unless the user uses their own node or Tor.
- Tor routing depends on a working SOCKS proxy such as Orbot.
- First-use trust of the release signing certificate requires an out-of-band digest.
- Reproducibility can be affected by Android build tools and signing-key differences.

## Review Triggers

Update this threat model when changing:

- Key storage, seed import/export, descriptors, passphrases, or wallet persistence.
- Transaction building, PSBT parsing, signing, finalization, or broadcast.
- Electrum, Tor, fee lookup, price lookup, or certificate pinning behavior.
- Release signing, CI, dependency management, or artifact publication.
- Recovery wizard or backup import behavior.
