#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
OUTPUT_APK="${1:-$ROOT_DIR/build/independent/app-release-unsigned.apk}"
if [[ "$OUTPUT_APK" != /* ]]; then
  OUTPUT_APK="$ROOT_DIR/$OUTPUT_APK"
fi

CLENCH_REQUIRE_NO_LOCAL_SIGNING_MATERIAL=1 \
  scripts/release/verify-release-controls.py

if [[ ! -d .git ]]; then
  echo "Refusing independent rebuild outside a standalone Git checkout." >&2
  echo "Linked Git worktrees are not reproducible for AGP version-control metadata." >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Refusing independent rebuild from a dirty worktree." >&2
  exit 1
fi

./gradlew --no-daemon --dependency-verification=strict clean assembleRelease

mapfile -t APK_CANDIDATES < <(
  find app/build/outputs/apk/release \
    -mindepth 1 -maxdepth 1 -type f -name 'app-release*.apk' \
    | LC_ALL=C sort
)
if (( ${#APK_CANDIDATES[@]} != 1 )); then
  printf 'Expected exactly one independently rebuilt release APK, found %s.\n' \
    "${#APK_CANDIDATES[@]}" >&2
  exit 1
fi
APK="${APK_CANDIDATES[0]}"

SOURCE_COMMIT="$(git rev-parse HEAD)"
VERSION_CONTROL_INFO="$(unzip -p "$APK" META-INF/version-control-info.textproto)"
if grep -Fq "generate_error_reason:" <<< "$VERSION_CONTROL_INFO" ||
  ! grep -Fq "revision: \"$SOURCE_COMMIT\"" <<< "$VERSION_CONTROL_INFO"; then
  echo "Independent APK does not contain exact Git revision metadata." >&2
  exit 1
fi

APKSIGNER="${APKSIGNER:-$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | LC_ALL=C sort -V | tail -1)}"
test -x "$APKSIGNER"
if "$APKSIGNER" verify "$APK" >/dev/null 2>&1; then
  echo "Independent rebuild unexpectedly produced a signed APK." >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT_APK")"
cp "$APK" "$OUTPUT_APK"
printf 'Independent unsigned APK: %s\n' "$OUTPUT_APK"
sha256sum "$OUTPUT_APK"
