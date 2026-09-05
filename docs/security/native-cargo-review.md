# BDK upstream Cargo advisory review — 2026-09-05

Status: **candidate matches require disposition**. No confirmed Clench exploit
or blanket native clearance is established by this report.

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
It does not suppress findings or claim a successful scan. The live advisory
check is not yet a required release job; integrating reviewed dispositions and
covering the non-Cargo native components remains part of open SC-02. Existing
CI identity/test success is not native vulnerability clearance.

## Matches and current evidence

| Candidate | Advisory group | Current disposition |
| --- | --- | --- |
| anyhow 1.0.102 | [RUSTSEC-2026-0190](https://rustsec.org/advisories/RUSTSEC-2026-0190.html) | Version match; affected operation is contextual error followed by mutable downcast. UniFFI occurs in both runtime/build graph. Call-site and shipped reachability review pending; do not label build-only. Patched at 1.0.103. |
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
