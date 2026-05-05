# SPEC_CLENCH_BACKUP_TX_TOOLS_2026-05-04

## Goal
Add safe current-state backup/export/import plus transaction and multisig controls without storing seed phrases or private descriptors in the backup file.

## Scope
Modify only Clench Android source under `/home/clawd/.openclaw/workspace/clench/android`.

## Files
Create:
- `app/src/main/java/net/clench/wallet/data/backup/ClenchStateBackupManager.kt`

Modify:
- `app/src/main/java/net/clench/wallet/data/local/SettingsManager.kt`
- `app/src/main/java/net/clench/wallet/ui/viewmodel/SettingsViewModel.kt`
- `app/src/main/java/net/clench/wallet/ui/screens/SettingsScreen.kt`
- `app/src/main/java/net/clench/wallet/ui/navigation/ClenchNavHost.kt`
- `app/src/main/java/net/clench/wallet/ui/screens/TransactionDetailScreen.kt`
- `app/src/main/java/net/clench/wallet/ui/viewmodel/ReceiveViewModel.kt`
- `app/src/main/java/net/clench/wallet/ui/screens/ReceiveScreen.kt`
- `app/src/main/java/net/clench/wallet/ui/viewmodel/WalletInfoViewModel.kt`
- `app/src/main/java/net/clench/wallet/ui/screens/WalletInfoScreen.kt`
- `app/src/main/java/net/clench/wallet/ui/viewmodel/BackupViewModel.kt`
- `app/src/main/java/net/clench/wallet/ui/screens/BackupScreen.kt`
- `app/src/main/java/net/clench/wallet/ui/viewmodel/CreateMultisigViewModel.kt`

Already modified from previous user request and include in final app commit:
- `app/build.gradle.kts`
- `app/src/main/java/net/clench/wallet/ui/screens/HardwareWalletPsbtScreen.kt`

## Behavior
1. State backup export/import
- Export a JSON file with format `clench-state-backup`, version `1`.
- Include public wallet metadata, descriptors, wallet labels, UTXO labels/freeze state, and restorable non-secret settings.
- Exclude seed phrases, private descriptors, PINs, biometric secrets, SQLCipher DB key, and in-memory passphrases.
- On import, hot wallets restore as watch-only using public descriptors. The import summary must tell the user to re-enter the matching seed phrase later if they want hot-wallet signing restored.
- Preserve original wallet ids when no duplicate descriptor exists. If descriptor/network already exists, map imported labels/UTXO metadata to the existing wallet id and skip duplicate wallet creation.

2. RBF/CPFP
- Keep existing RBF bump-fee path.
- Fix CPFP/spend flow so transaction detail never assumes `vout=0`.
- Resolve actual unspent outpoints for the transaction id via `listUnspent()` and route exact `txid:vout` outpoints into Send.
- Show CPFP wording for unconfirmed received transactions.

3. Transaction inspector
- Transaction detail must show direction, amount, fee, confirmation state, txid, date, label, explorer, and a compact fee/CPFP/RBF action area.

4. Hardware receive-address verification
- Receive screen must show address index, derivation path, master fingerprint/device when available, and guidance to verify the exact address on the configured signer.
- This is manual verification metadata, not a fake device command.

5. Multisig backup/export
- WalletInfo/Backup must export wallet descriptors for multisig/watch-only coordination.
- Multisig descriptor export must include external and change descriptors.
- Creation validation should reject malformed origin paths and mixed-network signer keys where detectable.

## Verification
Run from `/home/clawd/.openclaw/workspace/clench/android`:
- `./gradlew lint testDebugUnitTest`
- `./gradlew bundleRelease`
- `rg -n "severity=\"Error\"" app/build/reports/lint-results-debug.xml` should return no lines.
- Inspect `git diff --stat` and stage only intended Android files.
