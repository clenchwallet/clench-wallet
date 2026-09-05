# Embedded C source/advisory review — 2026-09-05

This supplements the Cargo candidate review. It is a source-bound, dated review,
not a claim that OSV completely covers C libraries or that vendor binaries have
been independently reproduced. Archive identities, source hashes and query
results are recorded in [the native baseline](native-dependencies.json).

## Bitcoin Core secp256k1 versus the similarly named Rust crate

The checksum-verified `secp256k1-sys` 0.10.1 crate records upstream revision
`1ad5185cd42c0636104129fcc9f6a4bf9c67cc40` in
`depend/secp256k1-HEAD-revision.txt`. The
[Bitcoin Core commit](https://github.com/bitcoin-core/secp256k1/commit/1ad5185cd42c0636104129fcc9f6a4bf9c67cc40)
prepares 0.4.1. Its crate build script compiles the vendored C source, including
ECDH, Schnorr, extra-key and EllSwift modules. Recovery depends on the selected
feature. The revision marker is not proof that the crate makes no local changes
or that the published AAR used identical flags.

An OSV commit query returned `CVE-2021-38195`. The
[converted OSV record](https://osv.dev/vulnerability/CVE-2021-38195) associates it
with this C repository, but the primary
[RustSec advisory](https://rustsec.org/advisories/RUSTSEC-2021-0076.html) and its
[upstream correction](https://github.com/paritytech/libsecp256k1/pull/67) concern
**Parity's Rust `libsecp256k1` crate** and its signature parser. That package is
absent from BDK's pinned lock. The record is retained as a wrong-component
mapping, not presented as a confirmed Clench signature-verification defect or
silently rewritten to a newer C-library version. Other advisories and vendor
patches are not cleared by this disposition.

## The two SQLite implementations

- BDK's checksum-verified libsqlite3-sys 0.28.0 contains SQLite 3.45.0. Its
  bundled build branch enables FTS3/FTS5 and extension-loading support. Their
  absence from the native binary must not be inferred from app usage.
- The SQLCipher 4.17 candidate reports SQLite 3.53.3 on the tested x86_64
  emulator. Its Android-tag source discrepancy remains unresolved; this runtime
  observation does not establish all-ABI source correspondence.

[SQLite's advisory table](https://sqlite.org/cves.html) identifies SQL or database
control as important prerequisites. Candidate groups relevant to the older BDK
source include CVE-2025-3277/29087 (`concat_ws`), CVE-2025-6965 (arbitrary SQL),
CVE-2025-7709 (crafted FTS5 content), and CVE-2026-11822/11824 (FTS5 with defensive
mode disabled). CVE-2025-70873 concerns the separate zipfile extension. These are
review candidates, not demonstrated reachable Clench defects.

Clench's repository constructs BDK `Persister.newSqlite` paths from app-private
database filenames. The pinned BDK FFI `src/store.rs` exposes persistence and
migration operations, not an arbitrary-SQL execution or extension-loading API.
The reviewed Clench production source has no FTS, `concat_ws`, `load_extension`,
`@RawQuery` or `db_config` call. Room migrations contain fixed SQL. State backup
imports parse bounded JSON into application records; they do not import a raw
SQLite database. No native memory-corruption or malicious-database experiment
was run. These source observations narrow entry points, but do not prove every
transitive BDK query safe or exclude app-UID/OS compromise.

The path trace also identified unchecked backup wallet identifiers. Their new
preflight rejects non-filename-safe and duplicate nonblank IDs before descriptor
parsing or database work. Valid existing identifier forms are retained, and
missing IDs still receive fresh UUIDs. This is a defensive input-boundary fix,
not proof that the previous implementation allowed arbitrary-file access.

## libffi, LibTomCrypt, and unknown source associations

The JNA vendored libffi tree exactly matches upstream 3.4.4. The dated OSV query
for its upstream commit returned no matches. The query for SQLCipher's pinned
LibTomCrypt fork commit `476a9579ae94f32b9ea9e2747bfb04b302370259` also returned
none. Neither result establishes complete C/fork advisory coverage. SQLCipher's
provider fixes are separate from the LibTomCrypt library version.

CameraX's `external:libyuv` build uses another source checkout; its exact
revision is not established by the frameworks/support release endpoint.
Graphics-path's inspected CMake has no separately linked third-party target,
but that alone does not establish source ancestry. These remain explicit
vendor/build-evidence prerequisites. Do not substitute a nearby release date,
moving branch or guessed version to make a scanner report green.

## Maintenance boundary

The native identity gate binds reviews to exact resolved archives and catches
drift; the live Cargo query remains an additional pre-sign gate. These dated C
results are not a new automated C clearance and do not suppress Cargo matches.
Before closing SC-02, resolve the recorded vendor-source gaps and establish
maintained advisory inputs for the remaining C components. No source-version
string or package coordinate is changed as a substitute for replacing a binary.
