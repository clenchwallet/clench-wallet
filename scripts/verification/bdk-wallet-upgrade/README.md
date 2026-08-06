# BDK 2 to BDK 3 persisted-wallet upgrade gate

This harness proves that an exact BDK Android 2.3.1 Clench debug APK can create a
production-shaped, unfunded Testnet3 wallet and that an exact BDK Android 3.0.0 APK can replace
it with `adb install -r`, load the same Room row and BDK SQLite file, and preserve public wallet
identity across two separate BDK 3 instrumentation processes.

The default source pair is:

- protected BDK 2 parent: `cc2d36132bf6fb4162093e2361f414f63297e1d4`
- BDK 3 consumer: the clean harness worktree's `HEAD`, resolved once to an immutable commit SHA

Override either with `CLENCH_BDK2_COMMIT` or `CLENCH_BDK3_COMMIT` when validating the exact head
of a rebased replacement PR. The script verifies the resolved BDK versions before building and
records both resolved commit IDs in the result manifest. `HEAD` is used because a commit cannot
contain its own hash. The consumer must be a Git descendant of the exact protected producer; the
gate refuses an upgrade candidate that omits protected-main history.

## What the gate does

1. Creates detached worktrees at the exact producer and consumer commits.
2. Overlays one version-specific instrumentation source into each worktree. No tracked app source
   changes, and neither test fixture enters the application APK.
3. Builds both debug app/test APK pairs under strict Gradle dependency verification using one
   newly generated, disposable debug certificate.
4. Refuses to operate unless `ADB_SERIAL` points to an emulator (`ro.kernel.qemu=1`) and the caller
   explicitly authorizes clearing only `net.clench.wallet.debug`. It also refuses to replace a
   pre-existing instrumentation test package.
5. Installs the BDK 2 app, clears the dedicated debug package, and runs the seeder.
6. The seeder derives BIP-84 descriptors from the public BIP-39 `abandon ... about` test vector,
   converts them to public descriptors, destroys every secret native wrapper, and only then creates
   the wallet. It writes:
   - a real production-named `wallet_<uuid>.db` BDK SQLite file;
   - the matching public, watch-only Room row so cold-start orphan cleanup follows the production
     path; and
   - app-private public comparison evidence.
7. Removes the test APK containing the public test mnemonic, then installs the BDK 3 app with
   `adb install -r` and verifies that Android preserved the database and evidence files.
8. Loads and validates the wallet with BDK 3, force-stops the target/test packages, then loads and
   validates it again in a different process (PID inequality is required).
9. Requires exact identity for Room/native public descriptors, Testnet3 network, three receive
   addresses, two change addresses, derivation indices, zero balance, empty history, and empty UTXO
   set. Any migration staged by the first BDK 3 load is persisted before the second restart.
10. Exports only hashes, counts, versions, commit IDs, APK hashes, the disposable certificate hash,
     and emulator metadata. It never exports the raw SQLite file, mnemonic, descriptors, or
     addresses. Both dedicated debug packages are uninstalled on exit.

## Run

Start a dedicated emulator separately, then run from a clean harness worktree:

```bash
export ADB_SERIAL=emulator-5554
export CLENCH_BDK_UPGRADE_ALLOW_EMULATOR_RESET=YES
scripts/verification/run-bdk2-bdk3-inplace-upgrade.sh
```

Optional controls:

```bash
export CLENCH_BDK2_COMMIT=<exact-bdk2-commit>
export CLENCH_BDK3_COMMIT=<exact-bdk3-candidate-commit>
export CLENCH_BDK_UPGRADE_EVIDENCE_DIR=<new-or-empty-directory>
export CLENCH_BDK_UPGRADE_OFFLINE=1
export CLENCH_BDK_UPGRADE_MAX_WORKERS=2
```

The default evidence directory is `build/reports/bdk2-bdk3-inplace-upgrade/`. The safe result is
`bdk2-to-bdk3-result.properties`; `gate-manifest.properties` binds it to both exact commits, all
four APK hashes, the safe-result hash, and the disposable signer.

## Security boundaries

- The mnemonic is an intentionally public, non-production BIP-39 test vector and exists only in
  the BDK 2 instrumentation APK. The script removes that APK before installing BDK 3.
- Secret descriptor objects and the mnemonic/secret-key native wrappers are destroyed before the
  persisted wallet is constructed. Room, SQLite, and cross-phase evidence contain public state
  only.
- The script refuses physical devices, production package IDs, and pre-existing target/test
  packages. Destructive Android operations are limited to its freshly installed `.debug` packages
  after explicit opt-in.
- A fresh 30-day debug certificate is generated outside both source trees. No production release
  key, keystore properties, GitHub secret, or signing control is read or modified.
- No Bitcoin server, faucet, testnet peer, broadcast endpoint, or funded transaction is used.
- Assertion failures name only the failed field; they do not interpolate descriptors, addresses,
  or evidence values into instrumentation logs.

## Remaining limitations

- The fixture has deliberately unsynced, zero-value local state, so this proves identity for zero
  balance and empty transaction/UTXO history without consulting any chain service. It does **not**
  assert anything about activity at the public test-vector addresses, or prove migration of a
  non-empty transaction graph, checkpoints, labels, frozen outputs, or spend state. A separate
  synthetic-chain fixture is needed for that without relying on live funds.
- This is a debug-package Android upgrade. Room is unencrypted in debug builds, so this does not
  replace the existing SQLCipher release/storage-corruption gates.
- It proves BDK persistence on the emulator ABI used for the run. It does not replace a real
  arm64/JNI smoke test, a safely authorized Testnet3 lifecycle rerun, or physical wallet/NFC/QR
  evidence.
- The harness does not test downgrade compatibility, BDK versions earlier than 2.3.1, or recovery
  from a deliberately corrupt BDK database.
