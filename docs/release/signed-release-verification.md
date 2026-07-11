# Signed-Release Verification

Use this checklist before installing a Clench Wallet release APK.

## 1. Verify The Source Tag

```bash
git fetch --tags origin
git tag -v vX.Y.Z
git checkout vX.Y.Z
git rev-parse HEAD
```

The release tag must be annotated, cryptographically signed, and shown as verified by GitHub. Do not install a release built from a lightweight or unsigned tag.

## 2. Verify Artifact Checksums

Download these files from the GitHub Release:

- `clench-X.Y.Z-release.apk`
- `SHA256SUMS`
- `SHA256SUMS.txt`
- `RELEASE-MANIFEST.txt`

Then run:

```bash
sha256sum -c SHA256SUMS
```

The APK digest must match exactly.

## 3. Verify APK Signature

Find `apksigner` in your Android SDK build tools, then run:

```bash
apksigner verify --verbose --print-certs clench-X.Y.Z-release.apk
```

Expected:

- `Verified using v2 scheme`: true.
- `Number of signers`: 1.
- Signer certificate SHA-256: `d161d82d633347948079cb5bbae0560c2f85622a51c69f3b4a0d283eefc853ca`.

This digest is the established v0.3.19 signer baseline. Every upgrade must keep the same signer unless a key-rotation notice is published and independently verified.

## 4. Verify Package And Version

```bash
aapt dump badging clench-X.Y.Z-release.apk | head -1
```

Expected:

- Package: `net.clench.wallet`
- Version name: `X.Y.Z`
- Version code: matches `app/build.gradle.kts`

## 5. Verify Build Provenance

With GitHub CLI authenticated, verify that the APK was attested by the release workflow at the expected signed tag and commit:

```bash
gh attestation verify clench-X.Y.Z-release.apk \
  --repo clenchwallet/clench-wallet \
  --signer-workflow clenchwallet/clench-wallet/.github/workflows/release.yml \
  --source-digest EXPECTED_COMMIT_SHA \
  --source-ref refs/tags/vX.Y.Z \
  --deny-self-hosted-runners
```

## 6. Run The Project Verifier

From the signed source checkout, place the four release assets plus `RELEASE-NOTES.md` in `release-artifacts/`, then run:

```bash
VERSION=X.Y.Z \
VERSION_CODE=EXPECTED_CODE \
RELEASE_TAG=vX.Y.Z \
SOURCE_COMMIT=EXPECTED_COMMIT_SHA \
EXPECTED_RELEASE_SIGNER_SHA256=d161d82d633347948079cb5bbae0560c2f85622a51c69f3b4a0d283eefc853ca \
scripts/release/verify-release-bundle.sh release-artifacts
```

## 7. Rebuild From Source

Follow [reproducible-builds.md](reproducible-builds.md), then compare:

```bash
sha256sum app/build/outputs/apk/release/app-release.apk
sha256sum clench-X.Y.Z-release.apk
```

If the hashes differ, compare:

- Source commit.
- Gradle distribution URL.
- Android SDK build tools.
- JDK version.
- `gradle/verification-metadata.xml` hash.
- Signing key.

## Do Not Install If

- The APK is a debug build.
- The package id differs from `net.clench.wallet`.
- The APK checksum is not published or does not match.
- `apksigner verify` fails.
- The signer certificate digest unexpectedly changes.
- The release tag or commit cannot be traced to the project.
