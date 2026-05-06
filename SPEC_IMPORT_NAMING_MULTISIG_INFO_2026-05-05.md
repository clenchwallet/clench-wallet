# Import Naming and Multisig Info Gameplan

## Goal
Implement two Clench Wallet UX/data fixes: imported wallets should get useful names from export metadata or an explicit prompt, and imported multisig wallets should persist and display as multisig with useful policy/keystore details.

## Routing
- Keep all work in the main GPT-5.5 Clench lane.
- Do not delegate this to Cuthbert/MiniMax because this touches wallet import, descriptor handling, persistence, and wallet-info behavior.
- Preserve existing dirty local work unless it is directly part of this implementation.

## Scope
Allowed files:
- `/home/clawd/.openclaw/workspace/clench/android/app/src/main/java/net/clench/wallet/ui/viewmodel/ImportWalletViewModel.kt`
- `/home/clawd/.openclaw/workspace/clench/android/app/src/main/java/net/clench/wallet/ui/screens/ImportWalletScreen.kt`
- `/home/clawd/.openclaw/workspace/clench/android/app/src/main/java/net/clench/wallet/data/repository/BdkBitcoinRepository.kt`
- `/home/clawd/.openclaw/workspace/clench/android/app/src/main/java/net/clench/wallet/ui/viewmodel/WalletInfoViewModel.kt`
- `/home/clawd/.openclaw/workspace/clench/android/app/src/main/java/net/clench/wallet/ui/screens/WalletInfoScreen.kt`
- `/home/clawd/.openclaw/workspace/clench/android/app/src/test/java/net/clench/wallet/viewmodel/ImportWalletViewModelTest.kt`
- `/home/clawd/.openclaw/workspace/clench/android/app/src/test/java/net/clench/wallet/viewmodel/WalletInfoViewModelTest.kt`

Do not touch seed storage, signing flows, PSBT flows, Tor/networking, migrations, release config, or Gradle files for this change.

## Phase 1: Import Name Inference
1. In `ImportWalletViewModel.setInput(text: String)`, normalize the import payload as today, then infer a wallet name only when the current `UiState.walletName` is blank.
2. Add/keep a small parser that accepts:
   - text lines such as `Name: Company Vault`, `Wallet Name: Company Vault`, or `Label: Company Vault`
   - JSON fields such as `name`, `walletName`, or `label`
3. Trim quotes/whitespace, reject blank values, and cap inferred names at 64 characters.
4. Do not override a user-entered name.

Expected behavior:
- Pasting/scanning a hardware export with `Name: Company Vault` fills `walletName` with `Company Vault`.
- Pasting the same export after manually typing a name leaves the manual name unchanged.

## Phase 2: Blank Name Prompt
1. In `ImportWalletScreen`, before calling `viewModel.importWallet(onWalletImported)`, check `uiState.walletName.isBlank()`.
2. If blank, show a modal prompt with a `Wallet name` text field.
3. Confirm should be enabled only for non-blank text, then call `viewModel.setWalletName(trimmedName)` and continue the same import path.
4. Provide a default-name fallback button using the existing suggested-name logic.
5. Preserve the passphrase confirmation ordering: if the import is a seed phrase with a passphrase, the name prompt must happen before the passphrase warning dialog, and the import must only run after both gates are satisfied.

Expected behavior:
- Importing a wallet with no inferred or typed name asks for a name before adding it.
- Passphrase imports still show the existing warning and confirmation.

## Phase 3: Multisig Persistence
1. In `BdkBitcoinRepository.importWatchOnly(name, descriptor, deviceType)`, after descriptor normalization, determine whether the external descriptor is multisig.
2. Treat descriptors containing `multi(` or `sortedmulti(` as multisig.
3. Persist `WalletEntity.isMultisig = true` for such imports.
4. Return `WalletData.isMultisig = true` for such imports.
5. Keep single-sig watch-only behavior unchanged, including origin fingerprint/path extraction and hardware device preference.

Expected behavior:
- `wsh(sortedmulti(...))`, `wsh(multi(...))`, and `sh(wsh(sortedmulti(...)))` imports are stored as multisig.
- Single-sig `wpkh(...)`, `sh(wpkh(...))`, xpub, ypub, and zpub imports remain single-sig watch-only wallets.

## Phase 4: Wallet Info Multisig Details
1. In `WalletInfoViewModel.UiState`, expose:
   - original public receive descriptor
   - public change descriptor
   - parsed multisig policy info when present
2. Add/keep `MultisigPolicyInfo` with:
   - policy type
   - script type
   - threshold
   - total signers
   - descriptor
   - keystore list
3. Add/keep `MultisigKeystoreInfo` with:
   - display label
   - master fingerprint when available
   - derivation path when available
   - xpub/key text
4. Parse descriptor arguments with nesting-aware splitting so commas inside nested descriptor expressions or origin brackets do not break keystore parsing.
5. If `WalletEntity.isMultisig` is false but the descriptor parses as multisig, display it as multisig anyway to cover older imported rows.
6. Avoid calling single-sig account-xpub/derivation APIs for multisig wallets.

Expected behavior:
- Wallet Info shows policy type, script type, M-of-N threshold, descriptor copy action, and each keystore's fingerprint/path/xpub where available.
- Wallet Info remains usable for older multisig rows that were not persisted with `isMultisig = true`.

## Phase 5: UI Rendering
1. In `WalletInfoScreen`, render a multisig details section only when `uiState.multisigPolicy != null`.
2. Show compact descriptor/xpub text with expand/collapse affordances and copy actions.
3. Keep regular single-sig fields for non-multisig wallets.
4. Do not expose private descriptors or seed/private key material.

## Phase 6: Tests
Add or update focused unit tests:
- `ImportWalletViewModelTest.kt`
  - infers name from a `Name:` line
  - infers name from JSON `name`
  - does not override an existing wallet name
- `WalletInfoViewModelTest.kt`
  - parses native segwit sortedmulti descriptors
  - parses nested segwit multisig descriptors
  - extracts threshold/total signers
  - extracts each keystore fingerprint, derivation path, and xpub/key text
  - rejects invalid threshold/empty keystore cases

## Verification
Run from `/home/clawd/.openclaw/workspace/clench/android`:
1. `./gradlew testDebugUnitTest --tests net.clench.wallet.viewmodel.ImportWalletViewModelTest --tests net.clench.wallet.viewmodel.WalletInfoViewModelTest`
2. `./gradlew testDebugUnitTest`
3. If UI/resource edits are included and unit tests pass, run `./gradlew assembleDebug`

Manual smoke test if an Android device/emulator is available:
1. Import a hardware/multisig export containing `Name: Company Vault`; verify the name is prefilled.
2. Import a descriptor without a name; verify the name prompt appears before the wallet is added.
3. Open Wallet Info for the imported multisig wallet; verify M-of-N, script type, descriptor, and all cosigner keystores are visible.
4. Confirm no seed phrase, passphrase, xprv, or private descriptor appears in Wallet Info or exported descriptor data.

## Done Criteria
- Blank imports cannot silently create an unnamed wallet.
- Metadata-provided names are respected without overriding manual names.
- Imported multisig descriptors persist as multisig.
- Wallet Info gives enough multisig detail for policy review and cosigner coordination.
- Focused tests and the smallest meaningful Gradle verification pass, or any blocker is recorded with exact command output.
