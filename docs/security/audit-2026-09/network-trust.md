# Android network trust correction

Baseline application: v0.3.31/f3d9d88009400fe6042cf81fd2ef31974f02e140.
Master b26fc99 adds only website files. This review does not repeat publication.

## Confirmed defects

1. **Explicit TLS/pins lost during route resolution.** Settings can retain a pin
   when SSL is toggled off. Direct routing enforced that pin, but enabling Tor
   selected plaintext and discarded it. Onion routing also unconditionally used
   plaintext despite an explicit TLS/pin configuration. The baseline targeted
   JVM run35522724618 has10tests/2failures/0errors/0skips. The two failing cases
   require TOR_TLS and retained pin bytes. Correction makes explicit TLS or a
   pin survive routing, while an explicitly plaintext unpinned onion remains
   Tor plaintext.
2. **A pinned trust anchor bypasses expiry and is not exact-leaf pinning.** The
   Android baseline matrix35522858605 has7tests/2failures/0errors/0skips: an
   expired pinned leaf was accepted, and a pinned CA accepted a different leaf.
   The correction retains the platform trust manager and HTTPS endpoint
   identification, then verifies exact peer leaf DER and validity before any
   Electrum application bytes. A CA certificate is no longer interpreted as
   authority to accept a different server certificate. Users must explicitly
   replace an expired pin with the current server certificate; no automatic
   fallback or trust-store widening is added.

These are network authentication/privacy defects, not demonstrated unauthorized
transaction signing or fund loss. All fixtures are synthetic, with no public
broadcast or mainnet funds.

## Evidence

- Corrected targeted JVM:10/10pass. Native applicability gate198candidates,
  six exact reviewed IDs, no unresolved;21gate regressions pass. Original
  WebPKI disposition expiry2026-10-16 retained.
- Pixel7/API37/arm64, serial2B231FDH200E44: seven actual Android TLS matrix
  tests pass on sourceceedc200791681c5de9b376b247f4c4bf73eea09 plus only an
  isolated application-ID suffix. Existing installations and device trust/
  credential configuration preserved. Separate two additional tests pass for
  unavailable SOCKS without direct fallback, and interruption during TLS
  handshake followed by a fresh successful connection. No completed physical
  SeedSigner/Coldcard/SATSCARD acceptance was restarted.
- Direct and SOCKS positive controls; wrong leaf, expired leaf/self-signed
  anchor, hostname mismatch, unknown CA, configured CA versus exact leaf.
  HTTP uses Android TrustManagerFactory with a test-only synthetic root and
  restores the original HTTPS socket factory in finally. Unknown-CA Electrum
  rejection uses the actual default Android trust configuration.
- SOCKS fixture records unresolved `fixture.invalid` destination; successful
  proxy tests require remote name handling. This is controlled SOCKS transport
  evidence, not an Orbot/public-Tor-circuit or packet-capture claim. No secret
  wallet material is used. Existing HTTP response/offline/reconnect physical
  evidence remains applicable to unchanged code.

Durable receipts, APK/source hashes, failed baseline XML, corrected physical
output and source deltas are under `/Users/pb/clench-audit-20260920/evidence`.
Exact final-head hosted evidence and independent changed-path review remain
required before merging/releasing.

## Standing CodeQL pinning warning

The historical alert6 describes no HTTP-service pinning in TorAwareHttpClient.
That literal condition remains; it is not a false positive or fixed by this
Electrum correction. Platform chain and hostname validation remain enabled;
redirects are disabled. Third-party prices influence USD-to-satoshi conversion,
fees influence recommended rates, and configured block-explorer URLs can expose
address/transaction metadata when intentionally opened. A valid chosen endpoint
can still supply dishonest data. Blanket pins for independently operated or
user-configured endpoints would introduce ownership/rotation failures without
resolving that data-trust risk. Preserve platform-CA trust and explicit opt-ins;
a future owned-endpoint policy needs backup/rotation/recovery design. The API
request for current alert6 returned404 under the authorized account, so this
phase does not claim a live alert-status change or a scanner dismissal.

Residual gaps: full public Tor circuit/DNS packet capture, wider OEM behavior,
and any new transport mode remain distinct from this bounded matrix. No native
cryptographic or whole-product audit closure is claimed.
