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
| Malicious QR/multipart payload | Typed and size-bounded UR/Base43 decoding, isolated multipart sessions, conflicting-stream rejection, and downstream PSBT/final-transaction policy validation |
| Malicious Electrum server | User-controlled Electrum server, TLS pinning, Tor routing, transaction validation before broadcast |
| Network privacy leak | Offline mode, Tor routing, opt-in price lookup, opt-in external fee fallback |
| Tampered dependency | Dependency locking and Gradle dependency verification |
| Tampered release artifact | Signed APKs, SHA-256 manifests, tag-based releases, release verification docs |
| Debug build mistaken for release | CI debug APKs are artifacts only; releases are tag-only signed builds |

## Assumptions

- Android Keystore and device screen lock are not fully compromised.
- Users protect seed phrase backups outside the app.
- Screen-equipped hardware wallets show trustworthy transaction details when
  used. TAPSIGNER is screenless, so the complete Clench phone review and an
  intentional tap form its confirmation boundary.
- Maintainers protect release keys and GitHub release credentials.
- Users verify release artifacts for high-value wallets.

## Residual Risks

- Code execution within Clench's process/app UID is already outside the UI-gate
  protection boundary; it does not require a fully compromised Android OS.
  `KeystoreManager` creates its wallet-secret `MasterKey` without a per-use
  authentication requirement. Code with that app identity and Keystore access
  can request decryption independently of the seed/send screen gates.
  `BiometricHelper` uses separate authentication-bound proof keys to authorize
  application actions; those keys do not wrap each wallet-secret decryption.
  Hardware-backed storage therefore must not be described as making secrets
  inaccessible to a compromised Clench process. Changing this storage boundary
  would require a separately tested key-migration and recovery design, not
  silently changing the existing key's configuration.
- A compromised Android OS can additionally observe UI, memory, clipboard, or input.
- JVM strings cannot be reliably zeroed after use.
- Mutable-buffer cleanup is best effort and does not erase prior copies made by
  JVM/native libraries, preferences, IPC, or the operating system. The clipboard
  helper retains the copied string in a delayed callback and attempts to replace
  only the unchanged clipboard after 60 seconds. Process death, background
  access restrictions or callback delays can prevent that cleanup. Android's
  sensitive-content flag is a presentation hint, not an access-control or
  clipboard-history-erasure guarantee. Avoid copying secrets where possible.
- Distinct multisig key material does not prove independent ownership or devices.
  Newly created/imported policies reject equivalent extended-key material;
  older funded policies must retain their exact descriptors and recovery access.
- Maven dependency inventory/advisory success does not establish coverage of
  native transitive dependencies. See [native assurance](native-assurance.md)
  for artifact identity, source mappings and unresolved coverage.
- A malicious Electrum server can degrade availability and may learn wallet activity unless the user uses their own node or Tor.
- Tor routing depends on a working SOCKS proxy such as Orbot.
- First-use trust of the release signing certificate requires an out-of-band digest.
- Reproducibility can be affected by Android build tools and signing-key differences.
- A compromised phone can misrepresent the transaction shown before a
  screenless TAPSIGNER tap; use an independently reviewed screen-equipped
  cosigner for higher-assurance multisig.
- New OneKey Pro, Krux, Specter DIY, camera-format, and TAPSIGNER multisig paths
  have automated coverage but no recorded v0.3.28 physical-device acceptance.

## Review Triggers

Update this threat model when changing:

- Key storage, seed import/export, descriptors, passphrases, or wallet persistence.
- Transaction building, PSBT parsing, signing, finalization, or broadcast.
- Electrum, Tor, fee lookup, price lookup, or certificate pinning behavior.
- Release signing, CI, dependency management, or artifact publication.
- Recovery wizard or backup import behavior.
