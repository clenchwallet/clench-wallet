# Reproducible Builds

This document describes the expected release rebuild process for Clench Wallet.

## Build Environment

Use the versions pinned in the repository:

- JDK: 21
- Android Gradle Plugin: from `gradle/libs.versions.toml`
- Gradle wrapper: `gradle/wrapper/gradle-wrapper.properties`
- Dependencies: `app/gradle.lockfile` and `gradle/verification-metadata.xml`
- Android SDK: compile SDK 36

The release process should use a clean checkout of a signed or otherwise trusted release tag.

## Inputs

Required files:

- `gradlew`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/libs.versions.toml`
- `gradle/verification-metadata.xml`
- `app/gradle.lockfile`
- `app/build.gradle.kts`
- `keystore.properties`
- Release keystore referenced by `keystore.properties`

`keystore.properties` must stay outside version control. It has this shape:

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

## Local Rebuild

From a clean checkout:

```bash
git fetch --tags origin
git checkout vX.Y.Z
./scripts/release/reproducible-build.sh
```

The script runs:

```bash
./gradlew --no-daemon --dependency-verification=strict clean assembleRelease
```

It writes a manifest under `build/release-trust/` with:

- Source commit.
- Version name and version code.
- Gradle distribution URL.
- Dependency verification metadata hash.
- Release APK path.
- Release APK SHA-256 digest.

## Dirty Worktrees

The script refuses to run on a dirty worktree. For local experimentation only:

```bash
CLENCH_ALLOW_DIRTY_REPRO=1 ./scripts/release/reproducible-build.sh
```

Do not publish release attestations from a dirty worktree.

## Dependency Verification

Gradle dependency verification must stay enabled. If a dependency changes:

1. Review why the dependency changed.
2. Review the upstream artifact and release notes.
3. Regenerate verification metadata intentionally.
4. Commit lockfile and verification-metadata changes together.
5. Record the dependency change in the release notes.

## Expected Limits

Android release APK bytes can vary if:

- The release is signed with a different keystore.
- The build environment uses different Android build tools.
- Timestamps or generated resources differ across tool versions.

The release target is deterministic rebuildability from the same source, pinned toolchain, locked dependencies, and same signing key. Independent verifiers should compare the rebuilt unsigned or signed artifact and publish any mismatch with the source commit, tool versions, and artifact digests.
