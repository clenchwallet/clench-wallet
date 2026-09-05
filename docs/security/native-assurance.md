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

- CameraX camera-core 1.6.1: image-processing and surface JNI libraries. Exact
  release-source mapping and bundled source dependency/advisory review pending.
- AndroidX graphics-path 1.0.1: path JNI library. Exact release-source mapping and
  source dependency/advisory review pending.
- JNA 5.14.0: jnidispatch. Vendor tag resolved to an immutable commit; build file
  evidence recorded. Exact bundled libffi and native advisory review pending.
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
