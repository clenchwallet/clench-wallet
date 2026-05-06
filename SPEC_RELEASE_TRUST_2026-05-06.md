# Release Trust Spec - 2026-05-06

## Scope

Implement wallet gap item #6:

> Release trust: reproducible builds, signed-release verification docs, threat model, audit path.

## Existing Coverage

Clench already has:
- Gradle dependency locking.
- Gradle dependency verification metadata.
- Security reporting policy under `.github/SECURITY.md`.
- Security hardening notes for earlier wallet-risk fixes.

## Changes

1. Release workflow hardening
   - Stop publishing debug APKs from ordinary `master` pushes.
   - Keep debug APKs as short-retention CI artifacts only.
   - Add a tag-only release workflow for `v*` tags.
   - Require release keystore secrets for signed release artifacts.
   - Generate SHA-256 manifests and verification metadata for release assets.

2. Reproducible build path
   - Document the pinned build environment and local release rebuild process.
   - Add a helper script that records the source commit, Gradle wrapper, dependency-verification file, release APK path, and SHA-256 digest.
   - Make the script refuse dirty worktrees unless explicitly overridden.

3. Signed-release verification docs
   - Document how users verify source tag, artifact checksum, APK signature presence, APK signer certificate digest, package id, version name, and version code.
   - Document what cannot be proven until a first trusted signer certificate digest is published out of band.

4. Threat model and audit path
   - Add a wallet-focused threat model covering in-scope assets, actors, boundaries, assumptions, and residual risks.
   - Add an audit path describing security-sensitive review areas, release gates, dependency review, and external-audit intake.

## Verification

- `bash -n scripts/release/reproducible-build.sh`
- `./gradlew testDebugUnitTest assembleDebug`
- `git diff --check`
