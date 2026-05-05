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
- **Signed-return support:** signed PSBT import, plus finalized transaction returns where supported
- Explicit broadcast confirmation after hardware-wallet signing
- Full PSBT (BIP-174) workflow

### Privacy
- **Tor support for Electrum** — route wallet sync traffic through a SOCKS5 proxy
- **Custom Electrum server** — connect to your own node
- **Coin control** — manual UTXO selection and freezing
- **No analytics** — no third-party tracking SDKs

### Transactions
- Batch sending (multiple recipients, single transaction)
- Transaction labeling with BIP-329 export/import
- Replace-By-Fee (RBF) fee bumping
- Sweep functionality
- Fee estimation via Electrum + mempool.space fallback

### Security
- AES-256-GCM encrypted key storage via Android Keystore
- SQLCipher database encryption
- Crypto-bound biometric authentication
- PIN lock with brute-force protection
- PSBT/final transaction validation before broadcast
- No auto-broadcast after scanning signed hardware-wallet payloads
- No analytics, no tracking, no third-party tracking SDKs

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

## Changelog

| Version | Highlights |
| --- | --- |
| 0.3.4 | Fixed Sparrow multisig Output Descriptor QR imports; added BSMS descriptor and Coldcard multisig config imports; hardened multisig imports so wallet configs cannot collapse to one cosigner xpub. |
| 0.3.3 | Initial public release with F-Droid-ready metadata, BDK 2.3.1, hardware wallet PSBT flows, Tor Electrum support, encrypted storage, and multisig creation. |

## License

MIT License. See [LICENSE](LICENSE) for details.

## Donate

If you find Clench useful, consider donating:
- Bitcoin: (address TBD)
