#!/usr/bin/env bash
set -euo pipefail

BUNDLE_DIR="${1:-release-artifacts}"
INDEPENDENT_APK="${2:-app/build/outputs/apk/release/app-release.apk}"
REPORT="${3:-$BUNDLE_DIR/INDEPENDENT-APK-VERIFICATION.json}"

for name in VERSION VERSION_CODE EXPECTED_RELEASE_SIGNER_SHA256; do
  if [[ -z "${!name:-}" ]]; then
    printf 'Required environment variable is empty: %s\n' "$name" >&2
    exit 1
  fi
done

SIGNED_APK="$BUNDLE_DIR/clench-$VERSION-release.apk"
test -f "$SIGNED_APK"
test -f "$INDEPENDENT_APK"

if [[ -z "${APKSIGNER:-}" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | LC_ALL=C sort -V | tail -1)"
fi
if [[ -z "${AAPT:-}" ]]; then
  AAPT="$(find "$ANDROID_HOME/build-tools" -name aapt -type f | LC_ALL=C sort -V | tail -1)"
fi
test -x "$APKSIGNER"
test -x "$AAPT"

SIGNED_REPORT="$("$APKSIGNER" verify --verbose --print-certs "$SIGNED_APK")"
SIGNER_SHA256="$(awk -F': ' '/certificate SHA-256 digest/ {print tolower($NF); exit}' <<< "$SIGNED_REPORT")"
test "$SIGNER_SHA256" = "$EXPECTED_RELEASE_SIGNER_SHA256"

if "$APKSIGNER" verify "$INDEPENDENT_APK" >/dev/null 2>&1; then
  echo "Independent APK unexpectedly contains a valid signature." >&2
  exit 1
fi

for apk in "$SIGNED_APK" "$INDEPENDENT_APK"; do
  BADGING="$("$AAPT" dump badging "$apk")"
  PACKAGE_LINE="${BADGING%%$'\n'*}"
  grep -Fq "package: name='net.clench.wallet'" <<< "$PACKAGE_LINE"
  grep -Fq "versionCode='$VERSION_CODE'" <<< "$PACKAGE_LINE"
  grep -Fq "versionName='$VERSION'" <<< "$PACKAGE_LINE"
done

scripts/release/compare-apk-payloads.py \
  "$SIGNED_APK" \
  "$INDEPENDENT_APK" \
  --report "$REPORT"
