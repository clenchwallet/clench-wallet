# Maintained strict 16 KB regression — issue #99

This directory maintains the external Android runtime runner and its static
companion for the known packaged JNA ELF/RELRO regression. The static companion
**does not execute Android and cannot establish runtime acceptance on its own**. It is
not a dependency change, a new release gate or new hardware-signing acceptance.

## Static companion

Run with Python 3, without additional packages:

```sh
python3 -B scripts/verification/test-jna-16kb.py
python3 -B scripts/verification/check-jna-16kb.py \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --output build/reports/strict-16kb/static.json
```

`--aar path/to/jna.aar` can independently examine an input AAR, but does not
check APK packaging. APK mode requires uncompressed, 16-KB-ZIP-aligned native
members (the existing Clench packaging policy), plus both shipped 64-bit JNA
libraries. For those two JNA libraries it verifies little-endian ELF64 machine
identity, LOAD alignment/congruence, a retained GNU_RELRO segment, and its
16-KB-aligned end within a writable LOAD. It deliberately does not extrapolate
JNA's internal ELF check to unrelated libraries or 32-bit runtime support.
Archive and payload hashes and inspected headers are included in its receipt.

The historical 5.14.0 AAR fails specifically because ARM64 RELRO ends inside
a 16-KB page; the repaired 5.19.1 AAR passes. This is a static negative control,
not a newly executed reproduction of the historic Android crash. The Python
self-tests cover both architectures, boundary failure, absent RELRO, missing
ABI, invalid/truncated metadata, duplicate ZIP entries, and ZIP misalignment.

Latest corrected execution: [fresh-XML delta acceptance](evidence/fresh-xml-review/README.md).
Original execution and first-failure evidence remain preserved.

## Maintained external runtime runner

`run.py` drives the **unchanged, signed, non-debuggable public APK**, using adb and
UIAutomator dumps. It creates a new uniquely named disposable AVD, verifies that
it owns the connected emulator, and destroys only that AVD on exit. There is no
instrumentation APK, test hook, app-source edit, production key, or physical-device
interaction. The existing static companion runs before Android acceptance.

### Prerequisites and repeatable command

- macOS ARM64 with Hypervisor.Framework, or Linux x86_64 with working KVM; the
  image ISA must match the host. Only the ARM64 Mac path has been executed for
  this contribution; x86_64 is a supported selection, not claimed runtime evidence.
- Python 3.9+, Git, JDK 21 available to Android tools (`JAVA_HOME` if necessary).
- Official Android SDK command-line tools, platform-tools, emulator, Build Tools
  35.0.0, and the rootable Google APIs 16 KB image. Install through `sdkmanager`:

```sh
sdkmanager 'platform-tools' 'emulator' 'build-tools;35.0.0' \
  'system-images;android-35;google_apis_ps16k;arm64-v8a'
# On x86_64 use the official x86_64 image instead.
```

Download the public input and run from the repository checkout containing the
source commit in `v0.3.33.json` (fetch `v0.3.33` if using a shallow checkout):

```sh
curl --fail --location --output /tmp/clench-0.3.33-release.apk \
  https://github.com/clenchwallet/clench-wallet/releases/download/v0.3.33/clench-0.3.33-release.apk
python3 -B scripts/verification/strict-16kb/run.py \
  --sdk "$ANDROID_HOME" \
  --apk /tmp/clench-0.3.33-release.apk \
  --evidence /tmp/clench-strict16-run-001
```

The evidence directory **must not exist**. Defaults: official API35 image matching
host ABI, port5584, 1,800-second overall execution budget. `--image`, `--port`,
`--timeout` (60–1800), `--build-tools` and `--fixture` are explicit overrides.
The runner neither downloads an image nor silently falls back to another one.
It checks acceleration capability and launches with `-accel on`; inability to
accelerate is a failure. Nothing must be running on the chosen emulator port pair.
Only disposable emulator state is cleared. The runner never issues `adb -d`,
selects the first connected device, or mutates another existing AVD.

A new native candidate can use a separately reviewed fixture JSON with its exact
APK hash, source commit, package version and test/public certificate fingerprint.
Do not overwrite the established release fixture or supply production signing
credentials. Fixture/source association is pinned review input supported by the
linked independent public rebuild; the runner does not itself rebuild the app.

### Assertions and limits

The runner fails nonzero on wrong hashes/signature/version, a debuggable APK,
static failure, unsupported image/ABI, unavailable acceleration, non-16384 page
size, API below35, enabled/unknown fallback, external routes, missing UI steps,
ambiguous controls, app death, wrong address, timeout or missing final evidence.
Both `bionic.linker.16kb.app_compat.enabled=false` and
`pm.16kb.app_compat.disabled=true` are set and rechecked before process relaunches
and at the end. A fallback-enabled run cannot pass. There is no emulator restart
inside a run; every invocation cold-boots a new AVD and re-establishes the settings.

It selects Testnet, Offline mode and the default no-lock choice, generates a
12-word throwaway wallet, answers all four seed-verification prompts, and reads
its receive address. It separately imports the public zero-entropy BIP39 vector
and asserts the known Testnet address. For **each wallet**, it asserts the wallet
name and exact address after force-stop/relaunch (old PID absent, new PID different), then after
HOME/reopen of the persisted wallet (same PID). Finally both wallets must remain selectable,
and the original wallet must still derive its original address.

Direct observations include UI XML, PID changes, identical installed APK bytes,
16384-byte pages, disabled fallback, empty external route tables, empty crash
buffer, executable mappings at the packaged JNA/BDK/SQLCipher APK offsets, an
on-disk `clench.db`, its hash/opaque header and rejection by plain SQLite. Android
maps uncompressed `.so` members directly from `base.apk`; the receipt correlates
`/proc/PID/maps` offsets to the static ZIP inventory instead of requiring library
filenames in maps. The exercised production paths and known derived address
support actual JNA/BDK execution; this is not function-call tracing. Wallet
identity means displayed name **and derived address**, not a decrypted internal
Room UUID. SQLCipher mapping + opaque database + successful process reload
supports encrypted persistence; this is not independent DB decryption, schema
verification, a cryptographic audit, or SQLCipher provenance work.

Each observation uses a unique device XML path, removes any prior file and verifies
absence before dumping. A zero dump exit alone cannot pass: the new file must be
read successfully, be nonempty, parse as XML and contain a UI hierarchy with nodes.
Dump/read/removal/absence stdout, stderr and exit (or timeout) metadata are retained,
including empty or malformed XML and a per-observation failure receipt.

UI waits poll observations only; they do not repeat state-changing actions or
relaunch after a crash. Commands have individual deadlines within the overall
budget. Failure stops the scenario and retains `first-failure.json`, crash/main
logs and available tombstones, with a separate bounded collection/cleanup budget
(up to roughly90seconds). `result.json` cannot pass unless every required step
passes in order. The emulator process is stopped on normal failures, timeout,
SIGTERM or Ctrl-C; SIGKILL/host loss cannot guarantee cleanup. Do not interpret an
incomplete receipt as PASS. A new invocation never overwrites an earlier failure.

The UI contract is deliberately the English release UI at the Pixel7 profile with density240 to expose the
mnemonic picker.
A deliberate UI change may require a small reviewed selector update. Automatic
retries must not hide a native crash. Application/network activity is confined to
the disposable emulator with Wi-Fi/mobile links disabled, airplane mode on,
external interfaces down, and empty IPv4/IPv6 main routes; the host may remain online.

### Observed fresh-onboarding routing limitation

During harness development, background/reopen **before the first cold restart**
after fresh onboarding returned the network-choice screen even with a launcher
visibility checkpoint. The created address was valid and the crash buffer was
empty. Attempts run03/run04 are retained; no app fix or automatic onboarding
retry is included here. The maintained scenario deliberately verifies cold
persistence first, then background/reopen of that persisted wallet. This satisfies
the native regression lifecycle assertions but does **not** claim the immediate
post-onboarding foreground route works. Independent review should keep this
separate UI/lifecycle observation visible; the cause is not proven here.

### Trigger, owner, time limit and evidence retention

- **Trigger:** explicit command before relevant JNA/BDK/native packaging or Android
  toolchain updates, and when investigating a 16 KB report. This is maintained
  manual-trigger automation, not a new mandatory CI or release gate.
- **Owner:** Clench maintainers (`@clenchwallet`), who triage the first failure and
  maintain the pinned input and English UI selectors. Gate-policy changes need
  separate review. Leave issue99 open until independent review.
- **Budget:** 30minutes execution, bounded failure collection/cleanup as above.
- **Retention:** keep raw evidence and SHA256SUMS for at least90days and through
  independent review, whichever is longer. Keep checked-in acceptance receipts
  and historical first-crash evidence with repository history. If invoking from
  an optional external CI worker, upload the whole evidence directory on success
  **or failure** with at least90-day retention; no CI availability is claimed here.
- **Safety:** raw XML includes a newly generated **unfunded disposable seed** and
  public fixture words; the database belongs only to these test wallets. Never
  fund either wallet or run the script on real wallet data. No credentials are
  needed. Keep raw evidence in the designated test-artifact store.

### Harness checks and old-versus-fixed evidence

```sh
python3 -B scripts/verification/test-jna-16kb.py
python3 -B scripts/verification/strict-16kb/test_runner.py
```

Harness tests reject wrong page size, API, emulator identity, ABI and fallback;
missing/ambiguous address observations; missing/reordered/failed steps; reused
evidence directories and command/overall timeouts. These are harness tests, not
substitutes for the real run. Freshness tests also cover zero-exit/no-write with
stale prior XML, empty output, malformed output and successful unique snapshots.

The old published v0.3.32 APK is a static negative control and has retained strict
Android runtime failures at JNA `JNI_OnLoad` from the original baseline. Reusing
those first-crash receipts avoids recreating known crashes. The repaired public
v0.3.33 APK is run through this maintained automation. See the checked-in
[acceptance record](evidence/README.md) for exact runner/input identifiers, the
executed command, raw evidence locations and any setup failures. Neither the
historical 4 KB control nor hardware acceptance is repeated for this task.

References: [issue #99](https://github.com/clenchwallet/clench-wallet/issues/99),
[Android page-size guidance](https://developer.android.com/guide/practices/page-sizes),
and [the shipped repair's evidence](../../../docs/security/jna-16kb-compatibility.md).
