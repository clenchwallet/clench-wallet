# BDK upstream Cargo advisory review — 2026-09-05

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
