# Clench Verification Laboratory

This laboratory exercises hostile protocol and recovery boundaries deterministically. It supplements BDK, Android, emulator, Testnet3, and physical-device testing; it does not replace them.

## Reproduce locally

From a clean source checkout:

```bash
CLENCH_FUZZ_CASES=1024 \
./gradlew --no-daemon --dependency-verification=strict \
  testDebugUnitTest lint assembleDebug
```

For a longer seeded run:

```bash
CLENCH_FUZZ_CASES=20000 \
./gradlew --no-daemon --dependency-verification=strict \
  testDebugUnitTest
```

Every generated failure reports its fixed seed and case index. `CLENCH_FUZZ_CASES` is clamped to 64–20,000; the default is 512 and hosted CI/release gates use 1,024.

Run only the deterministic mutation-fuzz lane locally with:

```bash
scripts/verification/run-hostile-fuzz.sh 20000
```

The weekly `Hostile Protocol Fuzz` workflow runs 5,000 cases per generated
property under strict dependency verification and without signing material.
A reported seed/case pair is replayed by rerunning the same suite with a case
count greater than that case index.

## Hostile fixture matrix

| Surface | Simulator / fixture | Required invariant |
| --- | --- | --- |
| PSBT / BIP-174 and BIP-370 framing | Generated key-value maps, mutations, duplicate keys, non-canonical/truncated CompactSize values | Reject malformed or oversized framing before BDK; never accept a duplicate key in one map |
| Animated QR / UR / BBQr / Base43 | BC-UR v2, legacy UR v1, `ur:psbt`, `ur:bytes`, BBQr, and bounded static Base43 PSBT/transaction fixtures; shuffled, mixed, conflicting, and malformed streams | Normalize one bounded, structurally valid payload or fail closed; never carry state across conflicting sessions |
| Coldcard NFC / NDEF | Mocked binary PSBT/transaction, checksum, duplicate-record, excessive-record, and oversized-record messages | Accept one bounded structurally valid signing payload; reject ambiguous payloads and missing, duplicate, or mismatched integrity evidence |
| NFC / APDU transport | Deterministic interrupted-response simulator plus arbitrary response corpus | Truncated or oversized responses never enter protocol state; retry begins from fresh state |
| SATSCARD | Deterministic status/read/certs/dump responses plus duplicate-key, wrong-type, recursive, malformed-indefinite, and semantically hostile CBOR | Enforce one break-aware, depth-bounded CBOR root and bounded signatures, keys, nonces, chain, slots, address, and delays |
| TAPSIGNER | Deterministic status/wait/derive/xpub/sign responses; production-card derive transcripts; documented-profile rejection; indefinite CBOR; BIP-143 P2WPKH/P2WSH vectors; witness-script mismatch; mixed cosigner origins; existing-partial and atomic multi-input cases; path/key/signature substitution; Android BDK descriptor preflight | Enforce bounded CBOR and card continuity; bind BIP-84 account zero or BIP-48 native-P2WSH multisig account zero through the master transcript plus encrypted child proof; accept only owned PSBT-v0 `SIGHASH_ALL` inputs; validate and preserve policy-member low-S signatures; merge atomically without changing the reviewed transaction |
| Multisig | Generated M-of-N descriptors, invalid thresholds, excessive signer sets, duplicate origins/branches, arbitrary descriptor text | Each policy has 2–20 independent public signers and a threshold inside the signer set |
| Storage corruption | Bounded import overflow, quarantine namespace attacks, corrupt-state rollback fixtures | Never consume unbounded storage input; preserve or restore the original state on failure |
| Fee attacks | Generated absolute/relative fees, threshold boundaries, negative/non-finite/overflow metadata | Invalid metadata or policy excess is blocked; warning/rejection thresholds are monotonic |
| Interrupted signing | Clear/cancel, single-consumption, malformed payload, simultaneous requests | Pending authorization is structurally valid, atomic, single-use, and erasable |
| Recovery | Generated interruption points around temporary eviction and quarantine identifiers | Restoration runs exactly once and a wallet cannot address another wallet's quarantine |

## Release evidence checks

```bash
scripts/release/verify-release-controls.py
python3 -B scripts/verification/test-release-tools.py

COMMIT=$(git rev-parse HEAD)
scripts/release/generate-sbom.py \
  --commit "$COMMIT" \
  --output build/clench-sbom.cdx.json
scripts/release/validate-sbom.py \
  build/clench-sbom.cdx.json \
  --version "$(grep 'versionName' app/build.gradle.kts | sed 's/.*\"\\(.*\\)\".*/\\1/')" \
  --commit "$COMMIT"
```

Run `scripts/release/rebuild-unsigned.sh` only from a clean standalone clone
that contains no keystore or `keystore.properties`. A linked Git worktree is
intentionally refused because AGP cannot reproduce its embedded Git revision
metadata. During a release, the tested primary no-secrets build is A. Two
separate clean build jobs produce B and C with equivalent strict recipes and
without receiving expected APK artifacts. The pre-sign verifier requires A and
B to match byte-for-byte before key access; the post-sign verifier
confirms C against the same approved raw digest. It then signs a copy of C with
the pinned `apksigner` and a disposable verifier-only key, destroys that key, and compares every
normalized ZIP entry with the production-signed APK. No APK entry is excluded.

The release-tool self-test creates synthetic signed/unsigned APK containers and
requires rejection of changed compression, changed payloads, duplicate ZIP
entries, traversal paths, and non-signature `META-INF` files. It also mutates
the generated SBOM and provenance to prove their exact validators reject
missing dependency or resolved-input evidence.

## What simulators do not establish

The Coinkite simulator validates Clench's APDU framing, CBOR parsing, bounds, retry behavior, and card-kind routing. It does not establish:

- NFC field strength, antenna alignment, Android tag-dispatch behavior, or removal timing.
- Coinkite factory certificate authenticity or secure-element cryptography.
- Correct behavior of a specific SATSCARD/TAPSIGNER firmware revision.
- Coldcard, SeedSigner, Keystone, Passport, Jade, OneKey Pro, Krux, or Specter DIY interoperability.
- Physical TAPSIGNER BIP-48/P2WSH multisig interoperability.

Those are physical gates, recorded separately rather than converted into automated claims.

The v0.3.27 record includes one real-card, one-input Mainnet payment performed
with an identified debug candidate. It established useful compatibility for
that card/device/build and confirmed that broadcast remained explicit. It does
not turn simulator or instrumentation results into physical passes, and it
does not establish the final signed v0.3.27 APK, multi-input behavior, the
keyboard follow-up, hostile PIN/NFC cases, or other card/Android firmware. See
[`physical-hardware-gates-v0.3.27.md`](../qa/physical-hardware-gates-v0.3.27.md)
for the exact evidence boundary.

The v0.3.28 release adds automated coverage for OneKey Pro, Krux, Specter DIY,
additional QR encodings, and TAPSIGNER BIP-48/P2WSH multisig. Their physical
rows remain `NOT RUN`; see
[`physical-hardware-gates-v0.3.28.md`](../qa/physical-hardware-gates-v0.3.28.md).
