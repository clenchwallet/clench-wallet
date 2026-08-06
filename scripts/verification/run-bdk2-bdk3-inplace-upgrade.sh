#!/usr/bin/env bash
set -euo pipefail

umask 077

readonly DEFAULT_BDK2_COMMIT="cc2d36132bf6fb4162093e2361f414f63297e1d4"
# The consumer is the clean worktree's exact candidate commit. The resolved immutable SHA is
# recorded in evidence; a commit self-hash cannot be embedded in the same commit.
readonly DEFAULT_BDK3_COMMIT="HEAD"
readonly EXPECTED_BDK2_VERSION="2.3.1"
readonly EXPECTED_BDK3_VERSION="3.0.0"
readonly TARGET_PACKAGE="net.clench.wallet.debug"
readonly TEST_PACKAGE="net.clench.wallet.debug.test"
readonly TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly DATABASE_NAME="wallet_00000000-0000-4000-8000-000000000326.db"
readonly PUBLIC_EVIDENCE="bdk2-to-bdk3-public-evidence.properties"
readonly SAFE_RESULT="bdk2-to-bdk3-result.properties"
readonly SEEDER_CLASS="net.clench.wallet.verification.bdkupgrade.Bdk2PersistedWalletSeederTest"
readonly PHASE_ONE_CLASS="net.clench.wallet.verification.bdkupgrade.Bdk3PersistedWalletVerifierPhaseOneTest"
readonly PHASE_TWO_CLASS="net.clench.wallet.verification.bdkupgrade.Bdk3PersistedWalletVerifierPhaseTwoTest"

fail() {
  printf 'BDK upgrade gate: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

require_command adb
require_command git
require_command keytool
require_command python3
require_command sha256sum

query_installed_packages() {
  local package_listing
  package_listing="$(adb -s "$ADB_SERIAL" shell cmd package list packages 2>/dev/null | tr -d '\r')" ||
    return 1
  [[ "$package_listing" == *"package:"* ]] || return 1
  printf '%s\n' "$package_listing"
}

package_is_installed() {
  local package_listing="$1"
  local package_name="$2"
  local package_line
  while IFS= read -r package_line; do
    [[ "$package_line" == "package:$package_name" ]] && return 0
  done <<<"$package_listing"
  return 1
}

# Exact-commit evidence must never honor local object substitution.
export GIT_NO_REPLACE_OBJECTS=1

[[ "${CLENCH_BDK_UPGRADE_ALLOW_EMULATOR_RESET:-}" == "YES" ]] ||
  fail "set CLENCH_BDK_UPGRADE_ALLOW_EMULATOR_RESET=YES to authorize clearing only $TARGET_PACKAGE on a dedicated emulator"
[[ -n "${ADB_SERIAL:-}" ]] || fail "ADB_SERIAL must name a dedicated Android emulator"

SOURCE_ROOT="$(git rev-parse --show-toplevel)" || fail "could not resolve source root"
[[ -n "$SOURCE_ROOT" && -d "$SOURCE_ROOT" ]] || fail "resolved source root is invalid"
readonly SOURCE_ROOT
SOURCE_STATUS="$(git -C "$SOURCE_ROOT" status --porcelain=v1 --untracked-files=all)" ||
  fail "could not inspect source worktree status"
[[ -z "$SOURCE_STATUS" ]] ||
  fail "source worktree must be clean"
GIT_COMMON_DIR="$(git -C "$SOURCE_ROOT" rev-parse --path-format=absolute --git-common-dir)" ||
  fail "could not resolve Git common directory"
[[ -n "$GIT_COMMON_DIR" && -d "$GIT_COMMON_DIR" ]] || fail "resolved Git common directory is invalid"
readonly GIT_COMMON_DIR
REPLACE_REFS="$(git -C "$SOURCE_ROOT" for-each-ref --format='%(refname)' refs/replace/)" ||
  fail "could not inspect source replacement refs"
[[ -z "$REPLACE_REFS" ]] ||
  fail "source repository contains forbidden replace refs"
[[ ! -s "$GIT_COMMON_DIR/info/grafts" ]] ||
  fail "source repository contains a forbidden legacy graft file"

readonly HARNESS_ROOT="$SOURCE_ROOT/scripts/verification/bdk-wallet-upgrade"
readonly BDK2_FIXTURE="$HARNESS_ROOT/fixtures/bdk2/Bdk2PersistedWalletSeederTest.kt"
readonly BDK3_FIXTURE="$HARNESS_ROOT/fixtures/bdk3/Bdk3PersistedWalletVerifierTest.kt"
[[ -f "$BDK2_FIXTURE" && -f "$BDK3_FIXTURE" ]] || fail "test-only fixture sources are missing"

BDK2_COMMIT="$(git -C "$SOURCE_ROOT" rev-parse "${CLENCH_BDK2_COMMIT:-$DEFAULT_BDK2_COMMIT}^{commit}")" ||
  fail "could not resolve exact BDK2 producer commit"
BDK3_COMMIT="$(git -C "$SOURCE_ROOT" rev-parse "${CLENCH_BDK3_COMMIT:-$DEFAULT_BDK3_COMMIT}^{commit}")" ||
  fail "could not resolve exact BDK3 consumer commit"
readonly BDK2_COMMIT BDK3_COMMIT
[[ "$BDK2_COMMIT" != "$BDK3_COMMIT" ]] || fail "producer and consumer commits must differ"
git -C "$SOURCE_ROOT" merge-base --is-ancestor "$BDK2_COMMIT" "$BDK3_COMMIT" ||
  fail "BDK3 consumer must descend from the exact protected BDK2 producer"

require_bdk_version() {
  local commit="$1"
  local expected="$2"
  local actual
  actual="$(git -C "$SOURCE_ROOT" show "$commit:gradle/libs.versions.toml" |
    sed -n 's/^bdk = "\([^"]*\)"$/\1/p')"
  [[ "$actual" == "$expected" ]] ||
    fail "commit $commit resolves BDK $actual, expected $expected"
}

require_bdk_version "$BDK2_COMMIT" "$EXPECTED_BDK2_VERSION"
require_bdk_version "$BDK3_COMMIT" "$EXPECTED_BDK3_VERSION"

WORK_ROOT="$(mktemp -d /tmp/clench-bdk-upgrade.XXXXXX)" ||
  fail "could not create isolated upgrade work directory"
[[ "$WORK_ROOT" == /tmp/clench-bdk-upgrade.* && -d "$WORK_ROOT" ]] ||
  fail "isolated upgrade work directory is outside the permitted path"
readonly WORK_ROOT
readonly BDK2_TREE="$WORK_ROOT/bdk2"
readonly BDK3_TREE="$WORK_ROOT/bdk3"
readonly HARNESS_ANDROID_USER_HOME="$WORK_ROOT/android-user-home"
DEVICE_TOUCHED=0
WORKTREES_ADDED=0
cleanup() {
  local status=$?
  local cleanup_failed=0
  local installed_packages package_name
  trap - EXIT INT TERM
  set +e
  if [[ "$DEVICE_TOUCHED" == "1" ]]; then
    installed_packages="$(query_installed_packages)"
    if [[ $? -ne 0 ]]; then
      printf 'BDK upgrade gate: cleanup could not query installed packages\n' >&2
      cleanup_failed=1
    else
      for package_name in "$TEST_PACKAGE" "$TARGET_PACKAGE"; do
        if package_is_installed "$installed_packages" "$package_name"; then
          if ! adb -s "$ADB_SERIAL" uninstall "$package_name" >/dev/null 2>&1; then
            printf 'BDK upgrade gate: cleanup failed to uninstall package: %s\n' "$package_name" >&2
            cleanup_failed=1
            continue
          fi
          installed_packages="$(query_installed_packages)"
          if [[ $? -ne 0 ]]; then
            printf 'BDK upgrade gate: cleanup could not re-query installed packages\n' >&2
            cleanup_failed=1
            break
          fi
          if package_is_installed "$installed_packages" "$package_name"; then
            printf 'BDK upgrade gate: cleanup failed to remove package: %s\n' "$package_name" >&2
            cleanup_failed=1
          fi
        fi
      done
    fi
  fi
  if [[ "$WORKTREES_ADDED" == "1" ]]; then
    git -C "$SOURCE_ROOT" worktree remove --force "$BDK2_TREE" >/dev/null 2>&1
    git -C "$SOURCE_ROOT" worktree remove --force "$BDK3_TREE" >/dev/null 2>&1
  fi
  case "$WORK_ROOT" in
    /tmp/clench-bdk-upgrade.*) rm -rf -- "$WORK_ROOT" ;;
  esac
  if [[ "$status" -eq 0 && "$cleanup_failed" -ne 0 ]]; then
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

readonly BUILD_TOOLS_VERSION="${CLENCH_BDK_UPGRADE_BUILD_TOOLS_VERSION:-35.0.0}"
readonly SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK_ROOT" ]] || fail "ANDROID_SDK_ROOT or ANDROID_HOME must be set"
readonly AAPT="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/aapt"
readonly APKSIGNER="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/apksigner"
[[ -x "$AAPT" && -x "$APKSIGNER" ]] || fail "pinned Android Build Tools $BUILD_TOOLS_VERSION are unavailable"

EVIDENCE_DIR="${CLENCH_BDK_UPGRADE_EVIDENCE_DIR:-$SOURCE_ROOT/build/reports/bdk2-bdk3-inplace-upgrade}"
if [[ "$EVIDENCE_DIR" != /* ]]; then
  EVIDENCE_DIR="$SOURCE_ROOT/$EVIDENCE_DIR"
fi
readonly EVIDENCE_DIR
if [[ -d "$EVIDENCE_DIR" ]] && find "$EVIDENCE_DIR" -mindepth 1 -print -quit | grep -q .; then
  fail "evidence directory must be absent or empty: $EVIDENCE_DIR"
fi
mkdir -p "$EVIDENCE_DIR" "$HARNESS_ANDROID_USER_HOME"

# Mark cleanup active before the first add so a partial worktree setup cannot leave Git metadata
# behind if the second add fails.
WORKTREES_ADDED=1
git -C "$SOURCE_ROOT" worktree add --quiet --detach "$BDK2_TREE" "$BDK2_COMMIT"
git -C "$SOURCE_ROOT" worktree add --quiet --detach "$BDK3_TREE" "$BDK3_COMMIT"

BDK2_TREE_STATUS="$(git -C "$BDK2_TREE" status --porcelain=v1 --untracked-files=all)" ||
  fail "could not inspect BDK2 checkout status"
BDK3_TREE_STATUS="$(git -C "$BDK3_TREE" status --porcelain=v1 --untracked-files=all)" ||
  fail "could not inspect BDK3 checkout status"
[[ -z "$BDK2_TREE_STATUS" ]] || fail "BDK2 checkout is not clean"
[[ -z "$BDK3_TREE_STATUS" ]] || fail "BDK3 checkout is not clean"

readonly TEST_SOURCE_REL="app/src/androidTest/java/net/clench/wallet/verification/bdkupgrade"
install -D -m 0600 "$BDK2_FIXTURE" "$BDK2_TREE/$TEST_SOURCE_REL/Bdk2PersistedWalletSeederTest.kt"
install -D -m 0600 "$BDK3_FIXTURE" "$BDK3_TREE/$TEST_SOURCE_REL/Bdk3PersistedWalletVerifierTest.kt"

BDK2_OVERLAY_STATUS="$(git -C "$BDK2_TREE" status --porcelain=v1 --untracked-files=all)"
BDK3_OVERLAY_STATUS="$(git -C "$BDK3_TREE" status --porcelain=v1 --untracked-files=all)"
[[ "$BDK2_OVERLAY_STATUS" == "?? $TEST_SOURCE_REL/Bdk2PersistedWalletSeederTest.kt" ]] ||
  fail "unexpected BDK2 source overlay"
[[ "$BDK3_OVERLAY_STATUS" == "?? $TEST_SOURCE_REL/Bdk3PersistedWalletVerifierTest.kt" ]] ||
  fail "unexpected BDK3 source overlay"

if find "$BDK2_TREE" "$BDK3_TREE" \
  -path '*/.git' -prune -o -type f \
  \( -name 'keystore.properties' -o -name '*.keystore' -o -name '*.jks' -o -name '*.p12' -o -name '*.pfx' \) \
  -print -quit | grep -q .; then
  fail "source checkout unexpectedly contains signing material"
fi

# A disposable, test-only debug signer is shared by both exact-source builds solely so Android
# permits adb install -r to replace BDK2 with BDK3 without clearing target-app data.
keytool -genkeypair -noprompt \
  -keystore "$HARNESS_ANDROID_USER_HOME/debug.keystore" \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -dname "CN=Clench BDK Upgrade Test, OU=Instrumentation, O=Clench Test Fixture, C=XX" \
  -keyalg RSA -keysize 3072 -validity 30 >/dev/null 2>&1

DISPOSABLE_CERT_DIGEST="$(
  keytool -exportcert \
    -keystore "$HARNESS_ANDROID_USER_HOME/debug.keystore" \
    -storepass android \
    -alias androiddebugkey |
    sha256sum | awk '{print $1}'
)" || fail "could not fingerprint the disposable test certificate"
[[ "$DISPOSABLE_CERT_DIGEST" =~ ^[0-9a-f]{64}$ ]] ||
  fail "could not fingerprint the disposable test certificate"
readonly DISPOSABLE_CERT_DIGEST

GRADLE_ARGS=(
  --no-daemon
  --no-build-cache
  --dependency-verification=strict
  --stacktrace
  "--max-workers=${CLENCH_BDK_UPGRADE_MAX_WORKERS:-2}"
)
if [[ "${CLENCH_BDK_UPGRADE_OFFLINE:-0}" == "1" ]]; then
  GRADLE_ARGS+=(--offline)
fi

build_test_pair() {
  local tree="$1"
  local label="$2"
  (
    cd "$tree"
    ANDROID_USER_HOME="$HARNESS_ANDROID_USER_HOME" \
      ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest "${GRADLE_ARGS[@]}" \
      2>&1 | tee "$EVIDENCE_DIR/$label.gradle.txt"
  )
}

build_test_pair "$BDK2_TREE" bdk2
build_test_pair "$BDK3_TREE" bdk3

readonly BDK2_APK="$BDK2_TREE/app/build/outputs/apk/debug/app-debug.apk"
readonly BDK2_TEST_APK="$BDK2_TREE/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
readonly BDK3_APK="$BDK3_TREE/app/build/outputs/apk/debug/app-debug.apk"
readonly BDK3_TEST_APK="$BDK3_TREE/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
for apk in "$BDK2_APK" "$BDK2_TEST_APK" "$BDK3_APK" "$BDK3_TEST_APK"; do
  [[ -s "$apk" ]] || fail "expected APK missing: $apk"
  "$APKSIGNER" verify "$apk" >/dev/null
done

apk_package() {
  "$AAPT" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1
}

apk_target_package() {
  "$AAPT" dump xmltree "$1" AndroidManifest.xml |
    sed -n 's/.*android:targetPackage[^=]*="\([^"]*\)".*/\1/p' | head -n 1
}

certificate_digest() {
  "$APKSIGNER" verify --print-certs "$1" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1
}

BDK2_PACKAGE="$(apk_package "$BDK2_APK")" || fail "could not inspect BDK2 package"
BDK3_PACKAGE="$(apk_package "$BDK3_APK")" || fail "could not inspect BDK3 package"
BDK2_TEST_PACKAGE="$(apk_package "$BDK2_TEST_APK")" || fail "could not inspect BDK2 test package"
BDK3_TEST_PACKAGE="$(apk_package "$BDK3_TEST_APK")" || fail "could not inspect BDK3 test package"
BDK2_TARGET_PACKAGE="$(apk_target_package "$BDK2_TEST_APK")" || fail "could not inspect BDK2 target package"
BDK3_TARGET_PACKAGE="$(apk_target_package "$BDK3_TEST_APK")" || fail "could not inspect BDK3 target package"
[[ "$BDK2_PACKAGE" == "$TARGET_PACKAGE" ]] || fail "unexpected BDK2 package"
[[ "$BDK3_PACKAGE" == "$TARGET_PACKAGE" ]] || fail "unexpected BDK3 package"
[[ "$BDK2_TEST_PACKAGE" == "$TEST_PACKAGE" ]] || fail "unexpected BDK2 test package"
[[ "$BDK3_TEST_PACKAGE" == "$TEST_PACKAGE" ]] || fail "unexpected BDK3 test package"
[[ "$BDK2_TARGET_PACKAGE" == "$TARGET_PACKAGE" ]] || fail "BDK2 test APK targets the wrong app"
[[ "$BDK3_TARGET_PACKAGE" == "$TARGET_PACKAGE" ]] || fail "BDK3 test APK targets the wrong app"

SIGNER_DIGEST="$(certificate_digest "$BDK2_APK")" || fail "could not inspect BDK2 APK signer"
[[ "$SIGNER_DIGEST" =~ ^[0-9a-f]{64}$ ]] || fail "could not determine disposable signer digest"
readonly SIGNER_DIGEST
[[ "$SIGNER_DIGEST" == "$DISPOSABLE_CERT_DIGEST" ]] ||
  fail "debug APK was not signed by the freshly generated disposable certificate"
for apk in "$BDK2_TEST_APK" "$BDK3_APK" "$BDK3_TEST_APK"; do
  APK_CERTIFICATE_DIGEST="$(certificate_digest "$apk")" || fail "could not inspect APK signer: $apk"
  [[ "$APK_CERTIFICATE_DIGEST" == "$SIGNER_DIGEST" ]] ||
    fail "APK signer mismatch would invalidate install-in-place evidence"
done

adb -s "$ADB_SERIAL" wait-for-device
QEMU_PROPERTY="$(adb -s "$ADB_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" ||
  fail "could not determine whether ADB target is an emulator"
BOOT_COMPLETED="$(adb -s "$ADB_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" ||
  fail "could not read emulator boot state"
[[ "$QEMU_PROPERTY" == "1" ]] ||
  fail "refusing to clear or install on a non-emulator Android device"
[[ "$BOOT_COMPLETED" == "1" ]] ||
  fail "dedicated emulator has not completed boot"
for service_name in package settings activity; do
  service_status="$(adb -s "$ADB_SERIAL" shell service check "$service_name" 2>&1 | tr -d '\r')" ||
    fail "could not query emulator service: $service_name"
  [[ "$service_status" == "Service $service_name: found" ]] ||
    fail "required emulator service is unavailable: $service_name"
done
INSTALLED_PACKAGES="$(query_installed_packages)" ||
  fail "could not query installed package state"
readonly INSTALLED_PACKAGES
if package_is_installed "$INSTALLED_PACKAGES" "$TARGET_PACKAGE"; then
  fail "refusing to replace a pre-existing target package: $TARGET_PACKAGE"
fi
if package_is_installed "$INSTALLED_PACKAGES" "$TEST_PACKAGE"; then
  fail "refusing to replace a pre-existing instrumentation test package: $TEST_PACKAGE"
fi

run_instrumentation_test() {
  local class_name="$1"
  local label="$2"
  local log_file="$EVIDENCE_DIR/$label.instrumentation.txt"
  adb -s "$ADB_SERIAL" shell am instrument -w -r \
    -e class "$class_name" \
    "$TEST_PACKAGE/$TEST_RUNNER" >"$log_file"
  grep -Fq 'OK (1 test)' "$log_file" || fail "$label did not report one passing test"
  grep -Fq 'INSTRUMENTATION_CODE: -1' "$log_file" || fail "$label instrumentation did not complete successfully"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$log_file"; then
    fail "$label instrumentation reported a failure"
  fi
}

assert_target_file() {
  adb -s "$ADB_SERIAL" shell run-as "$TARGET_PACKAGE" ls "$1" >/dev/null 2>&1 ||
    fail "preserved target-app file missing: $1"
}

target_file_sha256() {
  local relative_path="$1"
  adb -s "$ADB_SERIAL" exec-out run-as "$TARGET_PACKAGE" cat "$relative_path" |
    sha256sum | awk '{print $1}'
}

sha256_file() {
  local path="$1"
  local output digest
  [[ -f "$path" ]] || return 1
  output="$(sha256sum -- "$path")" || return 1
  digest="${output%% *}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$digest"
}

# Preflight proved both package names absent on a dedicated emulator. Mark device cleanup active
# before install because adb can return a transport error after PackageManager has made changes.
DEVICE_TOUCHED=1
adb -s "$ADB_SERIAL" install -r "$BDK2_APK" >/dev/null
PACKAGE_CLEAR_RESULT="$(adb -s "$ADB_SERIAL" shell pm clear "$TARGET_PACKAGE" | tr -d '\r')" ||
  fail "could not reset dedicated debug package"
[[ "$PACKAGE_CLEAR_RESULT" == "Success" ]] ||
  fail "could not reset dedicated debug package"
adb -s "$ADB_SERIAL" install -r "$BDK2_TEST_APK" >/dev/null
run_instrumentation_test "$SEEDER_CLASS" "bdk2-seed"
assert_target_file "databases/$DATABASE_NAME"
assert_target_file "no_backup/$PUBLIC_EVIDENCE"

# Remove the only APK containing the public non-production mnemonic before installing BDK3.
adb -s "$ADB_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null
adb -s "$ADB_SERIAL" shell am force-stop "$TARGET_PACKAGE"
BDK2_DATABASE_SHA256_BEFORE_INSTALL="$(target_file_sha256 "databases/$DATABASE_NAME")" ||
  fail "could not read the BDK2 database before APK replacement"
[[ "$BDK2_DATABASE_SHA256_BEFORE_INSTALL" =~ ^[0-9a-f]{64}$ ]] ||
  fail "could not fingerprint the BDK2 database before APK replacement"
readonly BDK2_DATABASE_SHA256_BEFORE_INSTALL
adb -s "$ADB_SERIAL" install -r "$BDK3_APK" >/dev/null
assert_target_file "databases/$DATABASE_NAME"
assert_target_file "no_backup/$PUBLIC_EVIDENCE"
BDK2_DATABASE_SHA256_AFTER_INSTALL="$(target_file_sha256 "databases/$DATABASE_NAME")" ||
  fail "could not read the BDK2 database after APK replacement"
[[ "$BDK2_DATABASE_SHA256_AFTER_INSTALL" == "$BDK2_DATABASE_SHA256_BEFORE_INSTALL" ]] ||
  fail "adb install -r changed the persisted BDK2 database before BDK3 loaded it"
readonly BDK2_DATABASE_SHA256_AFTER_INSTALL
adb -s "$ADB_SERIAL" install -r "$BDK3_TEST_APK" >/dev/null

run_instrumentation_test "$PHASE_ONE_CLASS" "bdk3-phase-one"
adb -s "$ADB_SERIAL" shell am force-stop "$TEST_PACKAGE"
adb -s "$ADB_SERIAL" shell am force-stop "$TARGET_PACKAGE"
run_instrumentation_test "$PHASE_TWO_CLASS" "bdk3-phase-two"
assert_target_file "no_backup/$SAFE_RESULT"

adb -s "$ADB_SERIAL" exec-out run-as "$TARGET_PACKAGE" cat "no_backup/$SAFE_RESULT" \
  >"$EVIDENCE_DIR/$SAFE_RESULT"

python3 - "$EVIDENCE_DIR/$SAFE_RESULT" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line]

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)

require(all("=" in line for line in lines), "malformed upgrade evidence line")
evidence = dict(line.split("=", 1) for line in lines)
expected_keys = {
    "balance.confirmed_sat", "balance.total_sat", "balance.trusted_pending_sat",
    "balance.untrusted_pending_sat", "checkpoint.hash", "checkpoint.height",
    "consumer.bdk", "database.load_verified",
    "external.addresses.sha256", "external.descriptor.sha256", "external.last_index",
    "fixture.version", "history.last_seen", "history.position",
    "history.transaction_count", "history.txid", "in_place_upgrade_verified",
    "internal.addresses.sha256", "internal.descriptor.sha256", "internal.last_index",
    "network", "next_unused.external_address.sha256", "next_unused.external_index",
    "process_restart_verified", "producer.bdk", "production.load_verified", "result",
    "room.metadata_preserved", "unspent.count", "unspent.outpoint",
    "unspent.script_sha256", "unspent.value_sat",
}
require(set(evidence) == expected_keys, "upgrade evidence keys do not match the exact contract")
require(evidence["result"] == "PASS", "upgrade evidence did not report PASS")
require(evidence["fixture.version"] == "2", "unexpected upgrade fixture version")
require(evidence["producer.bdk"] == "2.3.1", "unexpected producer BDK version")
require(evidence["consumer.bdk"] == "3.0.0", "unexpected consumer BDK version")
require(evidence["network"] == "testnet", "unexpected upgrade network")
require(evidence["balance.confirmed_sat"] == "0", "fixture confirmed balance changed")
require(evidence["balance.trusted_pending_sat"] == "0", "fixture trusted-pending balance changed")
require(evidence["balance.untrusted_pending_sat"] == "50000", "fixture pending balance changed")
require(evidence["balance.total_sat"] == "50000", "fixture total balance changed")
require(evidence["history.transaction_count"] == "1", "fixture transaction graph is incomplete")
require(bool(re.fullmatch(r"[0-9a-f]{64}", evidence["history.txid"])), "invalid fixture txid")
require(evidence["history.position"] == "unconfirmed", "fixture confirmation state changed")
require(evidence["history.last_seen"] == "1700000326", "fixture last-seen time changed")
require(evidence["unspent.count"] == "1", "fixture UTXO set is incomplete")
require(evidence["unspent.outpoint"] == evidence["history.txid"] + ":0", "fixture outpoint mismatch")
require(evidence["unspent.value_sat"] == "50000", "fixture UTXO value changed")
require(evidence["checkpoint.height"] == "0", "fixture checkpoint height changed")
require(
    evidence["checkpoint.hash"] ==
    "000000000933ea01ad0ee984209779baae8c49c9c4766772abf10f7687df4f2",
    "fixture checkpoint hash changed",
)
require(evidence["external.last_index"] == "2", "unexpected external derivation index")
require(evidence["internal.last_index"] == "1", "unexpected internal derivation index")
for key in (
    "database.load_verified", "in_place_upgrade_verified", "process_restart_verified",
    "production.load_verified", "room.metadata_preserved",
):
    require(evidence[key] == "true", f"upgrade proof is false: {key}")
for key in (
    "external.addresses.sha256", "external.descriptor.sha256",
    "internal.addresses.sha256", "internal.descriptor.sha256",
    "next_unused.external_address.sha256", "unspent.script_sha256",
):
    require(bool(re.fullmatch(r"[0-9a-f]{64}", evidence[key])), f"invalid digest: {key}")
PY

BDK2_APK_SHA256="$(sha256_file "$BDK2_APK")" || fail "could not hash BDK2 APK evidence"
BDK3_APK_SHA256="$(sha256_file "$BDK3_APK")" || fail "could not hash BDK3 APK evidence"
BDK2_TEST_APK_SHA256="$(sha256_file "$BDK2_TEST_APK")" || fail "could not hash BDK2 test APK evidence"
BDK3_TEST_APK_SHA256="$(sha256_file "$BDK3_TEST_APK")" || fail "could not hash BDK3 test APK evidence"
UPGRADE_RESULT_SHA256="$(sha256_file "$EVIDENCE_DIR/$SAFE_RESULT")" || fail "could not hash upgrade result evidence"
BDK2_GRADLE_LOG_SHA256="$(sha256_file "$EVIDENCE_DIR/bdk2.gradle.txt")" || fail "could not hash BDK2 build log evidence"
BDK3_GRADLE_LOG_SHA256="$(sha256_file "$EVIDENCE_DIR/bdk3.gradle.txt")" || fail "could not hash BDK3 build log evidence"
BDK2_LOCKFILE_SHA256="$(sha256_file "$BDK2_TREE/app/gradle.lockfile")" || fail "could not hash BDK2 lockfile evidence"
BDK3_LOCKFILE_SHA256="$(sha256_file "$BDK3_TREE/app/gradle.lockfile")" || fail "could not hash BDK3 lockfile evidence"
BDK2_VERIFICATION_METADATA_SHA256="$(sha256_file "$BDK2_TREE/gradle/verification-metadata.xml")" ||
  fail "could not hash BDK2 verification metadata evidence"
BDK3_VERIFICATION_METADATA_SHA256="$(sha256_file "$BDK3_TREE/gradle/verification-metadata.xml")" ||
  fail "could not hash BDK3 verification metadata evidence"
EMULATOR_SDK="$(adb -s "$ADB_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" ||
  fail "could not read emulator SDK evidence"
EMULATOR_ABI="$(adb -s "$ADB_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')" ||
  fail "could not read emulator ABI evidence"
[[ "$EMULATOR_SDK" =~ ^[0-9]+$ ]] || fail "emulator SDK evidence is invalid"
[[ "$EMULATOR_ABI" =~ ^[A-Za-z0-9._-]+$ ]] || fail "emulator ABI evidence is invalid"
readonly BDK2_APK_SHA256 BDK3_APK_SHA256 BDK2_TEST_APK_SHA256 BDK3_TEST_APK_SHA256
readonly UPGRADE_RESULT_SHA256 BDK2_GRADLE_LOG_SHA256 BDK3_GRADLE_LOG_SHA256
readonly BDK2_LOCKFILE_SHA256 BDK3_LOCKFILE_SHA256
readonly BDK2_VERIFICATION_METADATA_SHA256 BDK3_VERIFICATION_METADATA_SHA256
readonly EMULATOR_SDK EMULATOR_ABI

{
  printf 'result=PASS\n'
  printf 'bdk2.commit=%s\n' "$BDK2_COMMIT"
  printf 'bdk3.commit=%s\n' "$BDK3_COMMIT"
  printf 'bdk2.apk.sha256=%s\n' "$BDK2_APK_SHA256"
  printf 'bdk3.apk.sha256=%s\n' "$BDK3_APK_SHA256"
  printf 'bdk2.test_apk.sha256=%s\n' "$BDK2_TEST_APK_SHA256"
  printf 'bdk3.test_apk.sha256=%s\n' "$BDK3_TEST_APK_SHA256"
  printf 'upgrade_result.sha256=%s\n' "$UPGRADE_RESULT_SHA256"
  printf 'bdk2.database_before_install.sha256=%s\n' "$BDK2_DATABASE_SHA256_BEFORE_INSTALL"
  printf 'bdk2.database_after_install.sha256=%s\n' "$BDK2_DATABASE_SHA256_AFTER_INSTALL"
  printf 'database.install_preserved=true\n'
  printf 'bdk2.gradle_log.sha256=%s\n' "$BDK2_GRADLE_LOG_SHA256"
  printf 'bdk3.gradle_log.sha256=%s\n' "$BDK3_GRADLE_LOG_SHA256"
  printf 'bdk2.lockfile.sha256=%s\n' "$BDK2_LOCKFILE_SHA256"
  printf 'bdk3.lockfile.sha256=%s\n' "$BDK3_LOCKFILE_SHA256"
  printf 'bdk2.verification_metadata.sha256=%s\n' "$BDK2_VERIFICATION_METADATA_SHA256"
  printf 'bdk3.verification_metadata.sha256=%s\n' "$BDK3_VERIFICATION_METADATA_SHA256"
  printf 'disposable_signer.sha256=%s\n' "$SIGNER_DIGEST"
  printf 'emulator.sdk=%s\n' "$EMULATOR_SDK"
  printf 'emulator.abi=%s\n' "$EMULATOR_ABI"
  printf 'target.package=%s\n' "$TARGET_PACKAGE"
  printf 'test.package=%s\n' "$TEST_PACKAGE"
  printf 'installation.mode=adb-install-r\n'
} >"$EVIDENCE_DIR/gate-manifest.properties"

printf 'BDK 2.3.1 -> 3.0.0 in-place persisted-wallet upgrade gate: PASS\n'
printf 'Evidence: %s\n' "$EVIDENCE_DIR"
