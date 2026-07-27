# Signed-Release Verification

Do not install a Clench production APK until its source, signer, evidence bundle, attestations, and independently rebuilt payload all verify.

## 1. Verify the source tag

```bash
git fetch --tags origin
git tag -v vX.Y.Z
git checkout vX.Y.Z
git rev-parse HEAD
```

The release tag must be annotated, cryptographically signed, and verified by GitHub. Its commit must match the release manifest and attestation source digest.

## 2. Download the complete evidence bundle

- `clench-X.Y.Z-release.apk`
- `clench-X.Y.Z-sbom.cdx.json`
- `PROVENANCE.intoto.jsonl`
- `INDEPENDENT-APK-VERIFICATION.json`
- `SHA256SUMS` and `SHA256SUMS.txt`
- `RELEASE-MANIFEST.txt`
- `RELEASE-NOTES.md`

The release workflow also verifies `RELEASE-NOTES.md` internally against the tagged source.

```bash
sha256sum -c SHA256SUMS
cmp SHA256SUMS SHA256SUMS.txt
```

## 3. Verify APK signature continuity

```bash
apksigner verify --verbose --print-certs clench-X.Y.Z-release.apk
```

Require:

- APK Signature Scheme v2: true.
- Exactly one signer.
- Signer certificate SHA-256:
  `d161d82d633347948079cb5bbae0560c2f85622a51c69f3b4a0d283eefc853ca`.

This is the established Clench signer baseline. An unexpected signer is a hard failure unless a separately authenticated key-rotation notice exists.

## 4. Verify package and version

```bash
aapt dump badging clench-X.Y.Z-release.apk | head -1
```

Require package `net.clench.wallet`, version name `X.Y.Z`, and the version code in tagged `app/build.gradle.kts`.

## 5. Verify both GitHub attestations

Build provenance:

```bash
gh attestation verify clench-X.Y.Z-release.apk \
  --repo clenchwallet/clench-wallet \
  --signer-workflow clenchwallet/clench-wallet/.github/workflows/release.yml \
  --source-digest EXPECTED_COMMIT_SHA \
  --source-ref refs/tags/vX.Y.Z \
  --deny-self-hosted-runners
```

CycloneDX SBOM:

```bash
gh attestation verify clench-X.Y.Z-release.apk \
  --repo clenchwallet/clench-wallet \
  --predicate-type https://cyclonedx.org/bom \
  --signer-workflow clenchwallet/clench-wallet/.github/workflows/release.yml \
  --source-digest EXPECTED_COMMIT_SHA \
  --source-ref refs/tags/vX.Y.Z \
  --deny-self-hosted-runners \
  --format json > clench-sbom-attestation.json

scripts/release/verify-sbom-attestation.py \
  clench-sbom-attestation.json \
  clench-X.Y.Z-sbom.cdx.json
```

The second check requires the verified predicate to equal the published SBOM as parsed JSON; merely finding any SBOM attestation is insufficient.

## 6. Run the complete bundle verifier

From the signed source checkout, place the release workflow's pre-publication bundle in `release-artifacts/`, then run:

```bash
VERSION=X.Y.Z \
VERSION_CODE=EXPECTED_CODE \
RELEASE_TAG=vX.Y.Z \
SOURCE_COMMIT=EXPECTED_COMMIT_SHA \
EXPECTED_RELEASE_SIGNER_SHA256=d161d82d633347948079cb5bbae0560c2f85622a51c69f3b4a0d283eefc853ca \
scripts/release/verify-release-bundle.sh release-artifacts
```

This validates the exact regular-file allowlist, checksum filename allowlist,
tag/commit checkout, source release notes, signer, package/version, SBOM, and
deterministic provenance. Symlinks, directories, unexpected checksum targets,
and extra bundle entries are rejected.

## 7. Independently rebuild

Follow [reproducible-builds.md](reproducible-builds.md). The no-secrets rebuild must be unsigned and its APK payload must match the signed artifact:

```bash
scripts/release/rebuild-unsigned.sh

VERSION=X.Y.Z \
VERSION_CODE=EXPECTED_CODE \
EXPECTED_RELEASE_SIGNER_SHA256=d161d82d633347948079cb5bbae0560c2f85622a51c69f3b4a0d283eefc853ca \
scripts/release/verify-independent-apk.sh \
  release-artifacts \
  app/build/outputs/apk/release/app-release.apk \
  independently-verified.json
```

## Do not install if

- The APK is debug-signed or its package differs.
- Any checksum, tag, source commit, attestation, signer, version, SBOM, or provenance check fails.
- The independent build unexpectedly contains a signature.
- Any non-signature APK payload, compressed byte stream, ordering, local ZIP
  metadata, or archive comment differs.
- Verification requires weakening a key-isolation, dependency-integrity, attestation, or comparison rule.
