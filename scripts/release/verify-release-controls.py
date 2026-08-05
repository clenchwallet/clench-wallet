#!/usr/bin/env python3
"""Fail closed if release-key isolation or release workflow boundaries regress."""

from __future__ import annotations

import os
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


SENSITIVE_SUFFIXES = (".jks", ".keystore", ".p12", ".pfx")
RELEASE_SECRET_NAMES = (
    "RELEASE_KEYSTORE_BASE64",
    "RELEASE_KEYSTORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
FORBIDDEN_HARDWARE_PERMISSIONS = {
    "android.permission.BLUETOOTH",
    "android.permission.BLUETOOTH_ADMIN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
}
FORBIDDEN_HARDWARE_FEATURES = {
    "android.hardware.bluetooth",
    "android.hardware.bluetooth_le",
    "android.hardware.usb.accessory",
    "android.hardware.usb.host",
}
FORBIDDEN_SIGNER_TRANSPORT_PATTERNS = (
    re.compile(r"\bbluetooth\b", re.IGNORECASE),
    re.compile(r"\bble\b", re.IGNORECASE),
    re.compile(r"\busb\b", re.IGNORECASE),
    re.compile(r"virtual\s+disk", re.IGNORECASE),
)


def tracked_files() -> list[str]:
    output = subprocess.check_output(["git", "ls-files", "-z"])
    return [item.decode("utf-8") for item in output.split(b"\0") if item]


def job_blocks(workflow: str) -> dict[str, str]:
    jobs_offset = workflow.find("\njobs:\n")
    if jobs_offset < 0:
        raise SystemExit("Release workflow has no jobs block")
    jobs_text = workflow[jobs_offset + len("\njobs:\n") :]
    matches = list(re.finditer(r"(?m)^  ([A-Za-z0-9_-]+):\s*$", jobs_text))
    blocks: dict[str, str] = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(jobs_text)
        blocks[match.group(1)] = jobs_text[match.start() : end]
    return blocks


def verify_hardware_transport_policy() -> None:
    manifest_path = Path("app/src/main/AndroidManifest.xml")
    manifest = ET.parse(manifest_path).getroot()
    permissions = {
        item.attrib.get(ANDROID_NAME)
        for item in manifest.findall("uses-permission")
    }
    features = {
        item.attrib.get(ANDROID_NAME)
        for item in manifest.findall("uses-feature")
    }
    forbidden_permissions = sorted(
        permission
        for permission in permissions
        if permission in FORBIDDEN_HARDWARE_PERMISSIONS
    )
    forbidden_features = sorted(
        feature
        for feature in features
        if feature in FORBIDDEN_HARDWARE_FEATURES
    )
    if forbidden_permissions:
        raise SystemExit(
            "Manifest requests a forbidden direct signer transport permission: "
            + ", ".join(forbidden_permissions)
        )
    if forbidden_features:
        raise SystemExit(
            "Manifest advertises a forbidden direct signer transport feature: "
            + ", ".join(forbidden_features)
        )

    if "android.permission.HIDE_OVERLAY_WINDOWS" not in permissions:
        raise SystemExit("Manifest does not opt out of third-party overlay windows")
    main_activity = Path(
        "app/src/main/java/net/clench/wallet/ui/MainActivity.kt"
    ).read_text(encoding="utf-8")
    for required_overlay_control in (
        "filterTouchesWhenObscured = true",
        "window.setHideOverlayWindows(true)",
    ):
        if required_overlay_control not in main_activity:
            raise SystemExit(
                f"Main activity is missing overlay/tapjacking control: "
                f"{required_overlay_control}"
            )

    hardware_types = Path(
        "app/src/main/java/net/clench/wallet/domain/model/HardwareWalletType.kt"
    ).read_text(encoding="utf-8")
    labels = re.findall(
        r'(?m)^\s*[A-Z0-9_]+\("[^"]+",\s*"([^"]+)"\)',
        hardware_types,
    )
    if not labels:
        raise SystemExit("Could not parse hardware-wallet transport labels")
    for label in labels:
        for pattern in FORBIDDEN_SIGNER_TRANSPORT_PATTERNS:
            if pattern.search(label):
                raise SystemExit(
                    f"Hardware-wallet transport label violates the no-USB/Bluetooth "
                    f"policy: {label}"
                )

    required_copy = "Clench does not communicate with signing devices over USB or Bluetooth."
    for source_path in (
        Path(
            "app/src/main/java/net/clench/wallet/ui/components/"
            "HardwareWalletPickerSheet.kt"
        ),
        Path(
            "app/src/main/java/net/clench/wallet/ui/screens/PrivacyPolicyScreen.kt"
        ),
        Path(
            "app/src/main/java/net/clench/wallet/ui/screens/"
            "HardwareWalletSettingsScreen.kt"
        ),
    ):
        if required_copy not in source_path.read_text(encoding="utf-8"):
            raise SystemExit(
                f"Hardware-wallet transport policy copy is missing from {source_path}"
            )


def main() -> None:
    tracked = tracked_files()
    generated = [path for path in tracked if path == ".kotlin" or path.startswith(".kotlin/")]
    if generated:
        raise SystemExit(
            "Local Kotlin build artifacts are tracked by Git: " + ", ".join(generated)
        )

    local_identity_markers = (
        b"/home/" + b"clawd/",
        b".openclaw/" + b"workspace/",
        b"clawd" + b"@openclaw.ai",
    )
    leaked_local_identity: list[str] = []
    for path in tracked:
        source = Path(path)
        try:
            content = source.read_bytes()
        except (OSError, ValueError):
            continue
        if any(marker in content for marker in local_identity_markers):
            leaked_local_identity.append(path)
    if leaked_local_identity:
        raise SystemExit(
            "Tracked files expose a local build identity or workspace path: "
            + ", ".join(leaked_local_identity)
        )

    gradle_properties = Path("gradle.properties").read_text(encoding="utf-8")
    if re.search(r"(?m)^org\.gradle\.buildCacheDir\s*=\s*/", gradle_properties):
        raise SystemExit("Gradle build cache must not use a host-specific absolute path")

    sensitive = [
        path
        for path in tracked
        if path == "keystore.properties" or path.lower().endswith(SENSITIVE_SUFFIXES)
    ]
    if sensitive:
        raise SystemExit("Signing material is tracked by Git: " + ", ".join(sensitive))

    ignore = Path(".gitignore").read_text(encoding="utf-8")
    for required in (
        "keystore.properties",
        "**/*.jks",
        "**/*.keystore",
        "**/*.p12",
        "**/*.pfx",
    ):
        if required not in ignore:
            raise SystemExit(f".gitignore does not protect {required}")

    if os.environ.get("CLENCH_REQUIRE_NO_LOCAL_SIGNING_MATERIAL") == "1":
        local = [
            path
            for path in Path(".").rglob("*")
            if path.is_file()
            and (
                path.name == "keystore.properties"
                or path.name.lower().endswith(SENSITIVE_SUFFIXES)
            )
            and ".git" not in path.parts
        ]
        if local:
            raise SystemExit(
                "Independent verification checkout contains local signing material: "
                + ", ".join(str(path) for path in local)
            )

    wrapper = Path("gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    if not re.search(r"(?m)^distributionSha256Sum=[0-9a-f]{64}$", wrapper):
        raise SystemExit("Gradle wrapper distribution is not pinned by SHA-256")

    build_script = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    if re.search(
        r"jniLibs\s*\.\s*pickFirsts[^\n]*(?:\*\*|\*\.so)",
        build_script,
    ):
        raise SystemExit(
            "Release packaging must not silently pick the first copy of arbitrary "
            "native libraries; resolve or allowlist each exact collision"
        )

    biometric_helper = Path(
        "app/src/main/java/net/clench/wallet/ui/util/BiometricHelper.kt"
    ).read_text(encoding="utf-8")
    if "allowUiOnlyFallback: Boolean = true" in biometric_helper:
        raise SystemExit("Cryptographic authentication must fail closed by default")
    for kotlin_path in Path("app/src/main/java").rglob("*.kt"):
        if "allowUiOnlyFallback = true" in kotlin_path.read_text(encoding="utf-8"):
            raise SystemExit(
                f"Security-sensitive authentication enables UI-only fallback: {kotlin_path}"
            )

    verify_hardware_transport_policy()

    workflow_files = sorted(Path(".github/workflows").glob("*.yml"))
    for workflow_file in workflow_files:
        source = workflow_file.read_text(encoding="utf-8")
        actions = re.findall(r"(?m)^\s*(?:-\s*)?uses:\s*([^\s#]+)", source)
        for action in actions:
            if not re.fullmatch(r"[^@]+@[0-9a-f]{40}", action):
                raise SystemExit(
                    f"Workflow action is not pinned by full commit SHA: "
                    f"{workflow_file}: {action}"
                )
    for required_wrapper_lane in ("android.yml", "codeql.yml", "fuzz.yml", "release.yml"):
        source = Path(".github/workflows", required_wrapper_lane).read_text(
            encoding="utf-8"
        )
        if "gradle/actions/wrapper-validation@" not in source:
            raise SystemExit(
                f"Hosted build lane does not validate the wrapper JAR: "
                f"{required_wrapper_lane}"
            )

    workflow = Path(".github/workflows/release.yml").read_text(encoding="utf-8")
    blocks = job_blocks(workflow)
    required_jobs = {
        "validate_source",
        "build_unsigned",
        "sign_release",
        "verify_release",
        "publish",
    }
    if not required_jobs.issubset(blocks):
        raise SystemExit("Release workflow is missing required isolated jobs")
    if "workflow_dispatch:" not in workflow:
        raise SystemExit("Release workflow must be dispatched from protected master")
    if re.search(r"(?m)^\s*push:\s*$", workflow):
        raise SystemExit("Release workflow must not execute tag-controlled workflow code")
    if workflow.count("scripts/release/check-osv.py") < 2:
        raise SystemExit("Release build and independent verifier do not both run OSV")
    if "scripts/verification/test-osv-check.py" not in blocks["build_unsigned"]:
        raise SystemExit("Release build does not exercise hostile OSV-gate tests")
    if "environment: release-signing" not in blocks["sign_release"]:
        raise SystemExit("Signing job is not bound to the protected release environment")
    if workflow.count("gradle/actions/wrapper-validation@") < 2:
        raise SystemExit("Release build and independent verifier do not validate the wrapper JAR")
    for job in ("validate_source", "build_unsigned", "verify_release", "publish"):
        if "environment: release-signing" in blocks[job]:
            raise SystemExit(f"{job} must not inherit the signing environment")

    source_gate = blocks["validate_source"]
    for required_gate in (
        ".github/release-signers.allowed",
        "git verify-tag --raw",
        "EXPECTED_SOURCE_TAG_SIGNER_SHA256",
        'test "$WORKFLOW_REF" = refs/heads/master',
        'test "$TAG_COMMIT" = "$WORKFLOW_COMMIT"',
    ):
        if required_gate not in source_gate and required_gate not in workflow:
            raise SystemExit(f"Release source gate is missing: {required_gate}")

    signer = blocks["sign_release"]
    for forbidden in ("actions/checkout@", "./gradlew", "keystore.properties"):
        if forbidden in signer:
            raise SystemExit(f"Isolated signer must not receive source/build input: {forbidden}")
    if re.search(r"(?m)^\s+scripts/", signer):
        raise SystemExit("Isolated signer must not execute tag-controlled repository scripts")
    for required_global_control in (
        "EXPECTED_APKSIGNER_SHA256",
        "EXPECTED_APKSIGNER_JAR_SHA256",
    ):
        if required_global_control not in workflow:
            raise SystemExit(
                f"Release workflow is missing pinned signer control: "
                f"{required_global_control}"
            )
    for required_signer_control in (
        "sha256sum --strict -c BUILD-SHA256SUMS",
        "Sign the prebuilt digest with no source checkout",
        "--v4-signing-enabled false",
        'test ! -e "$RUNNER_TEMP/signed-release/clench-$VERSION-release.apk.idsig"',
    ):
        if required_signer_control not in signer:
            raise SystemExit(
                f"Isolated signer is missing pinned-input control: {required_signer_control}"
            )

    for secret in RELEASE_SECRET_NAMES:
        reference = "${{ secrets." + secret + " }}"
        if reference not in signer:
            raise SystemExit(f"Signing job does not require {secret}")
        for job, block in blocks.items():
            if job != "sign_release" and reference in block:
                raise SystemExit(f"{secret} leaked into non-signing job {job}")

    destroy_offset = signer.find("name: Destroy signing material")
    sign_offset = signer.find("name: Sign the prebuilt digest with no source checkout")
    verify_offset = signer.find("name: Verify signer continuity after key destruction")
    attest_offset = signer.find("name: Attest signed APK provenance")
    upload_offset = signer.find("name: Upload the minimally signed release inputs")
    if not (0 <= sign_offset < destroy_offset < verify_offset < attest_offset < upload_offset):
        raise SystemExit(
            "Signing material is not destroyed immediately after signing and before "
            "verification, attestation, or artifact upload"
        )
    if "needs: [validate_source, verify_release]" not in blocks["publish"]:
        raise SystemExit("Publication is not gated on the no-secrets verification job")
    if blocks["publish"].count("git verify-tag --raw") != 1:
        raise SystemExit("Publication does not reverify the pinned source tag")
    if (
        'test "$(git rev-parse "refs/tags/$RELEASE_TAG^{commit}")" ='
        not in blocks["publish"]
    ):
        raise SystemExit("Publication does not rebind the tag to the validated commit")
    if "--deny-self-hosted-runners" not in blocks["verify_release"]:
        raise SystemExit("Attestation verification does not reject self-hosted builders")
    if "--predicate-type https://cyclonedx.org/bom" not in blocks["verify_release"]:
        raise SystemExit("CycloneDX SBOM attestation is not independently verified")
    if "verify-sbom-attestation.py" not in blocks["verify_release"]:
        raise SystemExit("Published SBOM is not compared with the verified attestation")
    if "name: Finalize and reverify the public evidence bundle" not in blocks["verify_release"]:
        raise SystemExit("Independent verification evidence is not checksummed and reverified")
    for job, block in blocks.items():
        if "runs-on: ubuntu-24.04" not in block:
            raise SystemExit(f"{job} is not pinned to the release runner image")
        if re.search(r"(?m)^\s*runs-on:\s*(?:self-hosted|\[.*self-hosted)", block):
            raise SystemExit(f"{job} permits a self-hosted release runner")

    print(
        "Release-key isolation, no-secrets publication, and hardware-transport "
        "controls passed."
    )


if __name__ == "__main__":
    main()
