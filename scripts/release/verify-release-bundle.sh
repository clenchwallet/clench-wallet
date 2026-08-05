#!/usr/bin/env bash
set -euo pipefail

BUNDLE_DIR="${1:-release-artifacts}"
SOURCE_ROOT="${SOURCE_ROOT:-.}"
MODE="${2:-final}"
case "$MODE" in
  final|--pre-independent) ;;
  *) echo "Usage: $0 [bundle-dir] [--pre-independent]" >&2; exit 1 ;;
esac

for name in \
  VERSION \
  VERSION_CODE \
  RELEASE_TAG \
  SOURCE_COMMIT \
  EXPECTED_RELEASE_SIGNER_SHA256 \
  APKSIGNER_BUILD_TOOLS_VERSION \
  EXPECTED_APKSIGNER_SHA256 \
  EXPECTED_APKSIGNER_JAR_SHA256 \
  EXPECTED_AAPT_SHA256; do
  if [[ -z "${!name:-}" ]]; then
    printf 'Required environment variable is empty: %s\n' "$name" >&2
    exit 1
  fi
done

APK="$BUNDLE_DIR/clench-$VERSION-release.apk"
UNSIGNED_APK="$BUNDLE_DIR/clench-$VERSION-unsigned.apk"
MANIFEST="$BUNDLE_DIR/RELEASE-MANIFEST.txt"
RELEASE_NOTES="$BUNDLE_DIR/RELEASE-NOTES.md"
SOURCE_RELEASE_NOTES="$SOURCE_ROOT/docs/release/v$VERSION.md"
SBOM="$BUNDLE_DIR/clench-$VERSION-sbom.cdx.json"
PROVENANCE="$BUNDLE_DIR/PROVENANCE.intoto.jsonl"
INDEPENDENT_REPORT="$BUNDLE_DIR/INDEPENDENT-APK-VERIFICATION.json"
ORIGINAL_BUILD_SUMS="$BUNDLE_DIR/ORIGINAL-UNSIGNED-BUILD-SHA256SUMS"
VERIFIED_BUILD_SUMS="$BUNDLE_DIR/VERIFIED-UNSIGNED-SHA256SUMS"
POST_SIGN_BUILD_SUMS="$BUNDLE_DIR/POST-SIGN-UNSIGNED-BUILD-SHA256SUMS"
UNSIGNED_APPROVAL="$BUNDLE_DIR/UNSIGNED-APPROVAL.txt"

for file in "$APK" "$UNSIGNED_APK" "$MANIFEST" "$RELEASE_NOTES" "$SOURCE_RELEASE_NOTES" \
  "$SBOM" "$PROVENANCE" "$ORIGINAL_BUILD_SUMS" "$VERIFIED_BUILD_SUMS" \
  "$POST_SIGN_BUILD_SUMS" "$UNSIGNED_APPROVAL" \
  "$BUNDLE_DIR/SHA256SUMS" "$BUNDLE_DIR/SHA256SUMS.txt"; do
  test -f "$file"
  test ! -L "$file"
done
if [[ "$MODE" = final ]]; then
  test -f "$INDEPENDENT_REPORT"
  test ! -L "$INDEPENDENT_REPORT"
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
printf '%s\n' \
  "RELEASE-MANIFEST.txt" \
  "RELEASE-NOTES.md" \
  "SHA256SUMS" \
  "SHA256SUMS.txt" \
  "PROVENANCE.intoto.jsonl" \
  "ORIGINAL-UNSIGNED-BUILD-SHA256SUMS" \
  "POST-SIGN-UNSIGNED-BUILD-SHA256SUMS" \
  "UNSIGNED-APPROVAL.txt" \
  "VERIFIED-UNSIGNED-SHA256SUMS" \
  "clench-$VERSION-release.apk" \
  "clench-$VERSION-unsigned.apk" \
  "clench-$VERSION-sbom.cdx.json" \
  | LC_ALL=C sort > "$TEMP_DIR/expected-files"
if [[ "$MODE" = final ]]; then
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
  "ORIGINAL-UNSIGNED-BUILD-SHA256SUMS" \
  "POST-SIGN-UNSIGNED-BUILD-SHA256SUMS" \
  "UNSIGNED-APPROVAL.txt" \
  "VERIFIED-UNSIGNED-SHA256SUMS" \
  "clench-$VERSION-release.apk" \
  "clench-$VERSION-unsigned.apk" \
  "clench-$VERSION-sbom.cdx.json" \
  | LC_ALL=C sort > "$TEMP_DIR/expected-checksum-files"
if [[ "$MODE" = final ]]; then
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

printf '%s\n' \
  "RELEASE-NOTES.md" \
  "clench-$VERSION-sbom.cdx.json" \
  "clench-$VERSION-unsigned.apk" \
  | LC_ALL=C sort > "$TEMP_DIR/expected-original-checksum-files"
awk '
  !/^[0-9a-f]{64}  [A-Za-z0-9._-]+$/ { exit 1 }
  { print $2 }
' "$ORIGINAL_BUILD_SUMS" \
  | LC_ALL=C sort > "$TEMP_DIR/actual-original-checksum-files"
cmp "$TEMP_DIR/expected-original-checksum-files" \
  "$TEMP_DIR/actual-original-checksum-files"
grep -Fxq "$(sha256sum "$RELEASE_NOTES" | awk '{print $1}')  RELEASE-NOTES.md" \
  "$ORIGINAL_BUILD_SUMS"
grep -Fxq "$(sha256sum "$SBOM" | awk '{print $1}')  clench-$VERSION-sbom.cdx.json" \
  "$ORIGINAL_BUILD_SUMS"

printf '%s\n' \
  "RELEASE-NOTES.md" \
  "UNSIGNED-APPROVAL.txt" \
  "clench-$VERSION-sbom.cdx.json" \
  "clench-$VERSION-independent-unsigned.apk" \
  | LC_ALL=C sort > "$TEMP_DIR/expected-verified-checksum-files"
awk '
  !/^[0-9a-f]{64}  [A-Za-z0-9._-]+$/ { exit 1 }
  { print $2 }
' "$VERIFIED_BUILD_SUMS" \
  | LC_ALL=C sort > "$TEMP_DIR/actual-verified-checksum-files"
cmp "$TEMP_DIR/expected-verified-checksum-files" \
  "$TEMP_DIR/actual-verified-checksum-files"
grep -Fxq "$(sha256sum "$RELEASE_NOTES" | awk '{print $1}')  RELEASE-NOTES.md" \
  "$VERIFIED_BUILD_SUMS"
grep -Fxq "$(sha256sum "$SBOM" | awk '{print $1}')  clench-$VERSION-sbom.cdx.json" \
  "$VERIFIED_BUILD_SUMS"
grep -Fxq "$(sha256sum "$UNSIGNED_APPROVAL" | awk '{print $1}')  UNSIGNED-APPROVAL.txt" \
  "$VERIFIED_BUILD_SUMS"
ORIGINAL_UNSIGNED_APK_SHA256="$(
  awk -v name="clench-$VERSION-unsigned.apk" '$2 == name { print $1 }' \
    "$ORIGINAL_BUILD_SUMS"
)"
VERIFIED_UNSIGNED_APK_SHA256="$(
  awk -v name="clench-$VERSION-independent-unsigned.apk" '$2 == name { print $1 }' \
    "$VERIFIED_BUILD_SUMS"
)"
printf '%s\n' "clench-$VERSION-independent-unsigned.apk" \
  > "$TEMP_DIR/expected-post-sign-checksum-files"
awk '
  !/^[0-9a-f]{64}  [A-Za-z0-9._-]+$/ { exit 1 }
  { print $2 }
' "$POST_SIGN_BUILD_SUMS" \
  | LC_ALL=C sort > "$TEMP_DIR/actual-post-sign-checksum-files"
cmp "$TEMP_DIR/expected-post-sign-checksum-files" \
  "$TEMP_DIR/actual-post-sign-checksum-files"
POST_SIGN_UNSIGNED_APK_SHA256="$(
  awk -v name="clench-$VERSION-independent-unsigned.apk" '$2 == name { print $1 }' \
    "$POST_SIGN_BUILD_SUMS"
)"
test "$ORIGINAL_UNSIGNED_APK_SHA256" != ""
test "$VERIFIED_UNSIGNED_APK_SHA256" != ""
test "$POST_SIGN_UNSIGNED_APK_SHA256" != ""
test "$ORIGINAL_UNSIGNED_APK_SHA256" = "$VERIFIED_UNSIGNED_APK_SHA256"
test "$ORIGINAL_UNSIGNED_APK_SHA256" = "$POST_SIGN_UNSIGNED_APK_SHA256"
test "$ORIGINAL_UNSIGNED_APK_SHA256" = \
  "$(sha256sum "$UNSIGNED_APK" | awk '{print $1}')"
{
  echo "versionName=$VERSION"
  echo "versionCode=$VERSION_CODE"
  echo "sourceCommit=$SOURCE_COMMIT"
  echo "signerInputUnsignedSha256=$ORIGINAL_UNSIGNED_APK_SHA256"
  echo "independentUnsignedSha256=$VERIFIED_UNSIGNED_APK_SHA256"
  echo "rawUnsignedByteIdentical=true"
} > "$TEMP_DIR/expected-unsigned-approval"
cmp "$TEMP_DIR/expected-unsigned-approval" "$UNSIGNED_APPROVAL"
(
  cd "$BUNDLE_DIR"
  sha256sum --strict -c SHA256SUMS
)

test "$(git -C "$SOURCE_ROOT" rev-parse HEAD)" = "$SOURCE_COMMIT"
test "$(git -C "$SOURCE_ROOT" rev-parse "refs/tags/$RELEASE_TAG^{commit}")" = "$SOURCE_COMMIT"

APKSIGNER="$ANDROID_HOME/build-tools/$APKSIGNER_BUILD_TOOLS_VERSION/apksigner"
APKSIGNER_JAR="$ANDROID_HOME/build-tools/$APKSIGNER_BUILD_TOOLS_VERSION/lib/apksigner.jar"
APKSIGNER_SHADOW_JAR="$ANDROID_HOME/build-tools/$APKSIGNER_BUILD_TOOLS_VERSION/apksigner.jar"
AAPT="$ANDROID_HOME/build-tools/$APKSIGNER_BUILD_TOOLS_VERSION/aapt"
test -x "$APKSIGNER"
test -f "$APKSIGNER_JAR"
test ! -e "$APKSIGNER_SHADOW_JAR"
test -x "$AAPT"
test "$(sha256sum "$APKSIGNER" | awk '{print $1}')" = \
  "$EXPECTED_APKSIGNER_SHA256"
test "$(sha256sum "$APKSIGNER_JAR" | awk '{print $1}')" = \
  "$EXPECTED_APKSIGNER_JAR_SHA256"
test "$(sha256sum "$AAPT" | awk '{print $1}')" = \
  "$EXPECTED_AAPT_SHA256"

SIGNATURE_REPORT="$(
  "$APKSIGNER" verify --min-sdk-version 26 --verbose --print-certs "$APK"
)"
printf '%s\n' "$SIGNATURE_REPORT"
grep -Fq 'Verified using v1 scheme (JAR signing): false' <<< "$SIGNATURE_REPORT"
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<< "$SIGNATURE_REPORT"
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' <<< "$SIGNATURE_REPORT"
grep -Fq 'Verified using v3.1 scheme (APK Signature Scheme v3.1): false' <<< "$SIGNATURE_REPORT"
grep -Fq 'Verified using v4 scheme (APK Signature Scheme v4): false' <<< "$SIGNATURE_REPORT"
grep -Fq 'Verified for SourceStamp: false' <<< "$SIGNATURE_REPORT"
grep -Fq 'Number of signers: 1' <<< "$SIGNATURE_REPORT"
test ! -e "$APK.idsig"
APK_ZIP_ENTRIES="$(unzip -Z1 "$APK")"
if grep -Eiq '^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$' \
  <<< "$APK_ZIP_ENTRIES"; then
  echo "Release APK unexpectedly contains v1 signature records." >&2
  exit 1
fi
SIGNER_SHA256="$(awk -F': ' '/certificate SHA-256 digest/ {print tolower($NF); exit}' <<< "$SIGNATURE_REPORT")"
test "$SIGNER_SHA256" = "$EXPECTED_RELEASE_SIGNER_SHA256"
if "$APKSIGNER" verify "$UNSIGNED_APK" >/dev/null 2>&1; then
  echo "Published reproducibility input unexpectedly contains an APK signature." >&2
  exit 1
fi
test ! -e "$UNSIGNED_APK.idsig"
UNSIGNED_ZIP_ENTRIES="$(unzip -Z1 "$UNSIGNED_APK")"
if grep -Eiq '^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$' \
  <<< "$UNSIGNED_ZIP_ENTRIES"; then
  echo "Published reproducibility input contains v1 signature records." >&2
  exit 1
fi
{
  echo "versionName=$VERSION"
  echo "versionCode=$VERSION_CODE"
  echo "tag=$RELEASE_TAG"
  echo "commit=$SOURCE_COMMIT"
  echo "gradleDistributionUrl=$(grep '^distributionUrl=' \
    "$SOURCE_ROOT/gradle/wrapper/gradle-wrapper.properties" | cut -d= -f2-)"
  echo "dependencyVerificationSha256=$(sha256sum \
    "$SOURCE_ROOT/gradle/verification-metadata.xml" | awk '{print $1}')"
  echo "sbomSha256=$(sha256sum "$SBOM" | awk '{print $1}')"
  echo "provenanceSha256=$(sha256sum "$PROVENANCE" | awk '{print $1}')"
  echo "apkSignerCertsFollow"
  printf '%s\n' "$SIGNATURE_REPORT"
} > "$TEMP_DIR/expected-release-manifest"
cmp "$TEMP_DIR/expected-release-manifest" "$MANIFEST"

for package_apk in "$APK" "$UNSIGNED_APK"; do
  BADGING="$("$AAPT" dump badging "$package_apk")"
  PACKAGE_LINE="${BADGING%%$'\n'*}"
  printf '%s\n' "$PACKAGE_LINE"
  grep -Fq "package: name='net.clench.wallet'" <<< "$PACKAGE_LINE"
  grep -Fq "versionCode='$VERSION_CODE'" <<< "$PACKAGE_LINE"
  grep -Fq "versionName='$VERSION'" <<< "$PACKAGE_LINE"
done

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
if [[ "$MODE" = final ]]; then
  scripts/release/validate-independent-report.py \
    "$INDEPENDENT_REPORT" \
    --signed-apk "$APK" \
    --unsigned-approval "$UNSIGNED_APPROVAL" \
    --original-unsigned-build-sha256s "$ORIGINAL_BUILD_SUMS" \
    --verified-unsigned-sha256s "$VERIFIED_BUILD_SUMS" \
    --post-sign-unsigned-sha256s "$POST_SIGN_BUILD_SUMS" \
    --expected-release-signer-sha256 "$EXPECTED_RELEASE_SIGNER_SHA256"
  REPORT_SIGNER_INPUT_SHA256="$(
    python3 -B -c \
      'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["signerInputUnsignedApkSha256"])' \
      "$INDEPENDENT_REPORT"
  )"
  REPORT_INDEPENDENT_SHA256="$(
    python3 -B -c \
      'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["independentUnsignedApkSha256"])' \
      "$INDEPENDENT_REPORT"
  )"
  test "$ORIGINAL_UNSIGNED_APK_SHA256" = "$REPORT_SIGNER_INPUT_SHA256"
  test "$VERIFIED_UNSIGNED_APK_SHA256" = "$REPORT_INDEPENDENT_SHA256"
fi

printf 'Release bundle verification passed for Clench Wallet %s.\n' "$VERSION"
