# Clench Wallet

A Bitcoin-only, non-custodial on-chain wallet for Android. Built with [BDK](https://bitcoindevkit.org/) (Bitcoin Dev Kit) 3.0.0.

The static source for [clench.net](https://clench.net/) is in [`website/`](website/).

## Features

### Core
- Single-sig and multisig (M-of-N) wallet creation
- Watch-only wallets via descriptor import
- BIP-39 seed phrase (12/24 words) with optional passphrase (BIP-39)
- Multiple script types: Native SegWit (BIP-84), Nested SegWit (BIP-49), Legacy (BIP-44), Taproot (BIP-86)
- Testnet support

### Hardware Wallet Signing — Without USB or Bluetooth
- **QR-based:** SeedSigner, Keystone, Foundation Passport, and Blockstream Jade. v0.3.28 also adds physically unverified BC-UR v2 protocol presets intended for OneKey Pro, Krux, and Specter DIY; the Krux and Specter DIY presets include a user-selected microSD/file PSBT path.
- **Coldcard Q:** BBQr QR, NFC, and file-based PSBT transfer
- **Coldcard Mk4/Mk5:** NFC or user-selected file/microSD PSBT transfer
- **TAPSIGNER:** Set up, import, verify, back up, and directly sign PSBT-v0 BIP-84 P2WPKH single-signature or standard BIP-48 native-SegWit P2WSH multisig inputs over NFC with `SIGHASH_ALL`. Clench reviews the complete payment before the tap, verifies returned and existing policy-member signatures, merges atomically, and never auto-broadcasts. Physical acceptance of the new multisig path is not recorded. Taproot, legacy, nested-SegWit, nonstandard P2WSH, other account paths, and PIN change are not supported.
- **SATSCARD:** NFC status plus certificate-verified, CVC-authenticated active-slot funding, unseal, and sweep. A sealed SATSCARD slot cannot sign, and Clench does not present SATSCARD as a general PSBT or multisig signer.
- **Signed-return support:** signed PSBT import, plus finalized transaction returns where supported
- Explicit broadcast confirmation after hardware-wallet signing
- Full PSBT (BIP-174) workflow

Clench never opens a USB or Bluetooth data connection to a signing device.
Transactions move by QR, an intentional NFC tap, or a file/removable card chosen
by the user. A signer may still use a USB cable for power. With a screen-equipped
signer, review the transaction on that device. TAPSIGNER is screenless, so its
transaction details must be reviewed carefully in Clench before entering the
PIN and tapping the card.

The OneKey Pro, Krux, Specter DIY, and TAPSIGNER multisig paths introduced in
v0.3.28 have automated coverage but not recorded physical-device acceptance.
The release therefore makes no model/firmware compatibility claim for those
new paths; see the v0.3.28 physical-evidence record.

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
- Sweep functionality for external seeds, WIF paper wallets/OpenDime exports, and SATSCARD active slots
- Fee and confirmation context via Electrum, with optional external mempool.space fee fallback
- Recovery wizard for state backups, seed phrase restores, descriptor imports, and cross-wallet verification

### Security
- AES-256-GCM encrypted key storage via Android Keystore
- SQLCipher encryption for the Room application database; BDK's separate public wallet-state files are not SQLCipher-encrypted
- Crypto-bound biometric/device-credential approval gates for seed display and software signing
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

### Unsigned release evidence build
```bash
test ! -e keystore.properties
./gradlew assembleRelease
```

Without `keystore.properties`, this produces unsigned evidence only. Do not
install or distribute it as a wallet release. Production APKs are signed only
inside the protected, isolated GitHub release environment after the approved
unsigned digest and independent build evidence pass.

Production releases are dispatched only from protected `master` after a
pinned-key signed `v*` tag is proven to target that exact commit. Debug APKs
from CI are short-retention artifacts and are not production wallet releases.

Release trust docs:
- [Reproducible builds](docs/release/reproducible-builds.md)
- [Signed-release verification](docs/release/signed-release-verification.md)
- [Threat model](docs/security/threat-model.md)
- [Security hardening](docs/security/security-hardening.md)
- [v0.3.28 hardware-wallet and TAPSIGNER security review](docs/security/security-review-v0.3.28.md)
- [Audit path](docs/security/audit-path.md)
- [v0.3.28 Coinkite compatible-wallet listing-readiness assessment](docs/qa/coinkite-wallet-list-readiness.md)
- [v0.3.28 physical-hardware gates](docs/qa/physical-hardware-gates-v0.3.28.md)

## Architecture

- **BDK 3.0.0** — Bitcoin Dev Kit for wallet operations, transaction building, and Electrum sync
- **Jetpack Compose** — Modern declarative UI
- **Room + SQLCipher** — Encrypted app state. BDK's sandboxed per-wallet files
  contain public descriptors, scripts, and transaction metadata, not seeds or
  extended private keys.
- **Hilt** — Dependency injection
- **Kotlin Coroutines** — Async operations

## Contributing

Contributions welcome. Please open an issue first to discuss significant changes.

## Security

If you discover a security vulnerability, please report it responsibly by emailing cw@clench.net (do NOT open a public issue).

For release verification and security-review scope, see [docs/release/README.md](docs/release/README.md) and [docs/security/threat-model.md](docs/security/threat-model.md).

## Changelog

| Version | Highlights |
| --- | --- |
| 0.3.29 (candidate) | Fresh-authentication protection changes, multisig key-alias rejection, signing-session recovery, backup/network/QR hardening, and tested SQLCipher 4.17 upgrade; see candidate security review for native provenance limits. |
| 0.3.28 | Adds physically unverified air-gapped protocol presets intended for OneKey Pro, Krux, and Specter DIY; implements TAPSIGNER BIP-48 native-P2WSH multisig signing with physical acceptance still `NOT RUN`; adds legacy UR, `ur:psbt`, `ur:bytes`, and Base43 imports; and fixes the persisted TAPSIGNER settings label. |
| 0.3.27 | Adds verified TAPSIGNER signing for single-signature BIP-84 native-SegWit payments, real-card CBOR/derive/xpub interoperability, numeric-first PIN entry with a legacy fallback, BDK Android 3.0 persisted-wallet migration proof, Room/SQLCipher instrumentation, and an updated Android/Kotlin build toolchain. |
| 0.3.26 | Carries the v0.3.24 security hardening forward after two unpublished, fail-closed release attempts and adds a two-gate, three-build reproducibility proof: byte-identical unsigned builds, followed by deterministic `apksigner` packaging normalization and a no-exclusions comparison of every APK ZIP entry. |
| 0.3.25 | Unpublished fail-closed candidate: disabled unused APK v4 sidecars, then stopped before publication when the independent verifier rejected a post-`apksigner` local-header alignment-metadata mismatch. |
| 0.3.23 | Removes unused Bluetooth permissions and stale USB/Bluetooth/Virtual Disk signer claims, enforces QR/NFC/user-selected-file transports, verifies TAPSIGNER setup chain-code use, and documents the exact SATSCARD/TAPSIGNER compatible-wallet listing gates. |
| 0.3.22 | Added hostile protocol/property testing, reproducible-build and provenance evidence, stronger PSBT/QR/NFC/multisig/fee/storage boundaries, and an independently verified signed release. Hardware-wallet signing remains a QR/NFC/file PSBT round trip; direct TAPSIGNER payment signing is not yet implemented. |
| 0.3.21 | Fixed Clench phone-signer multisig creation, prevented unavailable OS authentication from blocking signing, showed estimated final multisig PSBT size and fee rate, and improved F-Droid “Bitcoin wallet” discovery metadata. |
| 0.3.20 | Hardened encrypted wallet-state recovery, transaction approval, fee controls, PSBT validation, TLS pinning, release signing, and dependency verification; added secure BIP39 entry, guided sweep/recovery flows, unified transaction review, and clearer hardware-signer progress. |
| 0.3.19 | Added WIF/OpenDime and verified SATSCARD sweep support, TAPSIGNER import and wallet verification flows, SATSCARD funding, the Signers vault, signer-based wallet creation choices, and release ABI filtering for F-Droid. |
| 0.3.18 | Removed Android dependency metadata from release APK signing blocks for F-Droid binary scanning, and fixed multisig hardware signer PSBT flows. |
| 0.3.17 | Added SATSCARD NFC Tap Protocol status checks in the sweep tool while keeping CVC-authenticated unseal/sweep blocked pending dedicated support, and changed project contact email to cw@clench.net. |
| 0.3.16 | Fixed multisig signer-key progress reporting during cosigner setup. |
| 0.3.15 | Added advanced multisig phone signer setup, encrypted phone-key PSBT signing, and guardrails for multi-key hot signer policies. |
| 0.3.14 | Hid hardware-signer selection from hot wallets, clarified Tor via Orbot, changed Recovery to method-based import guidance, and added app diagnostics/F-Droid polish. |
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
