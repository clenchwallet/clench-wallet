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
  manifest/build hashes are recorded. Local patches/native advisories remain open.
- BDK Android 3.0.0: bdkffi. The vendor's immutable release commit, Cargo manifest,
  Cargo lockfile and Android build file are identified. The lock contains 199
  package candidates, including build/dev/conditional entries; this is not a
  claim that all are shipped. Android feature/target filtering, Rust sys-crate C
  contents, and applicable advisories still require review.
- SQLCipher Android 4.15.0: sqlcipher JNI. Immutable vendor source and its SQLCipher
  and libtomcrypt gitlink revisions are recorded. Default source flags include
  libtomcrypt and FTS support; those defaults are not proof of binary build flags.
  Exact embedded versions, vendor patches and advisory applicability remain open.

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
changes. The baseline here remains 4.15.0, not patched merely by this review.
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
