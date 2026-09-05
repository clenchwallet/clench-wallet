# SQLCipher encrypted-database upgrade fixture

This is a test-only source overlay, not an application feature or a real-wallet
backup/import path. The harness builds pinned producer commit
`3a96b6da8bcbd33d1ecc56cf9d49e1d66cd98609` with SQLCipher 4.15.0 and the exact
clean candidate HEAD with 4.17.0. It changes only instrumentation source in
separate worktrees, retains strict dependency verification and existing locks,
and gives both APK pairs a newly generated disposable debug certificate.

Run only on a new dedicated emulator with neither Clench debug package installed:

```bash
ADB_SERIAL=emulator-5554 CLENCH_SQLCIPHER_UPGRADE_DISPOSABLE=YES \
  python3 -B scripts/verification/run-sqlcipher-upgrade.py
```

The writer creates a private, encrypted Room fixture using a public synthetic
key. It records a committed main-database row and a second committed WAL row,
then freezes a DB/WAL snapshot while a later update is uncommitted. That update
is rolled back after the snapshot; this is crash-style snapshot recovery, not
a claim of killing a production process at a precisely measured instruction.

After `adb install -r`, the new reader first opens the original writer database
directly. It then restores the frozen fixture snapshot into the same dedicated
test database, rejects wrong-key/corrupt copies without mutation, validates Room
schema and committed rows, and checkpoints. A separate instrumented process
reopens and revalidates. Candidate runtime must report SQLCipher 4.17.0 and
SQLite 3.53.3. No public broadcast, real wallet/key, release credential, schema
destructive-migration fallback, or dependency-verification exception is used.

Evidence goes under `build/reports/sqlcipher-inplace-upgrade`: source IDs,
fixture/APK/certificate hashes, three actual instrumentation results and build
logs. Skipped, incomplete, wrong-class and failed executions are rejected by
the result parser; four finite parser tests run without an emulator. Owned app
packages are removed after the device phase. Isolated build worktrees are
retained for diagnosis and disappear with the hosted runner; their disposable
debug keystore is never uploaded in the evidence artifact.

Compilation alone is not upgrade evidence. This fixture also does not reconcile
the Android vendor tag's stale SQLCipher gitlink or independently reproduce the
native binary; that source-association gap remains in the native baseline.
