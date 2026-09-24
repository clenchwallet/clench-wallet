# Focused startup reentry acceptance (#102)

External automation for the navigation-only candidate. This directory is separate
from the completed, pinned strict-16-KB regression: its runner, fixture and evidence
are unchanged. `run.py` reuses that runner's fresh-XML UI, identity/native mapping,
command diagnostics and disposable-emulator cleanup helpers. Artifact/environment
setup is copied locally so scenario ordering does not mutate the completed runner.

## Command and prerequisites

Use Python 3.9+, JDK 21, Android SDK build-tools 35.0.0, platform tools, command-line
tools and an accelerated official Android 35 Google APIs ps16k ARM64 image on an
ARM64 Mac. The existing installed image is reused; each run owns a fresh temporary
AVD. No production key, hardware device or instrumented/debug build is used.

Build the exact candidate `3bbddaaba5ddc553c71e510260e4abb444bb6dfc` with
`JAVA_HOME=... ANDROID_HOME=... ./gradlew :app:assembleRelease --console=plain`.
The existing checksum-pinned local BDK Maven input is required by the build; its
absence aborts Gradle before compilation. Sign the resulting unsigned release APK
with a disposable key using `apksigner`. `candidate.json` identifies the actually
executed test-signed artifact, not the published v0.3.33 APK. A future locally
signed rebuild needs its own reviewed hash/signer fixture and fresh evidence path.

Executed command (absolute SDK/APK/evidence paths are host-specific):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
python3 -B scripts/verification/startup-reentry/run.py \
  --sdk /Users/pb/Library/Android/sdk \
  --apk /Users/pb/clench-startup-reentry-evidence-20260924/startup-reentry-3bbddaa-test-release.apk \
  --fixture scripts/verification/startup-reentry/candidate.json \
  --evidence /Users/pb/clench-startup-reentry-evidence-20260924/run01 \
  --port 5590
```

## Ordered assertions

1. Verify APK hash, disposable signer, non-debuggable release package, installed
   APK bytes, native static companion and accelerated strict-16-KB environment.
2. Fresh offline Testnet onboarding, create a twelve-word disposable wallet,
   answer four verification questions, record wallet name and derived address.
3. Before any cold restart: HOME, observe Google launcher and STOPPED MainActivity,
   launch MAIN/CATEGORY_LAUNCHER with normal NEW_TASK|RESET_TASK_IF_NEEDED flags,
   assert wallet route, same name/address, PID, Activity record and task.
4. HOME/reopen using the original explicit `am start -W -n` MainActivity command;
   assert the same invariants, still before any cold restart.
5. Clear only this disposable app's state. Fresh offline onboarding and public
   mnemonic import; assert its known Testnet address. Exercise immediate explicit
   reentry first and launcher reentry second, with the same assertions.
6. Only after all four foreground checks, perform one `am force-stop`; reopen via
   launcher entry, assert a new PID and the imported wallet/address persistence.

The launcher check uses resolved ACTION_MAIN/CATEGORY_LAUNCHER and launcher flags
via Activity Manager, rather than a physical icon tap. Full launch responses and
before/background/after Activity dumps retain the intent, task and process facts.
The clean import uses `pm clear` (which terminates the prior disposable process);
there is no cold restart of either fresh wallet before its foreground assertions.
Cold persistence is asserted for the imported wallet; the generated wallet is
intentionally cleared after its foreground checks.

Fail on wrong inputs/environment, missing steps, fresh XML failure, timeout, route
or identity mismatch. Network choice on reentry fails immediately. No launch/action
retry or cold-restart-first fallback. Default bound: 900 seconds plus up to 110
seconds for failure capture/cleanup. Each output directory must be new. UI XML and
command streams, Activity dumps, maps, first-failure screenshot/logs and manifests
are retained. Mnemonics are public/disposable and must never be funded.

Owner: Clench maintainers. Trigger: this PR's bounded acceptance and future reviewed
startup-owner changes; manual/nonblocking, with no release gate modification.
Retain receipts and archives at least 90 days and through independent runtime review.
See `evidence/README.md` for actual results and limitations.
