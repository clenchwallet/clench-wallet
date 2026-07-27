#!/usr/bin/env bash
set -euo pipefail

BUNDLE_DIR="${1:-release-artifacts}"
SOURCE_ROOT="${SOURCE_ROOT:-.}"

for name in VERSION VERSION_CODE RELEASE_TAG SOURCE_COMMIT EXPECTED_RELEASE_SIGNER_SHA256; do
  if [[ -z "${!name:-}" ]]; then
    printf 'Required environment variable is empty: %s\n' "$name" >&2
    exit 1
  fi
done

APK="$BUNDLE_DIR/clench-$VERSION-release.apk"
MANIFEST="$BUNDLE_DIR/RELEASE-MANIFEST.txt"
RELEASE_NOTES="$BUNDLE_DIR/RELEASE-NOTES.md"
SOURCE_RELEASE_NOTES="$SOURCE_ROOT/docs/release/v$VERSION.md"
SBOM="$BUNDLE_DIR/clench-$VERSION-sbom.cdx.json"
PROVENANCE="$BUNDLE_DIR/PROVENANCE.intoto.jsonl"
INDEPENDENT_REPORT="$BUNDLE_DIR/INDEPENDENT-APK-VERIFICATION.json"

for file in "$APK" "$MANIFEST" "$RELEASE_NOTES" "$SOURCE_RELEASE_NOTES" \
  "$SBOM" "$PROVENANCE" "$BUNDLE_DIR/SHA256SUMS" "$BUNDLE_DIR/SHA256SUMS.txt"; do
  test -f "$file"
  test ! -L "$file"
done

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
printf '%s\n' \
  "RELEASE-MANIFEST.txt" \
  "RELEASE-NOTES.md" \
  "SHA256SUMS" \
  "SHA256SUMS.txt" \
  "PROVENANCE.intoto.jsonl" \
  "clench-$VERSION-release.apk" \
  "clench-$VERSION-sbom.cdx.json" \
  | LC_ALL=C sort > "$TEMP_DIR/expected-files"
if [[ -f "$INDEPENDENT_REPORT" ]]; then
  printf '%s\n' "INDEPENDENT-APK-VERIFICATION.json" \
    | cat "$TEMP_DIR/expected-files" - \
    | LC_ALL=C sort > "$TEMP_DIR/expected-files-with-independent"
  mv "$TEMP_DIR/expected-files-with-independent" "$TEMP_DIR/expected-files"
fi
find "$BUNDLE_DIR" -mindepth 1 -maxdepth 1 -printf '%f\n' \
  | LC_ALL=C sort > "$TEMP_DIR/actual-files"
cmp "$TEMP_DIR/expected-files" "$TEMP_DIR/actual-files"

cmp "$BUNDLE_DIR/SHA256SUMS" "$BUNDLE_DIR/SHA256SUMS.txt"
printf '%s\n' \
  "PROVENANCE.intoto.jsonl" \
  "RELEASE-NOTES.md" \
  "clench-$VERSION-release.apk" \
  "clench-$VERSION-sbom.cdx.json" \
  > "$TEMP_DIR/expected-checksum-files"
if [[ -f "$INDEPENDENT_REPORT" ]]; then
  printf '%s\n' \
    "INDEPENDENT-APK-VERIFICATION.json" \
    "RELEASE-MANIFEST.txt" \
    | cat "$TEMP_DIR/expected-checksum-files" - \
    | LC_ALL=C sort > "$TEMP_DIR/expected-checksum-files-final"
  mv "$TEMP_DIR/expected-checksum-files-final" "$TEMP_DIR/expected-checksum-files"
fi
awk '
  !/^[0-9a-f]{64}  [A-Za-z0-9._-]+$/ { exit 1 }
  { print $2 }
' "$BUNDLE_DIR/SHA256SUMS" \
  | LC_ALL=C sort > "$TEMP_DIR/actual-checksum-files"
cmp "$TEMP_DIR/expected-checksum-files" "$TEMP_DIR/actual-checksum-files"
cmp "$SOURCE_RELEASE_NOTES" "$RELEASE_NOTES"
(
  cd "$BUNDLE_DIR"
  sha256sum --strict -c SHA256SUMS
)

test "$(git -C "$SOURCE_ROOT" rev-parse HEAD)" = "$SOURCE_COMMIT"
test "$(git -C "$SOURCE_ROOT" rev-parse "refs/tags/$RELEASE_TAG^{commit}")" = "$SOURCE_COMMIT"

if [[ -z "${APKSIGNER:-}" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | LC_ALL=C sort -V | tail -1)"
fi
if [[ -z "${AAPT:-}" ]]; then
  AAPT="$(find "$ANDROID_HOME/build-tools" -name aapt -type f | LC_ALL=C sort -V | tail -1)"
fi
test -x "$APKSIGNER"
test -x "$AAPT"

SIGNATURE_REPORT="$("$APKSIGNER" verify --verbose --print-certs "$APK")"
printf '%s\n' "$SIGNATURE_REPORT"
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<< "$SIGNATURE_REPORT"
grep -Fq 'Number of signers: 1' <<< "$SIGNATURE_REPORT"
SIGNER_SHA256="$(awk -F': ' '/certificate SHA-256 digest/ {print tolower($NF); exit}' <<< "$SIGNATURE_REPORT")"
test "$SIGNER_SHA256" = "$EXPECTED_RELEASE_SIGNER_SHA256"

BADGING="$("$AAPT" dump badging "$APK")"
PACKAGE_LINE="${BADGING%%$'\n'*}"
printf '%s\n' "$PACKAGE_LINE"
grep -Fq "package: name='net.clench.wallet'" <<< "$PACKAGE_LINE"
grep -Fq "versionCode='$VERSION_CODE'" <<< "$PACKAGE_LINE"
grep -Fq "versionName='$VERSION'" <<< "$PACKAGE_LINE"

grep -Fxq "versionName=$VERSION" "$MANIFEST"
grep -Fxq "versionCode=$VERSION_CODE" "$MANIFEST"
grep -Fxq "tag=$RELEASE_TAG" "$MANIFEST"
grep -Fxq "commit=$SOURCE_COMMIT" "$MANIFEST"
grep -Fxq "sbomSha256=$(sha256sum "$SBOM" | awk '{print $1}')" "$MANIFEST"
grep -Fxq "provenanceSha256=$(sha256sum "$PROVENANCE" | awk '{print $1}')" "$MANIFEST"

scripts/release/validate-sbom.py \
  "$SBOM" \
  --version "$VERSION" \
  --commit "$SOURCE_COMMIT"
scripts/release/validate-provenance.py \
  "$PROVENANCE" \
  --apk "$APK" \
  --sbom "$SBOM" \
  --tag "$RELEASE_TAG" \
  --commit "$SOURCE_COMMIT" \
  --repository "${GITHUB_REPOSITORY:-clenchwallet/clench-wallet}"
if [[ -f "$INDEPENDENT_REPORT" ]]; then
  scripts/release/validate-independent-report.py \
    "$INDEPENDENT_REPORT" \
    --signed-apk "$APK"
fi

printf 'Release bundle verification passed for Clench Wallet %s.\n' "$VERSION"
