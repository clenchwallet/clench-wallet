# Executed strict 16 KB acceptance

**PASS** for the documented cold-restart-then-background scenario, 180.14seconds,
2026-09-24 00:55UTC (2026-09-23 local). The unchanged public v0.3.33 APK was used,
not a rebuilt or instrumented artifact. Issue99 remains open for independent review.

- [Acceptance/source-equivalence receipt](acceptance.json)
- [Machine-readable runtime steps](v0.3.33-result.json)
- [Static companion result](v0.3.33-static.json)
- [Final strict environment](v0.3.33-environment.json)
- [Complete raw runtime archive](v0.3.33-runtime.zip): UI XML, process maps and
  APK-offset correlations, commands, database sample, logs, source snapshots,
  fixture, tool identifiers, image metadata and per-file SHA256SUMS.
- [Development-attempt summary](development-attempts.json) and
  [preserved failure evidence](development-attempts.zip).
- [Historical old-input receipt](historical-v0.3.32/receipt.json),
  [first crash](historical-v0.3.32/first-crash.txt),
  [import crash](historical-v0.3.32/import-crash.txt) and
  [new static negative control](historical-v0.3.32/static-negative.json).

## Exact executed command

Working directory: `/Users/pb/clench-strict-16kb-regression-20260923`.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
python3 -B scripts/verification/strict-16kb/run.py \
  --sdk /Users/pb/Library/Android/sdk \
  --apk /Users/pb/clench-jna-16kb-repair-20260923/evidence/333-public-download/clench-0.3.33-release.apk \
  --evidence /Users/pb/clench-strict-16kb-evidence-20260923/run07 \
  --port 5588
```

Exit0. Raw evidence directory is retained at the exact command path; the archive
makes the same observations available for independent review without Mac access.
The runner's source was uncommitted when execution began; captured exact runner
bytes (SHA256 `da617004659ae811faf547a3ed70d6a4a5c17486b665b80a3d42775285322792`)
match commit `2457ec7980b16b6426bcc46ae991684330d769f1`. Static source bytes and
fixture values were also compared to that commit. The later acceptance-evidence
commit does not modify these executable inputs. This is explicit source-equivalence
evidence, not a claim that the raw receipt began at a later commit.

APK SHA256: `a5fff7ce7d9306d6631c84f68dbaf6461f1208ff00886d6bbbc08aada41a936c`.
App source: `d472bde3970fa5062728507b15f8ccd1a35ce171`.
Emulator36.6.11, official `system-images;android-35;google_apis_ps16k;arm64-v8a`
revision5, API35/Android15, Hypervisor.Framework with `-accel on`, Pixel7,
2048MiB RAM, four cores, density240, no snapshots, disposable fresh AVD.
PAGE_SIZE16384; linker compatibility `false`; package-manager compatibility
`disabled=true`. Properties were rechecked before relaunches and at completion.
No fallback-enabled acceptance is claimed. The owned emulator stopped on exit.

## Observations and limits

All14 required steps passed. The created address remained
`tb1qq8ejzdr33a8prn9ppepnqs6tr7ec092unszrcx`; the public imported fixture matched
`tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl`. Each identity survived a force-stopped
fresh process and then same-process background/reopen. Both remained selectable.
Executable APK mappings correspond to JNA, BDK and SQLCipher. The DB sample has
an opaque header and plain SQLite rejects it. Successful native-backed UI paths,
known address and process reload are behavioral evidence, not call tracing or
independent database decryption. The DB sample is the main `clench.db` file, not
a complete transactional database backup including WAL/SHM.

**Separate observed limitation:** during development, immediate HOME/reopen after
fresh onboarding, before any cold restart, returned network choice (run03/run04).
Adding a launcher-visible checkpoint did not resolve it. No native crash occurred;
its cause was not proven. The maintained scenario cold-restarts first and explicitly
does not claim that initial foreground route passes. The unchanged production APK
was not patched to hide the finding. This limitation should remain visible during
independent review; broader wallet/UI repair is outside this PR.

Other retained development failures: run01 encountered an unavailable Python3.11
hash helper (replaced with streaming hashlib); run02 incorrectly expected `.so`
filenames in direct-APK mappings (corrected to ZIP offsets); run05 encountered a
port in use (aborted without touching its owner); run06 rejected an ambiguous
heading/button label (corrected to unique clickable ancestry). No failed attempt
was overwritten or silently retried inside the runner.

Only ARM64 runtime is established. The static companion also covers x86_64 JNA;
x86_64 runtime, 4KB control, hardware acceptance and SQLCipher provenance are not
new claims. Ten static tests and six harness contract tests pass. No mandatory
CI/release gate or production app/dependency file changed.

Raw archives contain **unfunded disposable test seeds**; never fund these wallets.
Keep the raw evidence for at least90days and through independent review, whichever
is longer. The checked-in receipts/history persist with the repository. See the
[parent README](../README.md) for the maintained trigger, owner and timeouts.
