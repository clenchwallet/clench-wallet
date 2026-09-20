# Locally rebuilt BDK Cargo advisory review — 2026-09-16

The remediation uses **BDK Android 3.0.0-clench.1**, a local build based on
upstream BDK 3.0.0, rather than claiming that the published vendor AAR changed.
The exact native archive hash, per-ABI library hashes and local build evidence
are recorded in `native-dependencies.json`. Build instructions and fixed inputs
are in `scripts/native/`; the upstream Kotlin bindings and BDK feature selection
are preserved. The patched Cargo lock is
`upstream/bdk-ffi-3.0.0-clench-Cargo.lock`; the original upstream lock is retained
separately and is not relabelled as patched upstream content.

## Dependency decisions

| Dependency | Before | Rebuild | Decision |
| --- | --- | --- | --- |
| Rustls, Electrum TLS | 0.23.40 | 0.23.45 | The version-bound fix for [RUSTSEC-2026-0285](https://rustsec.org/advisories/RUSTSEC-2026-0285.html); no call-path exception. |
| anyhow | 1.0.102 | 1.0.103 | Removes [RUSTSEC-2026-0190](https://rustsec.org/advisories/RUSTSEC-2026-0190.html); previous applicability exception is deleted. |
| rustls-webpki, unused Esplora TLS | 0.101.7 | 0.101.7 | No patched compatible 0.101.x range exists in the three upstream advisories. Six exact advisory IDs retain newly reviewed, expiring source-call-path dispositions; not a patched-version claim. |
| rustls-webpki, Electrum TLS | 0.103.13 | 0.103.14 | Required by Rustls 0.23.45; meets the fixed ranges for all three reviewed webpki advisory groups. |

Rustls also requires aws-lc-rs **1.18.0** and aws-lc-sys **0.44.0** in the
complete lock (previously 1.17.0 / 0.41.0). These five version changes are the
full package-version delta; package names, source locations and feature
selection are unchanged. The lock has **198** registry candidates. Its fresh
September 16 OSV query reports only the six legacy webpki IDs; no matches
remain for the updated Rustls, anyhow, modern webpki or AWS-LC candidates.

The legacy Esplora dependency cannot be repaired by substituting webpki 0.103
under Rustls 0.21: those APIs and Cargo compatibility ranges differ. A separate
Esplora/minreq migration would change the vendor's networking implementation.
This scoped rebuild preserves its manifests/features and does not pretend that
unused legacy code is removed. The remaining exact applicability decisions
are documented in `native-cargo-dispositions.md` and machine-bound in `.json`.

## Gate and binding

```bash
python3 -B scripts/release/check-native-cargo-advisories.py \
  --output build/reports/native-cargo-advisories.json
python3 -B scripts/verification/test-native-cargo-advisories.py
```

The live query covers **all** registry package/version candidates in the actual
rebuild lock, including build/dev/conditional dependencies. The source-less
BDK 3.0.0 root remains the only permitted local package. A new local or Git
source, duplicate candidate, missing lookup, stale exception or unknown advisory
fails closed. Raw matches are saved before disposition checking.

The checker separately verifies the original immutable vendor lock and the
new local lock against their correct evidence types. The local lock is bound
to the new archive owner; it is never attributed to the original upstream URL.
Dispositions additionally bind the actual AAR, application production sources,
Gradle inputs/verification metadata, native build scripts and recipes, both
review documents and native baseline. Newly added native recipe or production
files invalidate the review. Every retained advisory is re-fetched and its full
canonical OSV document checked; changed text requires another applicability
review. Reviews expire after at most 30 days, even if inputs do not change.

## Verified rebuilt artifact identity

The replacement AAR SHA-256 is
`f9605a9302e4d53706dc32a34771fc5281b93628c6b6b854353bd860a2273dc2`.
The patched lock SHA-256 is
`f75230c54970038d85db0dbb03529582fefdd8e6cf208fc65872d3344b36c4e2`.
The retained `upstream/bdk-ffi-3.0.0-clench-reproducibility.json` records
byte-identical JNI for all three ABIs and identical AAR/POM/module from two
clean build directories with separate source/Cargo/target paths. Those builds
used the same host and checksum-verified shared download inputs; this is not
independent-host reproduction or a complete unsigned-APK blind rebuild.
The packaging evidence confirms unchanged vendor non-native AAR content.

## September 16 advisory re-review

The RustSec records and all three changed GHSA alias records were fetched and
read again on September 16. The name-constraint records still require use of
the affected verifier, with signed/misissued certificates as described upstream.
The CRL record still requires explicit CRL loading/parsing. Neither describes a
new path outside the legacy Esplora client identified below. Current production
source still has no Esplora construction/reference or reflective/direct JNA load
path; the application native library load is SQLCipher. Minreq 2.14.1's
checksum-bound Rustls module still constructs a standard root-store client with
no CRL configuration. The current Electrum factory uses a controlled Java upstream relay
with platform TLS for TLS-required modes. The separately patched modern native
Rustls/WebPKI capability remains bundled; the dependency repair table is not a
claim that current upstream connections use that native TLS implementation. These are source-call-path conclusions, not native-code
absence or a finding that certificate bugs are harmless in other applications.

No new exception is created for Rustls or anyhow. The repaired component versions
must be in the actual rebuilt libraries and release inventory, not merely this
review copy of a lockfile. Gate success is not whole-product security clearance,
complete native C advisory coverage, or physical-device acceptance. SQLCipher
source association and other separately recorded audit gaps remain open.

## Historical investigation — September 5 (superseded artifact/status)

The following chronological record concerns the **original vendor 3.0.0 AAR**.
Its old blocking/passing states and version applicability are retained only as
history; the current rebuild decisions and gate bindings are above.

### Original BDK upstream Cargo advisory review — 2026-09-05

Status: **exact reviewed call-path dispositions added** in
`native-cargo-dispositions.md` / `.json`. The chronological investigation below
retains the earlier blocking state. The latest live run queried 198 candidates,
retained seven raw IDs and passed with seven exact reviewed dispositions.
No confirmed Clench exploit or blanket native clearance is established.

## Reproduce the coverage check

```bash
python3 -B scripts/release/check-native-cargo-advisories.py \
  --output build/reports/native-cargo-advisories.json
```

The checker verifies the copied vendor Cargo.lock against the SHA-256 and
immutable source URL in `native-dependencies.json`, which also pins the owning
BDK AAR. It queries every one of the 198 registry package/version candidates;
the one local entry is the reviewed bdk-ffi root. This includes build, dev and
conditional dependencies, not just shipped code. Unreviewed non-registry/local
entries fail rather than silently disappearing from coverage. The six finite
input-binding regressions run in Android CI.

The live checker currently exits **1**, after saving its report: seven advisory
IDs correspond to four advisory groups across two candidate package versions.
It does not suppress findings or claim a successful scan. The release workflow now requires this live check in its no-secrets unsigned
build, before any signing dependency can succeed. Findings, source mismatch or
lookup failure block that job; saved native reports are retained on failure.
The current advisory matches therefore block a future release, not ordinary
PR builds. No release workflow has been dispatched to test this change.
Reviewed dispositions and non-Cargo native coverage remain part of open SC-02;
CI identity/test success is not native vulnerability clearance.

## Matches and current evidence

| Candidate | Advisory group | Current disposition |
| --- | --- | --- |
| anyhow 1.0.102 | [RUSTSEC-2026-0190](https://rustsec.org/advisories/RUSTSEC-2026-0190.html) | Version match; affected operation is contextual error followed by mutable downcast. Reviewed mutable anyhow calls are in the WASI-only WIT path, not an established Android path; see source tracing below. UniFFI itself is not classified as build-only. No gate exemption or binary clearance. Patched at 1.0.103. |
| rustls-webpki 0.101.7 | [RUSTSEC-2026-0098](https://rustsec.org/advisories/RUSTSEC-2026-0098.html), alias GHSA-965h-392x-2mh5 | URI name-constraint handling; valid certificate signature and certificate misissuance are prerequisites per upstream. Locked minreq/Esplora chain; Clench call-path review pending. |
| rustls-webpki 0.101.7 | [RUSTSEC-2026-0099](https://rustsec.org/advisories/RUSTSEC-2026-0099.html), alias GHSA-xgp8-3hg3-c2mh | Wildcard DNS name constraints; same upstream certificate prerequisites. Locked minreq/Esplora chain; no demonstrated Clench exploit. |
| rustls-webpki 0.101.7 | [RUSTSEC-2026-0104](https://rustsec.org/advisories/RUSTSEC-2026-0104.html), alias GHSA-82j2-j2ch-gfr8 | CRL parsing panic; upstream says applications not using CRLs are unaffected. CRL configuration/reachability review pending, not a demonstrated remote Clench crash. |

The immutable lock records these distinct paths:

- bdk_esplora 0.22.2 → esplora-client 0.12.3 → minreq 2.14.1 →
  rustls 0.21.12 / rustls-webpki 0.101.7.
- electrum-client 0.25.0 → rustls 0.23.40 → rustls-webpki 0.103.13.

The reviewed bdk-ffi Cargo manifest enables blocking HTTPS/Rustls for Esplora
and Rustls/Ring for Electrum. A source search found no Clench Kotlin
`EsploraClient` usage; that alone is not proof of absence from the binary or all
possible FFI call paths. The modern Electrum path must not be conflated with the
older Esplora path. The modern webpki version meets the fixed-version ranges for
these three advisories; that does not clear other native components.

Next: inspect actual native call sites and feature selection, review a patched
vendor artifact or reproducible source build if affected paths are reachable,
and attach explicit version-bound dispositions. Do not change a locked version
string without replacing and verifying the corresponding native artifact.

## Source and scope

The lockfile copy at `upstream/bdk-ffi-3.0.0-Cargo.lock` is from the
[immutable vendor source](https://github.com/bitcoindevkit/bdk-ffi/blob/cfb3418524d451ba8d1758f0ec27f8443740b422/bdk-ffi/Cargo.lock),
SHA-256 `8e86d388a119564809fafa5fed1b851357f08d8a5cb03634ec6092aec074476a`.
BDK is MIT/Apache-2.0 licensed. The copy is review input, never executed as a
build script. Source association is not independent binary reproducibility.
This check does not inventory C code inside Rust sys crates, vendored libffi,
libyuv, SQLite, libtomcrypt, or compiler/runtime libraries.

## Additional source tracing

Six crate archives were fetched without executing code and verified against
the vendor lock checksums: uniffi, uniffi_core, minreq, esplora-client,
libsqlite3-sys, and secp256k1-sys. The archive identities are in the native
baseline. In uniffi_core 0.30.0, source downcasts found are consuming `downcast`
and shared `downcast_ref`, not the affected `downcast_mut`. This narrows the
anyhow hypothesis but does not clear every runtime/build caller.

Minreq's selected Rustls source builds a standard client with root certificates
and no CRL configuration in that module. The CRL advisory requires CRL use;
this is supporting non-reachability evidence for that module, not an invented
patched version. Its name-constraint behavior still depends on the old webpki
code if that HTTPS client is invoked.

The checksum-verified libsqlite3-sys 0.28.0 crate bundles a SQLite 3.45.0 header,
which is distinct from the SQLCipher candidate's runtime-asserted SQLite 3.53.3.
Do not confuse the two database implementations or extend the Rust advisory
query to imply coverage of either C implementation. Build-feature confirmation,
C advisory applicability and vendor patch assessment remain open.

### Version-resolved anyhow consumer review

On 2026-09-05, all fifteen direct consumers of `anyhow` in the pinned lock were
inspected, plus getrandom 0.3.4 and 0.4.2 for target selection. Each downloaded
crate archive matched its lockfile checksum; no dependency script was executed.
The identities are retained in `verified_crate_sources` in the native baseline.

- `wit-parser` 0.244.0 `src/ast.rs` contains mutable `anyhow::Error` downcasts.
  Version-resolved reverse edges lead through wit-bindgen 0.51.0 and wasip3 to
  getrandom 0.4.2. Its normalized Cargo.toml selects wasip3 only for
  `target_arch = "wasm32", target_os = "wasi", target_env = "p3"`, not Android
  or the Linux/macOS build hosts. A name-only graph would incorrectly conflate
  this with the separate wasip2/wit-bindgen 0.57.1 entries.
- `uniffi_pipeline` 0.30.0 `src/node.rs` calls `downcast_mut` on
  `&mut dyn std::any::Any`, not on `anyhow::Error`. It is not evidence of the
  advisory's affected operation. Other reviewed UniFFI Rust files did not
  contain mutable-downcast calls. UniFFI does re-export anyhow; that fact is
  not treated as an exploit or ignored as build-only.
- All 106 files in the pinned BDK source archive matched the corresponding
  Git blob IDs. Its Rust sources contain no `downcast_mut`, `anyhow::`, or
  `uniffi::deps::anyhow` reference. This includes the local source consumer of
  UniFFI's re-export, but does not establish arbitrary generated-code behavior.

These observations establish **no affected Android call path in the reviewed
source**, not absence of anyhow from the native binary. The release gate still
reports the version match and fails; no suppression was introduced. Vendor
build/source correspondence and the remaining native components remain open.

## Application-path re-review for 0.3.31 — 2026-09-17

The controlled loopback Electrum transport now uses Java upstream sockets,
including platform trust/hostname checks in TLS-required modes. No production
Esplora construction, reflection/direct JNA load or CRL input route was added.
All six complete OSV documents were re-fetched and reviewed; canonical hashes
remain identical to the September16 review. Checksum-verified minreq2.14.1's TLS
module still accepts no CRL configuration. Native source, recipe, features,
Cargo lock and resolved payload identities are unchanged. The six exact
legacy call-path dispositions retain their original2026-10-16 expiry.
See [the current disposition evidence](native-cargo-dispositions.md).

Schema14's fixed-name migration and bound metadata imports add no `hexkey` URI
or `sqlcipher_export` route. This does not fix SQLCipher4.17 by version, resolve
the vendor source association discrepancy or complete native C assurance.
Final application/evidence bindings and the full live gate remain mandatory.

### 2026-09-20 NFC draft-destination delta

Re-reviewed the PR90 destination-revocation correction: ViewModel-owned NFC attempt invalidation and guarded signer publication add no native/network/Esplora/CRL call paths, dependency changes, TLS configuration or feature changes. Existing six exact WebPKI dispositions and their rationale still apply to this source; expiry remains2026-10-16. This is an applicability binding refresh, not native provenance or physical TAPSIGNER clearance. Independent delta review remains required.
