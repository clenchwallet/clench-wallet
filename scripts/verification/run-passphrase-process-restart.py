#!/usr/bin/env python3
"""Exact-source, isolated public-fixture BIP39 persistence across Android process death."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid

APP = "net.clench.wallet.debug"
TEST_APP = APP + ".test"
RUNNER = TEST_APP + "/androidx.test.runner.AndroidJUnitRunner"
CLASS = "net.clench.wallet.verification.passphraserestart.PassphraseProcessRestartFixture"
RECEIPT = "files/clench-passphrase-process-fixture-v1/public-receipt.json"


def require_phase_pass(result, phase):
    lines = result.replace("\r", "").splitlines()
    statuses = [line for line in lines if line.startswith("INSTRUMENTATION_STATUS_CODE:")]
    marker = "CLENCH_PASSPHRASE_RESTART_" + phase.upper() + "_PASS"
    if statuses != ["INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: 2", "INSTRUMENTATION_STATUS_CODE: 0"] or \
            lines.count("OK (1 test)") != 1 or lines.count("INSTRUMENTATION_CODE: -1") != 1 or \
            f"INSTRUMENTATION_STATUS: class={CLASS}" not in lines or \
            re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", result):
        raise RuntimeError("Instrumentation did not execute exactly one passing phase")
    def field(name):
        prefix = "INSTRUMENTATION_STATUS: " + name + "="
        values = [line[len(prefix):] for line in lines if line.startswith(prefix)]
        if len(values) != 1:
            raise RuntimeError("Missing or repeated phase evidence: " + name)
        return values[0]
    if field("clenchPassphraseRestartMarker") != marker:
        raise RuntimeError("Wrong phase marker")
    process = field("clenchPassphraseProcess")
    if str(uuid.UUID(process)) != process:
        raise RuntimeError("Invalid process instance")
    pid = field("clenchPassphrasePid")
    if not re.fullmatch(r"[1-9][0-9]*", pid):
        raise RuntimeError("Invalid process PID")
    return {"marker": marker, "process": process, "pid": int(pid)}


def run(args, *, cwd=None, env=None, output=None, timeout=90, codes=(0,)):
    try:
        result = subprocess.run(args, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, check=False, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        if output:
            Path(output).write_bytes(error.stdout or b"")
        raise RuntimeError(f"Command timed out: {args[0]}; evidence: {output or 'not saved'}") from error
    if output:
        Path(output).write_bytes(result.stdout)
    if result.returncode not in codes:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]}; evidence: {output or 'not saved'}")
    return result.stdout.decode("utf-8", errors="strict").strip()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if os.environ.get("CLENCH_PASSPHRASE_RESTART_DISPOSABLE") != "YES":
        raise RuntimeError("Explicit disposable-emulator authorization required")
    serial = os.environ.get("ADB_SERIAL", "")
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise RuntimeError("Explicit emulator serial required")
    os.environ["GIT_NO_REPLACE_OBJECTS"] = "1"
    root = Path(run(["git", "rev-parse", "--show-toplevel"]))
    if run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=root):
        raise RuntimeError("Require a clean source checkout")
    commit = run(["git", "rev-parse", "HEAD^{commit}"], cwd=root)
    common = Path(run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=root))
    if run(["git", "for-each-ref", "--format=%(refname)", "refs/replace/"], cwd=root) or \
            ((common / "info/grafts").exists() and (common / "info/grafts").stat().st_size):
        raise RuntimeError("Source substitution metadata is not allowed")
    adb = ["adb", "-s", serial]
    if run(adb + ["shell", "getprop", "ro.kernel.qemu"]) != "1" or \
            run(adb + ["shell", "getprop", "sys.boot_completed"]) != "1":
        raise RuntimeError("Target must be a booted emulator")
    packages = set(run(adb + ["shell", "cmd", "package", "list", "packages"]).splitlines())
    if not packages or any("package:" + p in packages for p in (APP, TEST_APP)):
        raise RuntimeError("Refusing to replace pre-existing app/test packages; run before other installation stages")
    evidence = root / "build/reports/passphrase-process-restart"
    if evidence.exists() and any(evidence.iterdir()):
        raise RuntimeError("Evidence directory must be new or empty")
    evidence.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    aapt = sdk / "build-tools/35.0.0/aapt"
    signer = sdk / "build-tools/35.0.0/apksigner"
    fixture = root / "scripts/verification/passphrase-process-restart/PassphraseProcessRestartFixture.kt"
    work = Path(tempfile.mkdtemp(prefix="clench-passphrase-process-"))
    (evidence / "work-directory.txt").write_text(str(work) + "\n")
    env = os.environ.copy()
    android = work / "android-user-home"
    android.mkdir()
    env["ANDROID_USER_HOME"] = str(android)
    env["CLENCH_REQUIRE_NO_LOCAL_SIGNING_MATERIAL"] = "1"
    tree = work / "source"
    run(["git", "worktree", "add", "--detach", str(tree), commit], cwd=root)
    files = run(["git", "ls-files"], cwd=tree).splitlines()
    if any(Path(p).name == "keystore.properties" or Path(p).suffix.lower() in
           (".jks", ".keystore", ".p12", ".pfx") for p in files):
        raise RuntimeError("Source unexpectedly contains signing material")
    overlay = tree / "app/src/androidTest/java/net/clench/wallet/verification/passphraserestart/PassphraseProcessRestartFixture.kt"
    overlay.parent.mkdir(parents=True)
    overlay.write_bytes(fixture.read_bytes())
    if run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=tree) != "?? " + str(overlay.relative_to(tree)):
        raise RuntimeError("Unexpected source overlay change")
    prepared = root / "build/native-bdk/maven"
    if prepared.is_dir():
        shutil.copytree(prepared, tree / "build/native-bdk/maven")
    else:
        run(["python3", "-B", "scripts/native/prepare-bdk.py"], cwd=tree, env=env,
            output=evidence / "native-build.log", timeout=3600)
    run([str(tree / "gradlew"), "--no-daemon", "--no-build-cache",
         "--dependency-verification=strict", "--max-workers=2",
         ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
        cwd=tree, env=env, output=evidence / "build.log", timeout=1800)
    apks = (tree / "app/build/outputs/apk/debug/app-debug.apk",
            tree / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    certs = []
    for index, apk in enumerate(apks):
        badging = run([str(aapt), "dump", "badging", str(apk)])
        package = APP if index == 0 else TEST_APP
        if not re.search(r"(?m)^package: name='" + re.escape(package) + "'", badging):
            raise RuntimeError("Unexpected APK package")
        if index == 0 and "application-debuggable" not in badging.splitlines():
            raise RuntimeError("Application must be debuggable")
        if index == 1:
            manifest = run([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"])
            if not re.search(r'android:targetPackage[^=]*="' + re.escape(APP) + '"', manifest):
                raise RuntimeError("Unexpected instrumentation target")
        certificate = run([str(signer), "verify", "--print-certs", str(apk)], output=evidence / f"apk-{index}-signature.txt")
        match = re.findall(r"(?m)^Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})$", certificate)
        if len(match) != 1:
            raise RuntimeError("Unverified debug certificate")
        certs.append(match[0])
    if certs[0] != certs[1]:
        raise RuntimeError("Debug APKs must share a signer")
    installed = []
    try:
        # No -r: even a package installed concurrently is not silently overwritten.
        for apk in apks:
            run(adb + ["install", str(apk)])
            installed.append(APP if len(installed) == 0 else TEST_APP)
        phases = {}
        for phase in ("write", "verify"):
            result = run(adb + ["shell", "am", "instrument", "-w", "-r",
                         "-e", "class", CLASS, "-e", "clenchDisposableEmulator", "YES",
                         "-e", "clenchPassphraseRestartPhase", phase, RUNNER],
                         output=evidence / f"{phase}.txt", timeout=240)
            phases[phase] = require_phase_pass(result, phase)
            if phase == "write":
                receipt = json.loads(run(adb + ["exec-out", "run-as", APP, "cat", RECEIPT],
                                         output=evidence / "public-receipt.json"))
                if receipt["writerProcess"] != phases[phase]["process"] or receipt["writerPid"] != phases[phase]["pid"]:
                    raise RuntimeError("Writer receipt does not match instrumentation process")
                run(adb + ["shell", "am", "force-stop", APP], output=evidence / "force-stop-app.txt")
                run(adb + ["shell", "am", "force-stop", TEST_APP], output=evidence / "force-stop-test.txt")
                remaining = run(adb + ["shell", "pidof", APP], codes=(0, 1), output=evidence / "after-force-stop-pidof.txt")
                if remaining:
                    raise RuntimeError("Application process survived force-stop")
                (evidence / "force-stop.json").write_text(json.dumps({"app": APP, "no_remaining_process": True}) + "\n")
        if phases["write"]["process"] == phases["verify"]["process"]:
            raise RuntimeError("Verifier reused the writer process")
        (evidence / "result.json").write_text(json.dumps({
            "source_commit": commit, "fixture_sha256": sha256(fixture),
            "apk_sha256": [sha256(apk) for apk in apks], "debug_signer_sha256": certs[0],
            "tests_passed": 2, "phases": phases, "host_force_stop_confirmed": True,
            "scope": "Public synthetic wallet persisted across force-stopped debug Android processes on a disposable emulator; not production-signed, physical-device, or OEM-wide acceptance."
        }, indent=2) + "\n")
    finally:
        if installed:
            for package in reversed(installed):
                current = set(run(adb + ["shell", "cmd", "package", "list", "packages"]).splitlines())
                if "package:" + package in current:
                    run(adb + ["uninstall", package])


if __name__ == "__main__":
    main()
