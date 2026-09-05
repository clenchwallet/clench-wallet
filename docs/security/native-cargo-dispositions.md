# Exact-candidate Cargo applicability dispositions — 2026-09-05

These are source-call-path dispositions for the pinned BDK Android 3.0.0
artifact, not claims that its dependency versions were patched or absent from
the binary. The full candidate query still runs, retains every raw finding and
blocks unknown findings. Each disposition is bound to the AAR, Cargo lock,
complete Clench production source/build inputs, this evidence and the full live
OSV advisory document. Any changed binding, stale entry, new ID, failed lookup
or expiry requires a new review. Reviews expire after at most 30 days.

## rustls-webpki 0.101.7: three advisory groups, six IDs

The pinned BDK `src/esplora.rs` constructs its `BlockingClient` only through
the exported `EsploraClient::new`; its HTTP operations require that instance.
Its dependency chain is bdk_esplora 0.22.2 → esplora-client 0.12.3 → minreq
2.14.1 → rustls 0.21.12 → rustls-webpki 0.101.7. The older webpki API is not
interchangeable with the separately locked 0.103.13 implementation used by
electrum-client 0.25.0 / rustls 0.23.40.

Clench's production source has no Esplora reference or construction path.
`ElectrumConnectionFactory.kt` constructs BDK `ElectrumClient` instances;
`BdkBitcoinRepository.kt` obtains sync/broadcast connections through that
factory. No application `Class.forName`, `loadClass` or direct JNA `Native.load`
call was found; the application `System.loadLibrary` call loads SQLCipher.
These are static application-path observations, not a claim that a compromised
app process cannot invoke unused exports. The broad BDK ProGuard keep rule
remains; no binary-removal claim is made.

RUSTSEC-2026-0098 / GHSA-965h-392x-2mh5 and RUSTSEC-2026-0099 /
GHSA-xgp8-3hg3-c2mh require the affected certificate validation path, in
addition to the issuer/signature prerequisites described upstream. That old
TLS path is not invoked by the reviewed Clench source. RUSTSEC-2026-0104 /
GHSA-82j2-j2ch-gfr8 additionally requires CRL use. The checksum-verified
minreq Rustls module creates a standard root-store client with no CRL
configuration. Its constructor and network operations do not expose CRL input.
The modern Electrum dependency meets all three upstream fixed-version ranges.

Disposition: **not affected through the reviewed application call paths**.
Adding Esplora, another FFI entry point or any production source/build change
invalidates the bound review. This is not authorization to use the old TLS
implementation in new application features.

## anyhow 1.0.102: RUSTSEC-2026-0190

The upstream affected operation is `anyhow::Error::context` followed by
`anyhow::Error::downcast_mut`. The version-resolved source review in
`native-cargo-review.md` inspected all fifteen direct consumers and the
getrandom target edges, with crate archives checked against the vendor lock.
The matching mutable anyhow use belongs to wit-parser 0.244.0 on the
wasm32/WASI-p3 path. The other `downcast_mut` in uniffi_pipeline operates on
`std::any::Any`, not `anyhow::Error`. UniFFI core uses shared/consuming error
downcasts; the reviewed macro code does not generate the affected mutable
anyhow operation. All 106 files in the pinned BDK archive were checked against
Git blob IDs; its Rust source does not invoke the affected operation or its
anyhow re-export. UniFFI is not incorrectly classified as build-only.

Disposition: **not affected through the reviewed Android code path**. This
does not claim anyhow is absent, nor that upstream unsoundness is harmless in
general. A vendor artifact update remains preferable when available. Maven
Central metadata checked on 2026-09-05 still lists 3.0.0 as the latest release.

## Evidence and limitations

- Immutable vendor source and lock: `native-dependencies.json`,
  `upstream/bdk-ffi-3.0.0-Cargo.lock`, and `native-cargo-review.md`.
- Primary advisories: https://rustsec.org/advisories/RUSTSEC-2026-0190.html,
  https://rustsec.org/advisories/RUSTSEC-2026-0098.html,
  https://rustsec.org/advisories/RUSTSEC-2026-0099.html,
  https://rustsec.org/advisories/RUSTSEC-2026-0104.html.
- Exact source: https://github.com/bitcoindevkit/bdk-ffi/blob/cfb3418524d451ba8d1758f0ec27f8443740b422/bdk-ffi/src/esplora.rs.

The review assumes the checksum-pinned vendor artifact corresponds to its
published release source association; that trust is explicit and has not been
replaced by an independent native rebuild. Neither this gate nor these
dispositions resolve the separate CameraX libyuv revision gap, SQLCipher
source-tag discrepancy or completeness of embedded C advisory coverage.
SC-02 therefore remains open for those distinct provenance/coverage questions.
