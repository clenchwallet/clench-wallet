# Exact-candidate Cargo applicability dispositions — 2026-09-16

These are six source-call-path dispositions for locally rebuilt **BDK Android
3.0.0-clench.1**, not claims that all native dependency versions are patched or
absent from the binary. The full candidate query runs, retains every raw finding
and blocks unknown findings. Each disposition is bound to the rebuilt AAR,
patched Cargo lock, production source/build inputs including the native build
recipe, this evidence and the full live OSV advisory document. Any changed
binding, stale entry, new ID, failed lookup or expiry requires a new review.
Reviews expire after at most 30 days. No exception is retained for anyhow or
added for the September 14 Rustls advisory: those versions are patched instead.

## rustls-webpki 0.101.7: three advisory groups, six IDs

The pinned BDK `src/esplora.rs` constructs its `BlockingClient` only through
the exported `EsploraClient::new`; its HTTP operations require that instance.
Its unchanged dependency chain is bdk_esplora 0.22.2 → esplora-client 0.12.3 →
minreq 2.14.1 → rustls 0.21.12 → rustls-webpki 0.101.7. The legacy API is not
interchangeable with the separately updated 0.103.14 implementation used by
electrum-client 0.25.0 / patched rustls 0.23.45. Upstream's fixed ranges do not
include any compatible 0.101.x patch release. This rebuild changes Cargo.lock,
not the upstream manifest, feature set or Esplora/UniFFI interface.

Current Clench production source (including additional source sets) has no
Esplora reference or construction path. `ElectrumConnectionFactory.kt`
constructs BDK `ElectrumClient` instances; `BdkBitcoinRepository.kt` obtains
sync/broadcast connections through that factory. No application `Class.forName`,
`loadClass` or direct JNA `Native.load` call is present; the application
`System.loadLibrary` call loads SQLCipher. These are static application-path
observations, not a claim that a compromised app process cannot invoke unused
exports. The broad BDK ProGuard keep rule remains. The old implementation is
not claimed to have been removed from the native artifact.

### Re-reviewed live advisory contents

All six complete OSV records were fetched/read again on September 16; the
three GHSA records changed since the original September 5 review. The hashes
in the JSON bind the newly reviewed documents, not merely their identifiers.

- [RUSTSEC-2026-0098](https://rustsec.org/advisories/RUSTSEC-2026-0098.html) /
  [GHSA-965h-392x-2mh5](https://github.com/advisories/GHSA-965h-392x-2mh5):
  URI constraints were accepted by the affected verifier. The current GHSA
  range starts at 0.101.0 and ends at 0.103.12, plus a separate prerelease
  range; **0.101.7 remains a match**. Proper signature verification and
  misissuance prerequisites do not justify ignoring a reachable path. Here
  the relevant legacy verifier is only used by the unconstructed Esplora client.
- [RUSTSEC-2026-0099](https://rustsec.org/advisories/RUSTSEC-2026-0099.html) /
  [GHSA-xgp8-3hg3-c2mh](https://github.com/advisories/GHSA-xgp8-3hg3-c2mh):
  wildcard DNS names could satisfy constraints too broadly. The same current
  stable/prerelease fixed ranges still include 0.101.7 as affected. The
  legacy call-path conclusion is the same, not a claim that certificate
  signatures alone prevent the problem.
- [RUSTSEC-2026-0104](https://rustsec.org/advisories/RUSTSEC-2026-0104.html) /
  [GHSA-82j2-j2ch-gfr8](https://github.com/advisories/GHSA-82j2-j2ch-gfr8):
  CRL parsing may panic before signature verification. Current stable fix
  starts at 0.103.13; **0.101.7 remains a match**. The updated detailed
  description still requires loading/parsing CRL input. In addition to the
  absent Esplora construction path, checksum-verified minreq 2.14.1's Rustls
  module creates a standard root-store client without CRL configuration;
  its constructor/network interface does not expose CRL input.

Disposition for these exact six package/advisory pairs: **not affected through
the reviewed application call paths**. Adding Esplora, another native entry
point, changed feature selection or any production source/build change
invalidates the bound review. This is not authorization to use the legacy TLS
implementation in new features. Electrum's updated webpki meets the fixed
ranges for all three groups.

## Patched findings, with no applicability exceptions

- Rustls **0.23.40 → 0.23.45** repairs the matched Electrum TLS dependency for
  [RUSTSEC-2026-0285](https://rustsec.org/advisories/RUSTSEC-2026-0285.html).
- anyhow **1.0.102 → 1.0.103** removes the match for
  [RUSTSEC-2026-0190](https://rustsec.org/advisories/RUSTSEC-2026-0190.html).
  The former Android-call-path exception is deleted because it would now be
  stale, even though the earlier source rationale was retained as history.

## Source and assurance boundary

The original source is BDK commit
`cfb3418524d451ba8d1758f0ec27f8443740b422`. Its immutable source archive and
original lock remain separately hashed in `native-dependencies.json`. The
patched local lock is `upstream/bdk-ffi-3.0.0-clench-Cargo.lock`; actual rebuilt
AAR and native library identities are recorded in the same baseline. The
[upstream Esplora source](https://github.com/bitcoindevkit/bdk-ffi/blob/cfb3418524d451ba8d1758f0ec27f8443740b422/bdk-ffi/src/esplora.rs)
is unchanged. This is an application-owned native rebuild, not a claim that the
new bytes equal the old vendor binaries. Byte reproducibility and the build
checks actually completed are reported with the rebuild evidence.

Neither this gate nor these dispositions resolve the separate SQLCipher
source-tag discrepancy, complete embedded C advisory coverage, physical-device
acceptance or unfinished whole-product audit lanes. No broader native security
clearance follows from this exact applicability review.

## 0.3.30 application binding re-review — 2026-09-16

The release candidate changes only versionName/versionCode in the application
build inputs after the imported remediation. Production source still constructs
Electrum clients through `ElectrumConnectionFactory`; no Esplora reference,
`Class.forName`, `loadClass` or direct `Native.load` path was found in production
source. The explicit native load remains SQLCipher. Native source, features,
recipe and dependency identities are unchanged by versioning. All six complete
live OSV records were fetched and reviewed again; their canonical content hashes
match the September 16 dispositions. The version bump adds no path to the legacy
verifier or CRL parser. Refreshing the application/evidence binding therefore
retains the same six exact dispositions and the original 2026-10-16 expiry.
Fresh native/app/runtime and live full-candidate gates remain required.
