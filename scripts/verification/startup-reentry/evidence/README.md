# PR #102 focused runtime receipt — PASS

Candidate app source: `3bbddaaba5ddc553c71e510260e4abb444bb6dfc`.
One runtime execution (`run01`), all 14 stages PASS in **189.55 seconds**.
No candidate retry, substituted scenario, extra baseline run or application change.
The child commit containing this evidence changes only separate verification
scripts/evidence and documentation. The executed scenario/helper bytes are captured
in `runtime.zip` and hash-bound by `receipt.json`.

## Observed outcomes

| Scenario | Address | Process | Result |
| --- | --- | --- | --- |
| Fresh creation → HOME → normal launcher entry | `tb1qvpnhf3vl7qqq37myh5sa4gkhtra2jpy87wr5cr` | 2954 → 2954 | PASS |
| Same created wallet → HOME → original explicit MainActivity intent | same created address | 2954 → 2954 | PASS |
| Clean fresh import → HOME → original explicit intent | `tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl` | 4633 → 4633 | PASS |
| Same imported wallet → HOME → normal launcher entry | same imported address | 4633 → 4633 | PASS |
| Only afterward: one force-stop → launcher entry | same imported address | 4633 → 6174 | PASS |

For every foreground check, fresh UI XML directly shows the launcher after HOME;
Activity dumps establish STOPPED MainActivity and the unchanged Activity record/task
and PID on resumption. The app returns to its wallet route and receive address,
not network choice. The archive retains process `/proc/PID/stat`, before/background/
after Activity dumps, exact launch responses and native executable mapping evidence.
81 unique UI observations each prove prior path absence, nonempty parseable hierarchy
and retained removal/absence/dump/read diagnostics. Crash buffer is empty. The owned
emulator was stopped and removed.

The normal launcher path uses resolved ACTION_MAIN/CATEGORY_LAUNCHER and flags
`0x10200000` through Activity Manager. It does not simulate an icon tap. The explicit
path is the original `am start -W -n net.clench.wallet/.ui.MainActivity` invocation.
The import starts after `pm clear` of the owned disposable app, which removes the
created wallet. Neither fresh wallet is force-stopped before its foreground checks.
Cold persistence is checked on the imported wallet only. Wallet identity here means
observed wallet name plus derived receive address, not independently queried Room UUID.

## Artifact and environment

- Local **minified, non-debuggable release build**, no instrumentation, disposable
  test signing; application version metadata remains 0.3.33/333. This is the PR
  candidate, not the published v0.3.33 APK.
- APK SHA-256: `6fbe9fbceb868523691275407283b7a43de718f863088e58e4dba4a6601ee2e8`.
- Unsigned APK SHA-256: `dd9795f6c0c64550be986430ab7baa4f84e963c0ca6122e540457e77e3038bf2`.
- Disposable signer SHA-256: `5d2d91693c1d859b34b153be44a42eb04b4a1db6016883023768376477fc6c86`.
  Production signing was not accessed; test private key deleted after signing.
- Official `system-images;android-35;google_apis_ps16k;arm64-v8a`, revision 5;
  Android 15/API 35, ARM64, `PAGE_SIZE=16384`, Hypervisor.Framework acceleration
  required with `-accel on`; emulator 36.6.11, Pixel 7 configuration, density 240.
- `bionic.linker.16kb.app_compat.enabled=false` and
  `pm.16kb.app_compat.disabled=true`, rechecked before each entry.
- Offline Testnet, default no-lock: airplane mode, Wi-Fi/data disabled, no external
  IPv4/IPv6 routes. No hardware signer, physical device or page-size matrix.

`build-receipt.json` binds source, APK, disposable signer, JDK/Gradle identifiers,
reused BDK Maven input hashes and exact build/sign commands. Initial Gradle setup
failed because the new worktree lacked its local BDK artifact; that first log is
retained. Restoring the existing checksum-pinned artifact allowed compilation;
no dependency or build source was changed. The APK is retained locally at the path
in the executed command, not committed as a release asset.

## Reviewable files and bounds

`result.json`, `receipt.json`, `environment-environment.json`, `static.json`,
`candidate-ci.json` and build logs are available directly. `runtime.zip` includes
all raw runtime files, captured source and an inner `SHA256SUMS`; the outer manifest
binds the archive and summary files. Both manifests were verified. The script was
new/untracked while executed at the exact candidate checkout; captured bytes match
the subsequently committed scenario. Documentation/evidence added afterward does
not change execution. Reproduce with the command and prerequisites in `../README.md`.

Retained v0.3.33 `run03`/`run04` explicit-intent failures remain in
[`development-attempts.zip`](../../strict-16kb/evidence/development-attempts.zip). No baseline control was
needed to evaluate this candidate: both candidate entry paths passed and the old
explicit-path failure already exists. We do **not** claim a new reproduction of the
old release's normal launcher behavior. Existing #101 fixtures/evidence remain
byte-identical.

All existing candidate CI checks passed, including Android instrumentation; see
`candidate-ci.json` for exact workflow/job URLs. New head CI may run after this
evidence commit and must be assessed separately. PR #102 remains unmerged pending
independent runtime-evidence review. This receipt covers offline no-lock startup
reentry only, not broader lifecycle/security acceptance or release authorization.
