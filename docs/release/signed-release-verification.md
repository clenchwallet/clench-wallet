# Signed-Release Verification

Use this checklist before installing a Clench Wallet release APK.

## 1. Verify The Source Tag

```bash
git fetch --tags origin
git tag -v vX.Y.Z
git checkout vX.Y.Z
git rev-parse HEAD
```

If tag signing is not available for a release, compare the commit hash against the release notes and an out-of-band announcement from the maintainer.

## 2. Verify Artifact Checksums

Download these files from the GitHub Release:

- `clench-X.Y.Z-release.apk`
- `SHA256SUMS`
- `SHA256SUMS.txt`

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

- `Verified using v1 scheme`: may be present.
- `Verified using v2 scheme`: true.
- `Verified using v3 scheme`: true where supported by the build tools.
- The signer certificate SHA-256 digest matches the digest published for the trusted Clench release key.

The first trusted signer digest must be learned out of band. After that, every upgrade must keep the same signer unless a key-rotation notice is published and independently verified.

## 4. Verify Package And Version

```bash
aapt dump badging clench-X.Y.Z-release.apk | head -1
```

Expected:

- Package: `net.clench.wallet`
- Version name: `X.Y.Z`
- Version code: matches `app/build.gradle.kts`

## 5. Rebuild From Source

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
