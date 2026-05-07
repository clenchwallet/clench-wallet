# Clench Wallet

A Bitcoin-only, non-custodial on-chain wallet for Android. Built with [BDK](https://bitcoindevkit.org/) (Bitcoin Dev Kit) 2.3.1.

## Features

### Core
- Single-sig and multisig (M-of-N) wallet creation
- Watch-only wallets via descriptor import
- BIP-39 seed phrase (12/24 words) with optional passphrase (BIP-39)
- Multiple script types: Native SegWit (BIP-84), Nested SegWit (BIP-49), Legacy (BIP-44), Taproot (BIP-86)
- Testnet support

### Hardware Wallet Support
- **QR-based:** SeedSigner, Keystone, Foundation Passport, Blockstream Jade
- **Coldcard Q:** BBQr QR, NFC, and file-based PSBT transfer
- **Coldcard Mk4/Mk5:** NFC, SD card, and virtual disk PSBT transfer
- **Tapsigner:** NFC Tap Protocol status checks and screenless-signer guardrails; direct PSBT signing is blocked until authenticated signing support is complete
- **Signed-return support:** signed PSBT import, plus finalized transaction returns where supported
- Explicit broadcast confirmation after hardware-wallet signing
- Full PSBT (BIP-174) workflow

### Privacy
- **Tor support for Electrum** — route wallet sync traffic through a SOCKS5 proxy
- **Custom Electrum server** — connect to your own node
- **Node diagnostics** — check Electrum route, Tor mode, TLS pin state, server version, and tip height
- **Coin control** — manual UTXO selection and freezing
- **No analytics** — no third-party tracking SDKs

### Transactions
- Batch sending (multiple recipients, single transaction)
- Transaction labeling with BIP-329 export/import
- Replace-By-Fee (RBF) fee bumping
- CPFP child transaction creation from spendable outputs
- RBF cancel replacement attempts back to the same wallet
- Raw transaction import, preview, and explicit broadcast
- Saved payees and BDK-backed network/script address verification
- Sweep functionality
- Fee and confirmation context via Electrum, with optional external mempool.space fee fallback
- Recovery wizard for state backups, seed phrase restores, descriptor imports, and cross-wallet verification

### Security
- AES-256-GCM encrypted key storage via Android Keystore
- SQLCipher database encryption
- Crypto-bound biometric authentication
- PIN lock with brute-force protection
- PSBT/final transaction validation before broadcast
- No auto-broadcast after scanning signed hardware-wallet payloads
- No analytics, no tracking, no third-party tracking SDKs
- Tag-only signed release builds with checksum and signature verification docs

## Building

### Requirements
- Android Studio Ladybug or later
- JDK 21
- Android SDK 36

### Build
```bash
git clone https://github.com/clenchwallet/clench-wallet.git
cd clench-wallet
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Release Build
```bash
./gradlew assembleRelease
```
Requires a signing keystore configured in `keystore.properties`.

Release builds are published only from `v*` tags. Debug APKs from CI are short-retention artifacts and are not production wallet releases.

Release trust docs:
- [Reproducible builds](docs/release/reproducible-builds.md)
- [Signed-release verification](docs/release/signed-release-verification.md)
- [Threat model](docs/security/threat-model.md)
- [Security hardening](docs/security/security-hardening.md)
- [Audit path](docs/security/audit-path.md)

## Architecture

- **BDK 2.3.1** — Bitcoin Dev Kit for wallet operations, transaction building, and Electrum sync
- **Jetpack Compose** — Modern declarative UI
- **Room + SQLCipher** — Encrypted local database
- **Hilt** — Dependency injection
- **Kotlin Coroutines** — Async operations

## Contributing

Contributions welcome. Please open an issue first to discuss significant changes.

## Security

If you discover a security vulnerability, please report it responsibly by emailing security@clench.net (do NOT open a public issue).

For release verification and security-review scope, see [docs/release/README.md](docs/release/README.md) and [docs/security/threat-model.md](docs/security/threat-model.md).

## Changelog

| Version | Highlights |
| --- | --- |
| Unreleased | Ongoing hardening and usability improvements. |
| 0.3.13 | Switched passphrase wallet fingerprint graphics to Sparrow Wallet's LifeHash v2 generation method and added Toucan attribution. |
| 0.3.12 | Added device-backed multisig signer slots, signer-device metadata in Wallet Info, and clearer multisig import choices for complete backups, device exports, and signer assembly. |
| 0.3.11 | Fixed onboarding text contrast, made seed phrase reveal auth usable on devices where crypto-bound biometric auth is unavailable, pre-synced imports before opening Home, and removed descriptor-backup prompts from hot single-sig wallets. |
| 0.3.10 | Kept JNA intact in minified releases for BDK native calls, prevented QR/import/create-wallet native boundary crashes from closing the app, and fixed onboarding background contrast. |
| 0.3.9 | Migrated release SQLCipher runtime to the 16 KB page-size compatible Android package and labeled debug builds as Clench Debug. |
| 0.3.8 | Hid single-sig wallet fingerprints, seed phrase conversion, and seed phrase viewing for multisig wallets even when an imported descriptor is misclassified. |
| 0.3.7 | Hardened wallet data-loss, logging, passphrase recovery, multisig metadata, and release-trust surfaces. |
| 0.3.6 | Hid the single-wallet seed phrase conversion action from multisig Wallet Info while keeping per-keystore signer fingerprints visible for verification. |
| 0.3.5 | Added multisig keystore renaming, removed misleading single-wallet multisig fingerprint/signing-device settings, and improved Specter Desktop/Specter DIY descriptor JSON imports. |
| 0.3.4 | Added multisig Wallet Info signer checks, BSMS round-trip export metadata, recovery wizard and drill guidance, Tapsigner NFC status/signing guardrails, CPFP/cancel transaction flows, raw transaction broadcast/import, saved payees, BDK-backed address verification, Electrum diagnostics, opt-in external lookup controls, and release-trust documentation/workflows. |
| 0.3.4 | Fixed Sparrow multisig Output Descriptor QR imports; added BSMS descriptor and Coldcard multisig config imports; hardened multisig imports so wallet configs cannot collapse to one cosigner xpub. |
| 0.3.3 | Initial public release with F-Droid-ready metadata, BDK 2.3.1, hardware wallet PSBT flows, Tor Electrum support, encrypted storage, and multisig creation. |

## License

MIT License. See [LICENSE](LICENSE) for details.

## Donate

If you find Clench useful, consider donating:
- Bitcoin: (address TBD)
