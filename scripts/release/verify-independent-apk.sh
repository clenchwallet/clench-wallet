#!/usr/bin/env bash
set -euo pipefail

BUNDLE_DIR="${1:-release-artifacts}"
INDEPENDENT_APK="${2:-app/build/outputs/apk/release/app-release.apk}"
REPORT="${3:-$BUNDLE_DIR/INDEPENDENT-APK-VERIFICATION.json}"
SIGNER_INPUT_UNSIGNED="${4:-}"

for name in \
  VERSION \
  VERSION_CODE \
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

if [[ -z "$SIGNER_INPUT_UNSIGNED" ]]; then
  echo "Original signer-input unsigned APK path is required." >&2
  exit 1
fi

SIGNED_APK="$BUNDLE_DIR/clench-$VERSION-release.apk"
test -f "$SIGNED_APK"
test -f "$INDEPENDENT_APK"
test -f "$SIGNER_INPUT_UNSIGNED"

APKSIGNER="$ANDROID_HOME/build-tools/$APKSIGNER_BUILD_TOOLS_VERSION/apksigner"
APKSIGNER_JAR="$ANDROID_HOME/build-tools/$APKSIGNER_BUILD_TOOLS_VERSION/lib/apksigner.jar"
APKSIGNER_SHADOW_JAR="$ANDROID_HOME/build-tools/$APKSIGNER_BUILD_TOOLS_VERSION/apksigner.jar"
AAPT="$ANDROID_HOME/build-tools/$APKSIGNER_BUILD_TOOLS_VERSION/aapt"
: "${JAVA_HOME:?JAVA_HOME must identify the pinned JDK used for verification}"
KEYTOOL="$JAVA_HOME/bin/keytool"
test -x "$APKSIGNER"
test -f "$APKSIGNER_JAR"
test ! -e "$APKSIGNER_SHADOW_JAR"
test -x "$AAPT"
test -x "$KEYTOOL"
test "$(sha256sum "$APKSIGNER" | awk '{print $1}')" = \
  "$EXPECTED_APKSIGNER_SHA256"
test "$(sha256sum "$APKSIGNER_JAR" | awk '{print $1}')" = \
  "$EXPECTED_APKSIGNER_JAR_SHA256"
test "$(sha256sum "$AAPT" | awk '{print $1}')" = \
  "$EXPECTED_AAPT_SHA256"

SIGNED_REPORT="$(
  "$APKSIGNER" verify --min-sdk-version 26 --verbose --print-certs "$SIGNED_APK"
)"
grep -Fq 'Verified using v1 scheme (JAR signing): false' <<< "$SIGNED_REPORT"
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<< "$SIGNED_REPORT"
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' <<< "$SIGNED_REPORT"
grep -Fq 'Verified using v3.1 scheme (APK Signature Scheme v3.1): false' <<< "$SIGNED_REPORT"
grep -Fq 'Verified using v4 scheme (APK Signature Scheme v4): false' <<< "$SIGNED_REPORT"
grep -Fq 'Verified for SourceStamp: false' <<< "$SIGNED_REPORT"
grep -Fq 'Number of signers: 1' <<< "$SIGNED_REPORT"
SIGNER_SHA256="$(awk -F': ' '/certificate SHA-256 digest/ {print tolower($NF); exit}' <<< "$SIGNED_REPORT")"
test "$SIGNER_SHA256" = "$EXPECTED_RELEASE_SIGNER_SHA256"
test ! -e "$SIGNED_APK.idsig"
SIGNED_ZIP_ENTRIES="$(unzip -Z1 "$SIGNED_APK")"
if grep -Eiq '^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$' \
  <<< "$SIGNED_ZIP_ENTRIES"; then
  echo "Release APK unexpectedly contains v1 signature records." >&2
  exit 1
fi

for unsigned_apk in "$SIGNER_INPUT_UNSIGNED" "$INDEPENDENT_APK"; do
  if "$APKSIGNER" verify "$unsigned_apk" >/dev/null 2>&1; then
    echo "Unsigned APK unexpectedly contains a valid signature: $unsigned_apk" >&2
    exit 1
  fi
done

SIGNER_INPUT_SHA256_BEFORE="$(sha256sum "$SIGNER_INPUT_UNSIGNED" | awk '{print $1}')"
INDEPENDENT_SHA256_BEFORE="$(sha256sum "$INDEPENDENT_APK" | awk '{print $1}')"
if [[ "$SIGNER_INPUT_SHA256_BEFORE" != "$INDEPENDENT_SHA256_BEFORE" ]]; then
  echo "Original signer input and independent raw unsigned APK are not byte-identical." >&2
  exit 1
fi

for apk in "$SIGNED_APK" "$SIGNER_INPUT_UNSIGNED" "$INDEPENDENT_APK"; do
  BADGING="$("$AAPT" dump badging "$apk")"
  PACKAGE_LINE="${BADGING%%$'\n'*}"
  grep -Fq "package: name='net.clench.wallet'" <<< "$PACKAGE_LINE"
  grep -Fq "versionCode='$VERSION_CODE'" <<< "$PACKAGE_LINE"
  grep -Fq "versionName='$VERSION'" <<< "$PACKAGE_LINE"
done

# apksigner intentionally replaces Android Gradle Plugin's zero-filled local
# ZIP alignment padding with the APK Alignment Extra Field (0xd935) before it
# writes v2/v3 signatures. Reproduce that non-secret, deterministic packaging
# transformation with a disposable verifier-only key so local headers remain
# part of the exact comparison. The original unsigned APK stays untouched and
# its whole-file hash is recorded in the public report.
NORMALIZATION_DIR="$(mktemp -d "${TMPDIR:-/tmp}/clench-apk-normalization.XXXXXX")"
EPHEMERAL_KEYSTORE="$NORMALIZATION_DIR/verifier-only.p12"
NORMALIZED_APK="$NORMALIZATION_DIR/independent-apksigner-normalized.apk"
EPHEMERAL_KEYSTORE_PASSWORD="clench-ephemeral-verifier-only"
cleanup_normalization() {
  if [[ -f "$EPHEMERAL_KEYSTORE" ]]; then
    if command -v shred >/dev/null 2>&1; then
      shred -u "$EPHEMERAL_KEYSTORE" 2>/dev/null || true
    fi
    rm -f "$EPHEMERAL_KEYSTORE"
  fi
  rm -f "$NORMALIZED_APK" "$NORMALIZED_APK.idsig"
  rmdir "$NORMALIZATION_DIR" 2>/dev/null || true
}
trap cleanup_normalization EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
umask 077

"$KEYTOOL" -genkeypair \
  -keystore "$EPHEMERAL_KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$EPHEMERAL_KEYSTORE_PASSWORD" \
  -keypass "$EPHEMERAL_KEYSTORE_PASSWORD" \
  -alias clench-independent-verifier \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 1 \
  -dname "CN=Disposable Clench APK Verifier" \
  -noprompt >/dev/null 2>&1
test -f "$EPHEMERAL_KEYSTORE"
export EPHEMERAL_KEYSTORE_PASSWORD
"$APKSIGNER" sign \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --verity-enabled false \
  --min-sdk-version 26 \
  --alignment-preserved false \
  --lib-page-alignment 16384 \
  --ks "$EPHEMERAL_KEYSTORE" \
  --ks-key-alias clench-independent-verifier \
  --ks-pass env:EPHEMERAL_KEYSTORE_PASSWORD \
  --key-pass env:EPHEMERAL_KEYSTORE_PASSWORD \
  --out "$NORMALIZED_APK" \
  "$INDEPENDENT_APK"
test ! -e "$NORMALIZED_APK.idsig"

if command -v shred >/dev/null 2>&1; then
  shred -u "$EPHEMERAL_KEYSTORE" 2>/dev/null || true
fi
rm -f "$EPHEMERAL_KEYSTORE"
unset EPHEMERAL_KEYSTORE_PASSWORD
test ! -e "$EPHEMERAL_KEYSTORE"

NORMALIZED_REPORT="$(
  "$APKSIGNER" verify --min-sdk-version 26 --verbose --print-certs \
    "$NORMALIZED_APK"
)"
grep -Fq 'Verified using v1 scheme (JAR signing): false' <<< "$NORMALIZED_REPORT"
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<< "$NORMALIZED_REPORT"
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' <<< "$NORMALIZED_REPORT"
grep -Fq 'Verified using v3.1 scheme (APK Signature Scheme v3.1): false' <<< "$NORMALIZED_REPORT"
grep -Fq 'Verified using v4 scheme (APK Signature Scheme v4): false' <<< "$NORMALIZED_REPORT"
grep -Fq 'Verified for SourceStamp: false' <<< "$NORMALIZED_REPORT"
grep -Fq 'Number of signers: 1' <<< "$NORMALIZED_REPORT"
NORMALIZED_SIGNER_SHA256="$(
  awk -F': ' '/certificate SHA-256 digest/ {print tolower($NF); exit}' \
    <<< "$NORMALIZED_REPORT"
)"
test -n "$NORMALIZED_SIGNER_SHA256"
test "$NORMALIZED_SIGNER_SHA256" != "$EXPECTED_RELEASE_SIGNER_SHA256"
NORMALIZED_ZIP_ENTRIES="$(unzip -Z1 "$NORMALIZED_APK")"
if grep -Eiq '^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$' \
  <<< "$NORMALIZED_ZIP_ENTRIES"; then
  echo "Normalized APK unexpectedly contains v1 signature records." >&2
  exit 1
fi
SIGNER_INPUT_SHA256_AFTER="$(sha256sum "$SIGNER_INPUT_UNSIGNED" | awk '{print $1}')"
INDEPENDENT_SHA256_AFTER="$(sha256sum "$INDEPENDENT_APK" | awk '{print $1}')"
if [[ "$SIGNER_INPUT_SHA256_AFTER" != "$SIGNER_INPUT_SHA256_BEFORE" ]]; then
  echo "Original signer-input APK changed during normalization." >&2
  exit 1
fi
if [[ "$INDEPENDENT_SHA256_AFTER" != "$INDEPENDENT_SHA256_BEFORE" ]]; then
  echo "Independent raw unsigned APK changed during normalization." >&2
  exit 1
fi

scripts/release/compare-apk-payloads.py \
  "$SIGNED_APK" \
  "$NORMALIZED_APK" \
  --unsigned-approval "$BUNDLE_DIR/UNSIGNED-APPROVAL.txt" \
  --original-unsigned-build-sha256s \
    "$BUNDLE_DIR/ORIGINAL-UNSIGNED-BUILD-SHA256SUMS" \
  --verified-unsigned-sha256s "$BUNDLE_DIR/VERIFIED-UNSIGNED-SHA256SUMS" \
  --post-sign-unsigned-sha256s \
    "$BUNDLE_DIR/POST-SIGN-UNSIGNED-BUILD-SHA256SUMS" \
  --normalization-signer-certificate-sha256 "$NORMALIZED_SIGNER_SHA256" \
  --release-signer-certificate-sha256 "$SIGNER_SHA256" \
  --signer-input-unsigned "$SIGNER_INPUT_UNSIGNED" \
  --independent-unsigned "$INDEPENDENT_APK" \
  --comparison-preparation apksigner-v2-v3-ephemeral-rsa4096 \
  --report "$REPORT"
