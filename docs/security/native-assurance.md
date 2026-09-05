# Native dependency assurance

Status: partial remediation of SC-02, **not a native vulnerability clearance**.

The Maven CycloneDX inventory remains useful for resolved Java/Kotlin coordinates,
but does not inventory source dependencies embedded in native binaries. Its zero
active advisory count must not be generalized to those dependencies.

## Repeatable identity check

From the repository root, with the normal supported SDK/JDK:

```bash
./gradlew --no-daemon --dependency-verification=strict \
  -I scripts/verification/native-artifacts.init.gradle :app:exportNativeRuntimeArtifacts
python3 -B scripts/release/inventory-native-artifacts.py \
  --baseline docs/security/native-dependencies.json \
  --output build/reports/native-dependency-inventory.json
```

The locally generated Gradle manifest is the trusted resolution input; do not
substitute an externally supplied manifest. The scanner checks that its module
set matches the committed runtime lock, verifies resolved archive hashes against
Gradle's existing pins, and hashes native payloads without loading them. It scans
whole AAR/JAR files, including ABIs/platforms not necessarily packaged into the
APK. These records are **not** an APK entry manifest. ZIP parsing is bounded for
native payloads; normal Gradle strict verification precedes the scan.

Android CI runs finite regression tests, checks the native baseline and retains
the generated inventory. A changed native owner/archive/payload fails the check
until the baseline receives an explicit source/advisory review. A passing check
only establishes no drift from this baseline, whose gaps remain visible.

## Baseline and outstanding work

The machine-readable [baseline](native-dependencies.json) pins five owners,
their archive and native payload SHA-256 values, source evidence URLs/hashes, and
explicit incomplete review status:

- CameraX camera-core 1.6.1: image-processing and surface JNI libraries. The
  official release-note commit-range endpoint and CMake hash are recorded. It
  links the vendored external:libyuv project; vendored/advisory review remains open.
- AndroidX graphics-path 1.0.1: path JNI library. The official release-note
  commit-range endpoint and CMake source hash are recorded; no third-party link
  target is declared there. Vendored source ancestry/advisory review remains open.
- JNA 5.14.0: jnidispatch. Vendor tag resolved to an immutable commit; build file
  evidence recorded. Vendored libffi declares 3.4.4; its exact source tree and
  manifest/build hashes are recorded. That tree exactly matches upstream libffi
  v3.4.4. The dated OSV commit query returned no matches; this is not complete C
  advisory coverage or source-to-binary proof.
- BDK Android 3.0.0: bdkffi. The vendor's immutable release commit, Cargo manifest,
  Cargo lockfile and Android build file are identified. The lock contains 199
  package candidates, including build/dev/conditional entries; this is not a
  claim that all are shipped. Android feature/target filtering, Rust sys-crate C
  contents, and applicable advisories still require review.
- SQLCipher Android 4.17.0 candidate: sqlcipher JNI. Archive hashes and vendor
  source associations are recorded. The Android tag points to older SQLCipher
  source than the separate SQLCipher release tag and binary version strings;
  the discrepancy is explicit, not treated as reproducibility. Cross-version
  database validation passed on the disposable hosted emulator; remaining native
  advisory and source-correspondence review are required.

Source-tag association is not independent binary reproducibility. Do not call
an upstream advisory exploitable from Clench without checking affected version,
vendor patches, compiled options, application reachability and attack prerequisites.
Conversely, missing source/build evidence is not evidence that a package is safe.

SC-02 stays open until each owner has a pinned transitive source inventory,
dated authoritative advisory results and reviewed dispositions, and those inputs
are maintained by the release evidence pipeline. The current CI report supplements,
but does not replace or silently change, the signed release asset contract.

The [dated Cargo candidate review](native-cargo-review.md) records four advisory
groups found by the new repeatable source-bound scan. No suppressions or native
clearance have been issued.

## Pre-sign release enforcement

The no-secrets `build_unsigned` job now repeats the strict native identity
check and runs the live Cargo advisory query. Signing depends transitively on
this job. Its current matches fail the gate; an upload with `always()` retains
the reports but never converts failure into success. No suppression is added.
Four workflow mutation tests reject removing, skipping, ignoring or masking the
live gate. This is a supplement to the Maven SBOM, not a claim of complete C
coverage or a change to the thirteen public release assets. No release was run.

## SQLCipher update disposition and acceptance plan

The [4.17.0 vendor notes](https://www.zetetic.net/blog/2026/07/08/sqlcipher-4.17.0-release/)
identify SQLite 3.53.3 FTS5 fixes and LibTomCrypt provider CSPRNG/error-handling
changes. The candidate branch selects 4.17.0 with unchanged AndroidX SQLite 2.6.2; the
published v0.3.28 release remains unchanged. The candidate is not called
validated merely by this review.
A 2026-09-05 source review found fixed application migration SQL and no FTS
queries, `sqlcipher_export`, extension loading or explicit defensive-mode
disable in Clench's database/backup/DI paths. The two cited FTS CVEs require
crafted database contents, FTS5 operations and disabled defensive mode per the
vendor; those app paths were not established. This narrows the FTS applicability
only. It does not dispose of the provider changes or all native advisories.

A deliberate update must establish Android compileSdk/Room compatibility from
the candidate AAR metadata, preserve existing locks/checksum verification,
refresh this native source inventory, and execute these acceptance cases:

- A database written by the existing production SQLCipher version opens with
  the candidate library using the same fixture key and retains wallet rows.
- WAL checkpoint/reopen and interrupted-upgrade recovery preserve committed
  synthetic data; no automatic destructive migration is allowed.
- A wrong key and corrupt database are rejected without modifying the input.
- Room schema validation, BDK wallet persistence, and reinstall/upgrade using
  the same debug fixture signer pass on a disposable emulator.

Current same-version tests are not proof of a cross-version upgrade. If a
candidate requires compileSdk 37, that must be an explicit compatible SDK
migration, not an unchecked Dependabot merge or a verification exception.

### Candidate evidence and source discrepancy

The 4.17 AAR, POM and module hashes matched Maven Central published SHA-256
sidecars. The AAR declares minCompileSdk 1 and the module requires the already
locked AndroidX SQLite 2.6.2. No SDK 37 migration or verification bypass was
needed for strict local resolution. The 475 JVM tests passed with zero
failures/errors/skips, and debug/test APK plus lint assembly passed.

Android tag commit `ae57a61052d8c41ce35cd48319b2f6f20f4de6bf` records SQLCipher
gitlink `e2a6040f2ae5cfff2b3e08eb3320007d93cdf3fc` (a 4.16-era source with
SQLite VERSION 3.53.1). The separate SQLCipher v4.17.0 source commit
`810db22f575ee7cf94ea96a3e91622b5fcece3dc` declares 3.53.3; the verified AAR's
x86_64/armeabi-v7a libraries contain 3.53.3 and 4.17.0 strings. Other ABI string
absence is not proof of another version. A vendor build manifest is needed to
reconcile the source association; no source-to-binary reproducibility claim
is made. The selected Android runtime is required to report 3.53.3 by the
[old-writer/new-reader fixture](../../scripts/verification/sqlcipher-upgrade/README.md).
Hosted run [33960440165](https://github.com/clenchwallet/clench-wallet/actions/runs/33960440165)
at `b099db055a62f39d1c71b60ae1c1a949bf553242` passed all three actual
instrumentation phases: the pinned 4.15 writer, 4.17 reader, and new-process
reopen. The result records producer `3a96b6da8bcbd33d1ecc56cf9d49e1d66cd98609`
and the exact candidate commit. The runtime version assertions, encrypted Room
rows, frozen WAL recovery, wrong-key/corruption non-mutation and checkpoint/reopen
checks therefore executed; they are not inferred from compilation.

The same run passed the additional 19 Android regression cases, including all
five real-system-authentication UI cases. This is API 35 x86_64 disposable-emulator
evidence, not physical/OEM-wide testing, all-ABI version proof, or resolution of
the vendor source discrepancy. The three upgrade phases remain separate from
the 19-case count.
