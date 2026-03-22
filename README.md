# Clench Wallet — Android

Self-custody Bitcoin wallet for Android. Built on [BDK (Bitcoin Dev Kit) 1.1.0](https://bitcoindevkit.org/).

[![Android CI](https://github.com/clenchwallet/clench-wallet/actions/workflows/android.yml/badge.svg)](https://github.com/clenchwallet/clench-wallet/actions/workflows/android.yml)

## Download

Grab the latest debug APK from [Releases](https://github.com/clenchwallet/clench-wallet/releases/latest).

## Features

- **Self-custody** — your keys, your coins; BDK-based key management
- **Hardware wallet support** — Coldcard (Mk4 + Q), SeedSigner, Keystone, Passport, Jade via QR/NFC/SD
- **Watch-only wallets** — monitor without exposing keys; sign via HWW or ephemeral seed entry
- **BIP39 passphrase** — Sparrow-style duress wallet design; any passphrase opens a valid wallet, none stored
- **Coin control** — select UTXOs, freeze outputs, drain selected UTXOs
- **Wallet sweep** — sweep confirmed funds from any seed phrase
- **Custom Electrum server** — plain TCP (port 50001) or TLS; self-signed certs not supported (BDK limitation)
- **App lock** — biometric or Clench PIN with exponential backoff
- **No analytics** — zero tracking, zero telemetry

## Versioning

`versionName` in `app/build.gradle.kts` is the single source of truth.  
CI tags each master push as `v{versionName}` and publishes a GitHub Release with the APK.  
Bump `versionName` (and `versionCode`) in `build.gradle.kts` before each meaningful release.

| Version | Highlights |
|---------|-----------|
| 0.7.0 | Fix: stale UTXO state on passphrase lock/re-entry — `LifecycleResumeEffect` clears UTXO screen on every resume/pause |
| 0.6.0 | Fix: passphrase wallet UTXO leak — `unlockedPassphraseWallets` set as authoritative unlock signal; block `syncWallet`/`listUnspent`/`getTransactions` in locked state |
| 0.5.0 | WalletList settings button, passphrase back stack fix, clear passphrase on screen resume |
| 0.4.0 | Fix: passphrase wallet tx cache leak — wipe Room tx cache on lock and cold start |
| 0.3.0 | Passphrase duress wallet, security onboarding, port defaults, BDK SSL warning |
| 0.2.0 | In-memory passphrase sessions, live UTXO overflow warning, USD price in Send |
| 0.1.0 | Initial release — full wallet, HWW, coin control, sweep, Clench PIN |

## Architecture

```
app/src/main/java/net/clench/wallet/
├── ClenchApplication.kt          ← Hilt entry; wipes passphrase wallet DBs on cold start
├── data/
│   ├── local/
│   │   ├── ClenchDatabase.kt     ← Room DB (clench.db — wallets, txs, UTXO metadata)
│   │   ├── KeystoreManager.kt    ← Seed/descriptor storage (Android Keystore + AES-256-GCM)
│   │   ├── PinManager.kt         ← Clench PIN (HMAC-SHA256, exponential throttle)
│   │   ├── SettingsManager.kt    ← Electrum config, app lock, feature flags
│   │   └── dao/
│   └── repository/
│       └── BdkBitcoinRepository.kt  ← All BDK operations
├── domain/
│   ├── model/
│   └── repository/
│       └── BitcoinRepository.kt  ← Interface
├── di/
│   └── AppModule.kt
└── ui/
    ├── MainActivity.kt           ← App lock overlay, NFC PSBT handler
    ├── navigation/
    │   ├── Routes.kt
    │   └── ClenchNavHost.kt
    ├── screens/                  ← All screens (Send, Receive, Home, Settings, HWW, etc.)
    └── viewmodel/
```

## Tech Stack

| Layer | Library |
|-------|---------|
| Bitcoin core | `bdk-android 1.1.0` |
| UI | Jetpack Compose + Material3 |
| Navigation | Compose Navigation |
| DI | Hilt |
| Database | Room (SQLCipher encrypted) |
| Secure storage | Android Keystore + EncryptedSharedPreferences |
| QR scanning | ZXing Android Embedded |
| Language | Kotlin |
| Min SDK | 26 (Android 8.0) |

## Build

```bash
# Debug APK
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK 35, JDK 17+.

## Security Notes

- Seeds stored in Android Keystore (hardware-backed where available)
- Passphrase wallets: in-memory only — nothing written to disk; full Electrum sync required each session
- BIP39 passphrase: duress/plausible-deniability design — no stored passphrase, no validation feedback
- PSBT signing for all hardware wallet flows
- Debug signing key committed to repo (`app/debug.keystore`) — for development only
