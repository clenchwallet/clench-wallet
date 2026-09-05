#!/usr/bin/env python3
"""Exact-source 4.15 -> 4.17 encrypted Room/WAL regression on a disposable emulator."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

PRODUCER = "3a96b6da8bcbd33d1ecc56cf9d49e1d66cd98609"
APP = "net.clench.wallet.debug"
TEST_APP = APP + ".test"
RUNNER = TEST_APP + "/androidx.test.runner.AndroidJUnitRunner"
FIXTURE_PACKAGE = "net.clench.wallet.verification.sqlcipherupgrade."


def require_one_passing_case(result, class_name):
    lines = result.replace("\r", "").splitlines()
    statuses = [line for line in lines if line.startswith("INSTRUMENTATION_STATUS_CODE:")]
    if statuses != ["INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: 0"] or \
            "OK (1 test)" not in lines or lines.count("INSTRUMENTATION_CODE: -1") != 1 or \
            f"INSTRUMENTATION_STATUS: class={class_name}" not in lines or \
            re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", result):
        raise RuntimeError("Instrumentation did not execute exactly one passing case: " + class_name)


def run(args, *, cwd=None, env=None, output=None, timeout=90):
    try:
        result = subprocess.run(args, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, check=False, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        if output:
            Path(output).write_bytes(error.stdout or b"")
        raise RuntimeError(f"Command timed out: {args[0]}; evidence: {output or 'not saved'}") from error
    if output:
        Path(output).write_bytes(result.stdout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]}; evidence: {output or 'not saved'}")
    return result.stdout.decode("utf-8", errors="strict").strip()


def main():
    if os.environ.get("CLENCH_SQLCIPHER_UPGRADE_DISPOSABLE") != "YES":
        raise RuntimeError("Explicit disposable-emulator authorization required")
    serial = os.environ.get("ADB_SERIAL", "")
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise RuntimeError("Explicit emulator serial required")
    os.environ["GIT_NO_REPLACE_OBJECTS"] = "1"
    root = Path(run(["git", "rev-parse", "--show-toplevel"]))
    if run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=root):
        raise RuntimeError("Require a clean source checkout")
    consumer = run(["git", "rev-parse", "HEAD^{commit}"], cwd=root)
    common = Path(run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=root))
    if run(["git", "for-each-ref", "--format=%(refname)", "refs/replace/"], cwd=root) or \
            ((common / "info/grafts").exists() and (common / "info/grafts").stat().st_size):
        raise RuntimeError("Source substitution metadata is not allowed")
    run(["git", "merge-base", "--is-ancestor", PRODUCER, consumer], cwd=root)
    for commit, version in ((PRODUCER, "4.15.0"), (consumer, "4.17.0")):
        build = run(["git", "show", commit + ":app/build.gradle.kts"], cwd=root)
        lock = run(["git", "show", commit + ":app/gradle.lockfile"], cwd=root)
        if f'implementation("net.zetetic:sqlcipher-android:{version}")' not in build or \
                not re.search(r"(?m)^net\.zetetic:sqlcipher-android:" + re.escape(version) + "=", lock):
            raise RuntimeError("Exact source does not select the required SQLCipher version")
    adb = ["adb", "-s", serial]
    if run(adb + ["shell", "getprop", "ro.kernel.qemu"]) != "1" or \
            run(adb + ["shell", "getprop", "sys.boot_completed"]) != "1":
        raise RuntimeError("Target must be a booted emulator")
    packages = set(run(adb + ["shell", "cmd", "package", "list", "packages"]).splitlines())
    if not packages or any("package:" + p in packages for p in (APP, TEST_APP)):
        raise RuntimeError("Refusing to replace pre-existing fixture/app packages")
    evidence = root / "build/reports/sqlcipher-inplace-upgrade"
    if evidence.exists() and any(evidence.iterdir()):
        raise RuntimeError("Evidence directory must be new or empty")
    evidence.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    signer = sdk / "build-tools/35.0.0/apksigner"
    aapt = sdk / "build-tools/35.0.0/aapt"
    fixture = root / "scripts/verification/sqlcipher-upgrade/SqlCipherUpgradeFixture.kt"
    env = os.environ.copy()
    work = Path(tempfile.mkdtemp(prefix="clench-sqlcipher-upgrade-"))
    # Retain isolated worktrees on failure for diagnosis, never overwrite source.
    (evidence / "work-directory.txt").write_text(str(work) + "\n")
    android = work / "android-user-home"
    android.mkdir()
    env["ANDROID_USER_HOME"] = str(android)
    env["CLENCH_REQUIRE_NO_LOCAL_SIGNING_MATERIAL"] = "1"
    key = android / "debug.keystore"
    run(["keytool", "-genkeypair", "-noprompt", "-keystore", str(key),
         "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android",
         "-dname", "CN=Disposable SQLCipher Upgrade Fixture", "-keyalg", "RSA",
         "-keysize", "3072", "-validity", "30"], output=evidence / "debug-signer-creation.txt")
    cert = subprocess.run(["keytool", "-exportcert", "-keystore", str(key),
                           "-storepass", "android", "-alias", "androiddebugkey"],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=90).stdout
    cert_hash = hashlib.sha256(cert).hexdigest()
    apks = {}
    for label, commit in (("producer", PRODUCER), ("consumer", consumer)):
        tree = work / label
        run(["git", "worktree", "add", "--detach", str(tree), commit], cwd=root)
        files = run(["git", "ls-files"], cwd=tree).splitlines()
        if any(Path(p).name == "keystore.properties" or Path(p).suffix.lower() in
               (".jks", ".keystore", ".p12", ".pfx") for p in files):
            raise RuntimeError("Source unexpectedly contains signing material")
        overlay = tree / "app/src/androidTest/java/net/clench/wallet/verification/sqlcipherupgrade/SqlCipherUpgradeFixture.kt"
        overlay.parent.mkdir(parents=True)
        overlay.write_bytes(fixture.read_bytes())
        status = run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=tree)
        if status != "?? " + str(overlay.relative_to(tree)):
            raise RuntimeError("Unexpected test overlay change")
        run([str(tree / "gradlew"), "--no-daemon", "--no-build-cache",
             "--dependency-verification=strict", "--max-workers=2",
             ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
            cwd=tree, env=env, output=evidence / f"{label}-build.log", timeout=1800)
        apks[label] = (tree / "app/build/outputs/apk/debug/app-debug.apk",
                       tree / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        for index, apk in enumerate(apks[label]):
            badging = run([str(aapt), "dump", "badging", str(apk)])
            expected_package = APP if index == 0 else TEST_APP
            if not re.search(r"(?m)^package: name='" + re.escape(expected_package) + "'", badging):
                raise RuntimeError("APK targets an unexpected package")
            if index == 1:
                manifest = run([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"])
                if not re.search(r'android:targetPackage[^=]*="' + re.escape(APP) + '"', manifest):
                    raise RuntimeError("Instrumentation targets an unexpected app")
            certificate = run([str(signer), "verify", "--print-certs", str(apk)])
            if f"Signer #1 certificate SHA-256 digest: {cert_hash}" not in certificate:
                raise RuntimeError("APK does not use the shared disposable signer")
            (evidence / f"{label}-{index}-apk.sha256").write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + "\n")
    touched = False
    try:
        for label in ("producer", "consumer"):
            touched = True
            for apk in apks[label]:
                run(adb + ["install", "-r", str(apk)])
            classes = ["SqlCipher415WriterTest"] if label == "producer" else ["SqlCipher417ReaderTest", "SqlCipher417ReopenTest"]
            for test in classes:
                run(adb + ["shell", "am", "force-stop", APP])
                result = run(adb + ["shell", "am", "instrument", "-w", "-r",
                             "-e", "class", FIXTURE_PACKAGE + test,
                             "-e", "clenchDisposableEmulator", "YES", RUNNER],
                             output=evidence / f"{test}.txt", timeout=180)
                require_one_passing_case(result, FIXTURE_PACKAGE + test)
        (evidence / "result.json").write_text(json.dumps({
            "producer_commit": PRODUCER, "consumer_commit": consumer,
            "producer_version": "4.15.0", "consumer_version": "4.17.0",
            "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
            "disposable_signer_sha256": cert_hash, "tests_passed": 3,
            "scope": "Encrypted Room/WAL snapshot recovery and in-place debug APK upgrade on disposable emulator, not real-wallet or OEM-wide evidence."
        }, indent=2) + "\n")
    finally:
        if touched:
            for package in (TEST_APP, APP):
                current = set(run(adb + ["shell", "cmd", "package", "list", "packages"]).splitlines())
                if "package:" + package in current:
                    run(adb + ["uninstall", package])


if __name__ == "__main__":
    main()
