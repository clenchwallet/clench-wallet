# Exact-candidate Cargo applicability dispositions — 2026-09-17

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
constructs BDK `ElectrumClient` instances on controlled loopback TCP;
`BdkBitcoinRepository.kt` obtains sync/broadcast connections through that factory.
All current upstream Electrum transports use the controlled Java relay.
TLS-required modes use Java `SSLSocket` with trust and hostname verification;
plain modes remain plain. Patched modern native Rustls/WebPKI stays bundled,
but is no longer the ordinary application upstream TLS transport. No application `Class.forName`,
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

## 0.3.31 application binding re-review — 2026-09-17

The eight-finding remediation changes application behavior, including network
admission and cancellation, but adds no Esplora construction/reference,
reflection/direct JNA load or CRL configuration path. All active Electrum
connections use the controlled loopback relay described above; this supersedes
the earlier release's active native-TLS description. The legacy
Esplora/minreq/Rustls0.21/WebPKI0.101.7 dependency remains present but unconstructed
by reviewed production paths. Checksum-verified minreq2.14.1's TLS module still
constructs a trusted-root client without CRL input.

All six complete live OSV records were fetched and read on September17; their
canonical hashes match the September16 records. The native recipe, features,
locks, verification metadata and native payload identities are unchanged. The
same exact call-path dispositions remain justified with the original
2026-10-16 expiry. The JSON binds the final candidate source and these documents;
the full live query must pass on that binding before release. This is not a
blanket exception or a claim that legacy code is absent or patched.

The Room13-to-14 migration adds fixed-name SQL and bound metadata operations,
not a SQLCipher URI `hexkey`, arbitrary schema alias or `sqlcipher_export` path.
SQLCipher4.17's separate source/provenance and maintenance gaps remain open.

## Signer-label correction binding re-review — 2026-09-17

Reviewed correction `71597d9b4f3a601dc7b1acf94c46f1dcfe8d43cd` against
`20dfb45947629e8f27ff455aa8b1406ec85ea719`. The only production-input change
is WalletInfoViewModel's lookup of the two existing descriptor-derived metadata
ID spellings and selection of the newest matching display label. It adds no
network operation, native entry point, reflection, CRL configuration or dependency
change. The companion regression-test changes are outside production inputs.
The existing independent changed-path review is recorded on PR86.

A fresh production-source search found no Esplora construction/reference,
Class.forName, loadClass or direct Native.load call; the explicit loadLibrary
remains SQLCipher. ElectrumConnectionFactory still creates the native client
on controlled loopback TCP and uses Java SSLSocket for upstream TLS. The native
recipe, features, locks and verification metadata are unchanged from the prior
review. The correction therefore adds no route to the legacy URI/wildcard-name
constraint verifier or CRL parser described above.

All six full live OSV records were fetched again and their details re-read;
canonical hashes match the existing exact dispositions. The same affected
legacy versions remain reported, and no patched/removed-code claim is made.
This refresh retains all six exact dispositions and the original October16
expiry, binding the corrected production inputs and this evidence. A fresh
full-candidate live query remains required; device acceptance and broader native
assurance are separate gates.

## SeedSigner return correction binding re-review — 2026-09-19

Reviewed production correction 9783cc8 against merged c742179. The sole
production change validates the retained canonical PSBT before a signature-only
merge instead of extracting the metadata-trimmed return. Exact unsigned-byte
comparison, signature policy and full merged/final transaction validation remain.
This adds no network transport, Esplora construction, reflection, native loading,
CRL input, native feature or dependency change. ElectrumConnectionFactory still
uses controlled loopback and Java upstream TLS; the explicit application native
load remains SQLCipher. No new path reaches the legacy verifier/CRL parser.

All six complete live OSV documents were fetched and re-read on September19.
Their canonical hashes changed, so the old content hashes were not reused.
The live ranges still include legacy0.101.7: URI/wildcard fixes begin0.103.12,
CRL fix0.103.13, with separate prerelease ranges. URI/wildcard constraints still
require the affected verifier path; CRL panic can precede signature verification
but requires CRL parsing. The existing absent Esplora construction and unchanged
minreq no-CRL configuration evidence continues to apply. The six exact call-path
dispositions retain their original October16 expiry; legacy code is not claimed
patched or removed. Physical acceptance and independent review of the correction
remain separate outstanding gates. The full live candidate query remains required.

## Coldcard finalized-return display correction re-review — 2026-09-19

Physical Coldcard Q NFC acceptance exposed a Compose crash after a finalized
raw transaction passed repository policy validation: outbound PSBT QR generation
ran even though the completed-signing screen does not display that QR. The
correction skips outbound encoding in the completed state and keeps strict PSBT
validation for pending exports. It changes no transaction validation, native
entry point, dependency, transport or signing/broadcast policy. Production
searches still find no Esplora/reflection/direct JNA entry point; SQLCipher is
the explicit native load and Electrum uses the existing controlled relay.

All six complete live OSV records were fetched and their descriptions/ranges
re-read. Their canonical hashes match the earlier September19 review; legacy
0.101.7 remains affected in the same three groups. No new path reaches the
legacy verifier or CRL parser. Retain the six exact call-path dispositions and
the original October16 expiry, with a new application/evidence binding. This
is not a native clearance; regression, physical retest and independent review
of this display correction remain required.

## Coldcard lifecycle correction delta re-review — 2026-09-19

Independent review found that the initial display guard at f46dd95 was incomplete:
readiness clears after broadcast and replacement attempts. The delta separates
validated finalized transaction data from canonical PSBT data in repository
progress. Raw returns retain the reviewed original PSBT for export/replacement;
only the distinct signed payload reaches the existing explicit broadcast method.
The ViewModel exposes a pending export payload, preserves canonical recovery and
requires a fresh review after restart. Restart snapshots also bind the signed
payload and return count. The signature/transaction equality, freeze, session
and network authorization boundaries are unchanged.

This data-flow correction adds no native entry point, TLS/CRL input, Esplora or
reflection path, dependency, feature or native recipe change. Fresh production
searches retain the earlier no-Esplora/reflection/direct-JNA findings. All six
full live OSV documents were fetched again; their canonical hashes and affected
ranges match the previously reviewed records. The same exact six call-path
dispositions remain applicable, with the original October16 expiry and refreshed
application/evidence hashes. New lifecycle regressions, native raw-return
contract assertions, independent delta review and physical retry are separate
gates; the prior f46dd95 review is not an approval of this delta.

## Multisig NFC import cancellation delta review — 2026-09-20

The production delta adds explicit ownership of the pending credential and active ISO-DEP connection for the multisig import screen. Cancellation and backgrounding close the connection, invalidate old callbacks, and require a fresh PIN entry. Card factory, network, path, derive, child-proof and xpub checks remain in place. No automatic retry or new native/TLS/CRL/Esplora/reflection entry point is added.

The six legacy WebPKI applicability dispositions remain tied to the same BDK artifact, Cargo lock and input paths. Their original 2026-10-16 expiry is retained. The live gate re-fetches each complete advisory and rejects changed content; a matching gate is not a native clearance or independent approval of this change. Source and evidence hashes are refreshed for this bounded delta.

## Phone-signer asynchronous completion re-review — 2026-09-21

The bounded CreateMultisigViewModel correction binds pending phone-key generation to an operation identity, draft revision and selected network. Draft mutation revokes that ownership; stale success, failure and cleanup cannot overwrite a newer signer destination or operation. Key generation algorithms, storage, native interfaces and wallet identities are unchanged. This change introduces no Esplora, reflection, native loading, TLS or CRL input and does not alter native recipes, features, dependencies or transport construction. The six existing legacy WebPKI call-path dispositions retain the original 2026-10-16 expiry and advisory-content hashes. The live applicability gate must re-fetch and match every advisory and query all candidates; any mismatch still blocks. This refresh is not native provenance clearance or independent changed-path approval.

### Phone/NFC admission overlap delta — 2026-09-21

Independent review of658c630 identified a newer NFC admission without a draft edit that could leave older phone generation current. The screen now admits through a ViewModel boundary that revokes pending phone ownership/loading before starting the existing NFC session. Existing draft revision, credential ownership, card validation, and protocol commands are unchanged. No native, TLS/CRL, Esplora, reflection, dependency or build-recipe input path is added. Retain all six exact advisory dispositions/content hashes and the original2026-10-16 expiry; refresh production/evidence binding and require the live gate and independent delta review.
