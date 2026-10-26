# Android emSign public-key distrust — 2026-10-25

## Scope

The October 25 native Cargo review correctly excluded dormant native TLS call
paths, but did not establish Android's protection against compromised emSign
intermediate keys on every supported device. Google Play Services blocklist
rollout is not proof of coverage on outdated or non-GMS/F-Droid installations.
This correction adds the same eight-key rejection policy to Clench's active
Android Electrum and HTTPS paths. It does not change the device trust store.

## Public identity evidence

The source is rustls/webpki commit
[`8ecdcc76f56b68fda67a6adf7976f5eed5006a4c`](https://github.com/rustls/webpki/commit/8ecdcc76f56b68fda67a6adf7976f5eed5006a4c),
merged through [upstream PR 546](https://github.com/rustls/webpki/pull/546).
The eight public keys and immutable source URLs are retained in
`upstream/emsign-2026-10/`. The upstream files contain ASN.1 SEQUENCE **contents**;
Clench's retained files add the canonical SEQUENCE tag and length. OpenSSL
public-key parse/re-encoding verified each resulting complete DER SPKI.
`sources.json` records both hashes to make that encoding distinction explicit.

`CertificateDistrust.EMSIGN` contains SHA-256 hashes of the complete DER
SubjectPublicKeyInfo, matching `X509Certificate.publicKey.encoded`, not hashes of
certificate DER. Reissue, a different serial number or cross-signing cannot
change the identity of the compromised key. No real compromised private key is
present in the repository or test material.

## Enforcement and preserved checks

`PlatformTlsTrust` initializes the platform's default TrustManagerFactory with
the existing normal trust configuration. An explicit Electrum pin instead
narrows that same factory to the chosen certificate, as before. A host-bound
`DistrustingTrustManager` delegates each basic/socket/engine validation overload
to the underlying X509ExtendedTrustManager first. Thus socket hostname and
algorithm checks are not replaced by a simpler trust-manager call.

After normal validation, Android's X509TrustManagerExtensions obtains the
completed validated path from the same delegate. The app rejects a blocked
public key anywhere in that path, including a cached intermediate or trust
anchor omitted by the peer. Missing/unsupported validation-path behavior fails
closed; there is no fallback to checking only transmitted certificates. Client
certificate validation remains delegated unchanged.

Both `ElectrumConnectionFactory.wrapTls` and `TorAwareHttpClient` use this
factory before application payloads. Electrum retains HTTPS hostname
identification, exact pinned-leaf DER comparison and explicit pinned-leaf
validity. HttpsURLConnection retains its normal hostname verification.
The existing network lease, cancellation, SOCKS routing, no redirect follow-up
and response limits remain in place. The internal test factory seam is not a
runtime setting; public injected constructors always use the fixed policy.

## Required verification and remaining limits

JVM tests compare all eight actual retained public keys with the production
policy and cover all validation overloads, delegate rejection, missing path and
unrelated-key success. The 13-test Android network contract combines the nine
existing trust/pin/hostname/routing regressions with full-path distrust checks.
Synthetic positive and negative chains cover leaf, intermediate, omitted root,
cached omitted intermediate, reissue/cross-sign and exact-pin cases, over direct
and SOCKS routes for both Electrum and HTTPS. Rejection observes the first
decrypted application byte, not merely a completed line. Fixture issuer private
keys are discarded; only a disposable synthetic test-leaf key is retained.

The workflow runs that contract on API 26 AOSP (no Google services) and API 35.
Exact result names/counts, failures, errors, skips and duplicates are gated.
Execution receipts must establish success on the exact candidate; this design
document does not pre-assert unfinished builds or device tests.

This is a bounded trust correction. It does not establish universal OEM TLS
compatibility, exhaustive CA distrust, broader physical hardware-wallet
acceptance, SQLCipher source-to-binary correspondence or whole-product security
clearance. The existing native advisory review and its expiry remain separate.
