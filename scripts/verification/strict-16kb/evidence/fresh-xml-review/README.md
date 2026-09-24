# Fresh XML correction — independent delta review

Addresses [review5298838072](https://github.com/clenchwallet/clench-wallet/pull/101#pullrequestreview-5298838072).
**PASS**, one corrected bounded execution, 185.76seconds, all14 stages.
All78 observations have unique device paths, verified absence before dumping,
new nonempty parseable hierarchy XML, and retained stdout/stderr/exit diagnostics
for removal, absence, dump and read. A zero-exit/no-write dump cannot read stale XML.
Missing, empty or malformed observations fail the scenario without action retries.

- [Correction receipt](receipt.json)
- [Runtime steps and exact identifiers](result.json)
- [Final environment](environment.json)
- [Static companion](static.json)
- [Full raw evidence](runtime.zip), including captured runner/static sources,
  all78 XML observations and their diagnostics, maps, logs, database sample,
  command history, image metadata and per-file SHA256SUMS.

Executed once from a **clean checkout** of correction commit
`f8ae80d0e5e117a2466dc7e73393eae87b752cbd`:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
python3 -B scripts/verification/strict-16kb/run.py \
  --sdk /Users/pb/Library/Android/sdk \
  --apk /Users/pb/clench-jna-16kb-repair-20260923/evidence/333-public-download/clench-0.3.33-release.apk \
  --evidence /Users/pb/clench-strict-16kb-evidence-20260923/run08-fresh-xml \
  --port 5590
```

Exit0. Runner SHA256:
`54b18f8dc09ef0aa467b1c25f3b3ed47ff3a9930e5afbf0e6dd47b441c352e01`.
The evidence/documentation-only child changes no runner, static companion or fixture
bytes. Ten runner helper tests pass, including stale prior XML with zero-exit/no-write,
empty output, malformed output and successful fresh unique observations. The probes
use actual subprocess exit/output handling with a simulated device filesystem.

Input remains the unchanged public v0.3.33 APK, SHA256
`a5fff7ce7d9306d6631c84f68dbaf6461f1208ff00886d6bbbc08aada41a936c`, app source
`d472bde3970fa5062728507b15f8ccd1a35ce171`. Official Android15/API35 ARM64 Google APIs
16KB image revision5, Emulator36.6.11, accelerated with Hypervisor.Framework.
PAGE_SIZE16384; `bionic.linker.16kb.app_compat.enabled=false`;
`pm.16kb.app_compat.disabled=true`. No instrumented build or fallback-enabled pass.

The original run07 and development attempts are unchanged and their original
manifest still verifies. This new run supplies the explicit freshness proof missing
from the original receipt; it does not assert that run07 falsely passed.

Existing limitations remain: ARM64 runtime only; database encryption observations
are not independent decryption; the main DB sample is not a WAL-inclusive backup.
The documented cold-restart-then-background scenario remains unchanged. The separate
cause-unknown immediate post-onboarding routing failure in runs03/04 is preserved,
not fixed or counted as passing here. No app/dependency/release-gate changes, physical
acceptance or additional release work occurred. PR101 and issue99 stay open for
independent delta review. Retention and disposable unfunded-seed handling remain as
specified in the parent runner README.
