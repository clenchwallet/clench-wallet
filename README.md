# Clench Wallet — Android

Self-custody Bitcoin wallet for Android. Built on [BDK (Bitcoin Dev Kit)](https://bitcoindevkit.org/), funded by Block/Spiral.

## Architecture

```
app/src/main/java/net/clench/wallet/
├── ClenchApplication.kt          ← Hilt app entry point
├── data/
│   ├── local/
│   │   ├── ClenchDatabase.kt     ← Room database
│   │   ├── KeystoreManager.kt    ← Encrypted seed storage (Android Keystore)
│   │   ├── dao/
│   │   │   ├── WalletDao.kt
│   │   │   └── TransactionDao.kt
│   │   └── entity/
│   │       ├── WalletEntity.kt
│   │       └── TransactionEntity.kt
│   └── repository/
│       └── BdkBitcoinRepository.kt  ← BDK implementation (TODOs to wire up)
├── domain/
│   ├── model/
│   │   └── Models.kt             ← WalletData, TransactionItem, Address, ElectrumConfig
│   └── repository/
│       └── BitcoinRepository.kt  ← Interface
├── di/
│   └── AppModule.kt              ← Hilt DI modules
└── ui/
    ├── MainActivity.kt
    ├── navigation/
    │   ├── Routes.kt
    │   └── ClenchNavHost.kt
    ├── screens/
    │   ├── WelcomeScreen.kt
    │   ├── CreateWalletScreen.kt
    │   ├── ImportWalletScreen.kt
    │   ├── HomeScreen.kt
    │   ├── SendScreen.kt
    │   ├── ReceiveScreen.kt
    │   └── SettingsScreen.kt
    ├── viewmodel/
    │   ├── CreateWalletViewModel.kt
    │   ├── ImportWalletViewModel.kt
    │   ├── HomeViewModel.kt
    │   ├── SendViewModel.kt
    │   ├── ReceiveViewModel.kt
    │   └── SettingsViewModel.kt
    └── theme/
        └── Theme.kt              ← Dark theme, Clench orange (#FF6B00)
```

## Tech Stack

| Layer | Library |
|-------|---------|
| Bitcoin core | `bdk-android 1.1.0` (Block/Spiral) |
| UI | Jetpack Compose + Material3 |
| Navigation | Compose Navigation |
| DI | Hilt |
| Database | Room |
| Secure storage | Android Keystore + EncryptedSharedPreferences |
| QR codes | ZXing Android Embedded |
| Language | Kotlin |
| Min SDK | 26 (Android 8.0) |

## Current Status

✅ Scaffold complete — all screens, ViewModels, data layer, DI wired  
⏳ BDK implementation — `BdkBitcoinRepository` has TODO stubs for all Bitcoin operations  
⏳ QR code scanning — ZXing integrated, scanner launch needs wiring  
⏳ Electrum server persistence — Settings screen UI done, save to prefs needed  

## Next Steps

1. Implement `BdkBitcoinRepository` — wire up BDK calls (seed gen, sync, tx building)
2. Wire ZXing QR scanner in SendScreen
3. Persist Electrum server settings
4. Add proper launcher icon
5. Set up GitHub Actions CI

## Setup

```bash
# Open in Android Studio
# Or build from CLI:
./gradlew assembleDebug
```

Requires Android SDK 35, JDK 17+.
