# Passphrase identity across Android process restart

This overlay-only acceptance fixture exercises the exact clean candidate HEAD.
It is deliberately **not** in the ordinary instrumentation source set: both
phases require explicit disposable-emulator and phase arguments. The public
BIP39 zero-entropy example and `" \t "` passphrase are synthetic fixtures, never
real wallet material. No server calls, transactions, production signing, or
production app data are involved.

Run this stage **before other stages install Clench debug packages**, or after
those stages remove their own disposable packages:

```bash
ANDROID_SDK_ROOT=/path/to/android/sdk \
ADB_SERIAL=emulator-5554 CLENCH_PASSPHRASE_RESTART_DISPOSABLE=YES \
  scripts/verification/run-passphrase-process-restart.sh
```

The harness requires a booted `emulator-N` with `ro.kernel.qemu=1`, explicit
consent, no pre-existing Clench debug app/test packages, clean HEAD, and no Git
source-substitution metadata. It never clears or replaces a pre-existing app.
It builds an isolated checkout with only the fixture source overlaid; the
prepared native Maven artifact is copied if available and checked by strict
Gradle dependency verification, otherwise the pinned preparation script runs.
A separate temporary Android user home isolates the generated debug signer.
Only the exact debug app and matching instrumentation package are installed.
Both owned packages are removed after the device phases. The source checkout
is unchanged; isolated build worktrees remain for diagnosis.

## What the phases prove

1. `write` imports a nonempty-whitespace passphrase wallet and an empty-passphrase
   control into a dedicated encrypted Room database, using the real repository,
   Android encrypted preferences/Keystore, and pinned BDK native library. The
   whitespace wallet is locked and has no persisted private descriptors or BDK
   disk graph. The receipt records only public descriptors, addresses,
   fingerprints, wallet IDs, and a per-process identifier. Live objects are not
   deliberately closed/reconstructed by the fixture.
2. The host runs `am force-stop` and records that `pidof` returns no app process;
   it does **not** clear data or reinstall between phases. Instrumentation may
   already have ended its writer process. This is restart evidence, not a claim
   to power-loss recovery or killing a particular native instruction.
3. `verify` must run in a new OS process with a different per-process identifier.
   It checks unchanged Room identity/flags, no cached unlock/private descriptors/
   BDK disk graph, and the same native address/fingerprint after explicitly
   unlocking with the exact whitespace passphrase. The existing empty-passphrase
   control remains empty-passphrase; it is never reinterpreted. Locking again
   leaves the passphrase wallet without a disk graph.

Expected instrumentation markers, each exactly once in its phase:

- `CLENCH_PASSPHRASE_RESTART_WRITE_PASS`
- `CLENCH_PASSPHRASE_RESTART_VERIFY_PASS`

Evidence: `build/reports/passphrase-process-restart/` contains the build log,
phase transcripts, public receipt, force-stop/no-process proof, and `result.json`
with exact source/fixture/APK/certificate hashes and `tests_passed: 2`. A skipped,
wrong-class, incomplete, repeated, or failed execution does not produce success.
The evidence directory must be empty on entry. Do not upload the separate
worktree or Android user-home directory containing the disposable debug key.

Device-free parser checks:

```bash
python3 -B scripts/verification/test-passphrase-process-restart.py
```

Compilation and parser checks alone do not satisfy the restart acceptance gate.
This fixture also does not provide production-signed, physical-device, hardware
wallet, OEM-wide, or broader-audit sign-off.
