# BDK native security rebuild

This maintenance change replaces BDK's three Android JNI libraries with a
source build carrying Rustls 0.23.45. It does not reopen or complete the
interrupted whole-product audit, publish a release, or resolve SQLCipher's
separate vendor-source discrepancy.

## Exact scope

| Input | Pinned value |
| --- | --- |
| BDK FFI upstream source | `cfb3418524d451ba8d1758f0ec27f8443740b422` (3.0.0) |
| Original Kotlin bindings | Checksum-verified BDK Android 3.0.0 AAR, unchanged non-native entries |
| Local Maven coordinate | `org.bitcoindevkit:bdk-android:3.0.0-clench.1` |
| Android ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| Rustls | 0.23.40 → 0.23.45 |
| Anyhow | 1.0.102 → 1.0.103 |
| Required transitive changes | rustls-webpki 0.103.14, aws-lc-rs 1.18.0, aws-lc-sys 0.44.0 |

The immutable original Cargo lock remains checked in. The separately named
`upstream/bdk-ffi-3.0.0-clench-Cargo.lock` is the actual rebuild input. Its 198
registry candidates include conditional/build/development dependencies; that
number is not a claim that all 198 are present in the APK. BDK source code,
features, Kotlin bindings and application behavior are not otherwise changed.

## Repeatable build and identity

On the supported Linux x86_64 builder with Android NDK 28.2.13676358:

```bash
python3 -B scripts/native/prepare-bdk.py
./gradlew --no-daemon --dependency-verification=strict \
  -I scripts/verification/native-artifacts.init.gradle \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:exportNativeRuntimeArtifacts
python3 -B scripts/release/inventory-native-artifacts.py \
  --baseline docs/security/native-dependencies.json \
  --output build/reports/native-dependency-inventory.json
python3 -B scripts/release/check-native-cargo-advisories.py \
  --output build/reports/native-cargo-advisories.json
```

The recipe downloads only pinned upstream/compiler archives, builds with the
reviewed Cargo lock, and checks native ABI exports and ELF hardening. The AAR
packager replaces every expected JNI entry and verifies all other entry bytes
remain identical to the vendor AAR. ZIP ordering, timestamps, permissions and
storage are deterministic. Gradle resolves the patched coordinate exclusively
from the local build repository and verifies its committed SHA-256 pins; there
is no remote fallback or committed native prebuilt.

`build/native-bdk/provenance.json`, `abi-comparison.json` and
`packaging-manifest.json` record the actual local result. The reviewed output
identities are also in `native-dependencies.json` and Gradle verification
metadata. A successful source rebuild records the inputs used for the new
libraries; it does not establish that the previous vendor libraries were
reproducible from those inputs.

CI explicitly prepares the local artifact before resolution. Independent
release jobs perform their own native builds, without receiving another job's
native binaries. The existing unsigned-APK equality and isolated-signing gates
remain required. Local tests do not substitute for those hosted release proofs.

## Advisory coverage retained

Rustls and Anyhow are version-bound fixes, not new advisory exceptions. The
remaining six legacy WebPKI 0.101.7 IDs are exact, short-lived source-call-path
dispositions for the unused Esplora dependency, described in
[the native review](native-cargo-review.md). Legacy code has not been removed or
represented as patched. Unknown findings and changed inputs continue to block
release.

The Maven SBOM records the unchanged wrapper's original 3.0.0 ancestry and AAR
hash. Maven advisory checks query both that upstream identity and the local
rebuild identity, so the private version suffix cannot conceal a future wrapper
advisory. The Cargo gate separately examines the real patched native lock.

Android runtime acceptance, physical devices, hardware signers, complete C
advisory coverage and independent release rebuilds must be reported according to
what actually executed; compilation or a clean advisory query alone is not
end-to-end security sign-off.
