#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

if [[ "${CLENCH_ALLOW_DIRTY_REPRO:-0}" != "1" ]]; then
  if [[ -n "$(git status --porcelain)" ]]; then
    echo "Refusing reproducible build from a dirty worktree." >&2
    echo "Commit or stash changes, or set CLENCH_ALLOW_DIRTY_REPRO=1 for local experiments." >&2
    exit 1
  fi
fi

if [[ ! -f keystore.properties ]]; then
  echo "Missing keystore.properties; release rebuilds require the release signing keystore." >&2
  exit 1
fi

OUT_DIR="${CLENCH_RELEASE_TRUST_DIR:-build/release-trust}"
mkdir -p "$OUT_DIR"

VERSION_NAME="$(grep 'versionName' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')"
VERSION_CODE="$(grep 'versionCode' app/build.gradle.kts | awk '{print $3}')"
COMMIT="$(git rev-parse HEAD)"
GRADLE_URL="$(grep '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties | cut -d= -f2-)"
VERIFICATION_SHA="$(sha256sum gradle/verification-metadata.xml | awk '{print $1}')"

GRADLE_FLAGS=(--no-daemon --dependency-verification=strict)
if [[ "${CLENCH_GRADLE_OFFLINE:-0}" == "1" ]]; then
  GRADLE_FLAGS+=(--offline)
fi

./gradlew "${GRADLE_FLAGS[@]}" clean assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
  echo "Release APK not found at $APK" >&2
  exit 1
fi

RELEASE_APK="$OUT_DIR/clench-${VERSION_NAME}-release.apk"
cp "$APK" "$RELEASE_APK"

APK_SHA="$(sha256sum "$RELEASE_APK" | awk '{print $1}')"
MANIFEST="$OUT_DIR/clench-${VERSION_NAME}-release-manifest.txt"

{
  echo "name=Clench Wallet"
  echo "versionName=$VERSION_NAME"
  echo "versionCode=$VERSION_CODE"
  echo "commit=$COMMIT"
  echo "gradleDistributionUrl=$GRADLE_URL"
  echo "dependencyVerificationSha256=$VERIFICATION_SHA"
  echo "apk=$RELEASE_APK"
  echo "apkSha256=$APK_SHA"
  echo "builtAtUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$MANIFEST"

sha256sum "$RELEASE_APK" > "$OUT_DIR/SHA256SUMS"

echo "Release APK: $RELEASE_APK"
echo "Release APK SHA-256: $APK_SHA"
echo "Manifest: $MANIFEST"
