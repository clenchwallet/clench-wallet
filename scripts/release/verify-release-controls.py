#!/usr/bin/env python3
"""Fail closed if release-key isolation or release workflow boundaries regress."""

from __future__ import annotations

import ast
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
TOOLCHAIN_ENV_TO_PYTHON = {
    "APKSIGNER_BUILD_TOOLS_VERSION": "APKSIGNER_BUILD_TOOLS_VERSION",
    "EXPECTED_APKSIGNER_SHA256": "APKSIGNER_EXECUTABLE_SHA256",
    "EXPECTED_APKSIGNER_JAR_SHA256": "APKSIGNER_JAR_SHA256",
    "EXPECTED_AAPT_SHA256": "AAPT_SHA256",
}


def literal_assignments(path: Path) -> dict[str, object]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    assignments: dict[str, object] = {}
    for node in tree.body:
        if (
            isinstance(node, ast.Assign)
            and len(node.targets) == 1
            and isinstance(node.targets[0], ast.Name)
        ):
            try:
                assignments[node.targets[0].id] = ast.literal_eval(node.value)
            except (ValueError, TypeError):
                continue
    return assignments


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


def named_step_blocks(job: str) -> dict[str, str]:
    matches = list(re.finditer(r"(?m)^      - name: (.+?)\s*$", job))
    blocks: dict[str, str] = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(job)
        name = match.group(1)
        if name in blocks:
            raise SystemExit(f"Release workflow repeats a named step: {name}")
        blocks[name] = job[match.start() : end]
    return blocks


def named_step_names(job: str) -> list[str]:
    """Return named steps in execution order."""

    return re.findall(r"(?m)^      - name: (.+?)\s*$", job)


def job_needs(job: str) -> str | None:
    match = re.search(r"(?m)^    needs:\s*(.+?)\s*$", job)
    return match.group(1) if match else None


def job_permissions(job: str) -> dict[str, str]:
    """Parse the job-level permissions mapping, excluding step-level text."""

    match = re.search(
        r"(?m)^    permissions:\s*$\n"
        r"((?:^      [A-Za-z0-9_-]+:\s*[^\n]+\n)+)",
        job,
    )
    if not match:
        return {}
    permissions: dict[str, str] = {}
    for key, value in re.findall(
        r"(?m)^      ([A-Za-z0-9_-]+):\s*([^\s#]+)", match.group(1)
    ):
        permissions[key] = value
    return permissions


def require_text(source: str, required: str, message: str) -> None:
    if required not in source:
        raise SystemExit(message)


def verify_release_workflow(workflow: str) -> None:
    """Validate release.yml security boundaries without reading or mutating files.

    Keeping this check pure lets the hostile self-test exercise workflow mutations
    in memory, so a negative test can never transiently rewrite the tracked release
    workflow.
    """

    blocks = job_blocks(workflow)
    required_jobs = {
        "validate_source",
        "build_unsigned",
        "build_independent_unsigned",
        "attest_independent_unsigned",
        "verify_unsigned",
        "build_post_sign_unsigned",
        "attest_post_sign_unsigned",
        "sign_release",
        "verify_release",
        "publish",
    }
    if set(blocks) != required_jobs:
        raise SystemExit(
            "Release workflow must contain exactly the required isolated jobs"
        )

    for job_name, block in blocks.items():
        steps_offset = block.find("\n    steps:\n")
        if steps_offset < 0:
            raise SystemExit(f"Release job {job_name} has no steps block")
        job_header = block[:steps_offset]
        if "${{ runner." in job_header:
            raise SystemExit(
                f"Release job {job_name} uses runner context before runner assignment"
            )
        if re.search(r"(?m)^      - (?!name:\s)", block):
            raise SystemExit(
                f"Release job {job_name} contains an unnamed top-level step"
            )

    expected_permissions = {
        "validate_source": {"contents": "read"},
        "build_unsigned": {"contents": "read"},
        "build_independent_unsigned": {"contents": "read"},
        "attest_independent_unsigned": {
            "contents": "read",
            "id-token": "write",
            "attestations": "write",
        },
        "verify_unsigned": {"contents": "read", "attestations": "read"},
        "build_post_sign_unsigned": {"contents": "read"},
        "attest_post_sign_unsigned": {
            "contents": "read",
            "id-token": "write",
            "attestations": "write",
        },
        "sign_release": {
            "contents": "read",
            "id-token": "write",
            "attestations": "write",
        },
        "verify_release": {
            "contents": "read",
            "id-token": "write",
            "attestations": "write",
        },
        "publish": {"contents": "write", "attestations": "read"},
    }
    for job_name, expected in expected_permissions.items():
        if job_permissions(blocks[job_name]) != expected:
            raise SystemExit(f"Release job {job_name} has unsafe permissions")

    strict_semver = (
        '[[ ! "$RELEASE_TAG" =~ '
        '^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.'
        '(0|[1-9][0-9]*)$ ]]'
    )
    require_text(
        blocks["validate_source"],
        strict_semver,
        "Release source gate must reject semantic-version leading zeros",
    )

    expected_needs = {
        "build_unsigned": "validate_source",
        "build_independent_unsigned": "validate_source",
        "attest_independent_unsigned": (
            "[validate_source, build_independent_unsigned]"
        ),
        "verify_unsigned": (
            "[validate_source, build_unsigned, build_independent_unsigned, "
            "attest_independent_unsigned]"
        ),
        "build_post_sign_unsigned": "validate_source",
        "attest_post_sign_unsigned": (
            "[validate_source, build_post_sign_unsigned]"
        ),
        "sign_release": "[validate_source, verify_unsigned]",
        "verify_release": (
            "[validate_source, verify_unsigned, build_post_sign_unsigned, "
            "attest_post_sign_unsigned, sign_release]"
        ),
        "publish": "[validate_source, verify_release]",
    }
    for job_name, expected in expected_needs.items():
        if job_needs(blocks[job_name]) != expected:
            raise SystemExit(
                f"Release job {job_name} has an unsafe dependency edge"
            )

    for job_name in ("build_independent_unsigned", "build_post_sign_unsigned"):
        block = blocks[job_name]
        if job_permissions(block) != {"contents": "read"}:
            raise SystemExit(
                f"{job_name} must have only contents: read permission"
            )
        require_text(
            block,
            "persist-credentials: false",
            f"{job_name} must not persist checkout credentials",
        )
        if "actions/download-artifact@" in block:
            raise SystemExit(
                f"{job_name} must not download expected release artifacts"
            )
        for cleared in (
            "ACTIONS_RUNTIME_TOKEN: ''",
            "ACTIONS_RESULTS_URL: ''",
            "ACTIONS_ID_TOKEN_REQUEST_TOKEN: ''",
            "ACTIONS_ID_TOKEN_REQUEST_URL: ''",
            "GH_TOKEN: ''",
            "GITHUB_TOKEN: ''",
        ):
            require_text(
                block,
                cleared,
                f"{job_name} does not clear blind-build credential: {cleared}",
            )

    attestors = {
        "attest_independent_unsigned": (
            "${{ runner.temp }}/independent-unsigned-build/"
            "clench-${{ needs.validate_source.outputs.version }}-"
            "independent-unsigned.apk"
        ),
        "attest_post_sign_unsigned": (
            "${{ runner.temp }}/post-sign-unsigned-build/"
            "clench-${{ needs.validate_source.outputs.version }}-"
            "independent-unsigned.apk"
        ),
    }
    for job_name, subject in attestors.items():
        block = blocks[job_name]
        if job_permissions(block) != {
            "contents": "read",
            "id-token": "write",
            "attestations": "write",
        }:
            raise SystemExit(f"{job_name} has unsafe attestor permissions")
        for forbidden in (
            "actions/checkout@",
            "gradle/actions/",
            "./gradlew",
            "scripts/",
            "rebuild-unsigned.sh",
        ):
            if forbidden in block:
                raise SystemExit(
                    f"{job_name} must not check out or execute release source"
                )
        if block.count("actions/attest-build-provenance@") != 1:
            raise SystemExit(f"{job_name} must attest exactly one blind APK")
        require_text(
            block,
            f"subject-path: {subject}",
            f"{job_name} does not attest the exact blind APK subject",
        )

    verifier = blocks["verify_unsigned"]
    require_text(
        verifier,
        "approved_raw_sha256: ${{ steps.raw_compare.outputs.approved_raw_sha256 }}",
        "verify_unsigned does not expose the immutable approved raw digest",
    )
    verifier_steps = named_step_blocks(verifier)
    verifier_order = named_step_names(verifier)
    raw_name = "Prove raw reproducibility with core tools before APK parsing"
    attestation_name = "Verify blind-build provenance before approval"
    parser_name = "Validate unsigned APK metadata and create the approval artifact"
    for step_name in (raw_name, attestation_name, parser_name):
        if step_name not in verifier_steps:
            raise SystemExit(f"verify_unsigned is missing required step: {step_name}")
    if not (
        verifier_order.index(raw_name)
        < verifier_order.index(attestation_name)
        < verifier_order.index(parser_name)
    ):
        raise SystemExit(
            "verify_unsigned must compare raw APKs, verify B attestation, then parse"
        )
    raw_step = verifier_steps[raw_name]
    require_text(
        raw_step,
        "id: raw_compare",
        "Raw reproducibility step is not the immutable output producer",
    )
    require_text(
        raw_step,
        'echo "approved_raw_sha256=$RAW_SHA256" >> "$GITHUB_OUTPUT"',
        "Raw reproducibility step does not emit approved_raw_sha256",
    )
    attestation_step = verifier_steps[attestation_name]
    for required in (
        "gh attestation verify",
        '"$RUNNER_TEMP/independent-unsigned-build/clench-$VERSION-independent-unsigned.apk"',
        '--signer-workflow "$GITHUB_REPOSITORY/.github/workflows/release.yml"',
        '--source-digest "$SOURCE_COMMIT"',
        "--source-ref refs/heads/master",
        "--deny-self-hosted-runners",
    ):
        require_text(
            attestation_step,
            required,
            "Blind-build attestation is not fully verified before approval",
        )

    signer = blocks["sign_release"]
    signer_steps = named_step_blocks(signer)
    signer_order = named_step_names(signer)
    expected_signer_order = [
        "Download the first no-secrets unsigned build",
        "Download only the pre-sign verified unsigned build",
        "Verify the isolated signer input without parsing APK contents",
        "Require release signing secrets",
        "Sign the prebuilt digest with no source checkout",
        "Destroy signing material",
        "Verify signer continuity after key destruction",
        "Attest signed APK provenance",
        "Attest signed APK SBOM",
        "Upload the minimally signed release inputs",
    ]
    if signer_order != expected_signer_order:
        raise SystemExit("Isolated signer must contain the exact ordered step list")
    precheck_name = "Verify the isolated signer input without parsing APK contents"
    sign_name = "Sign the prebuilt digest with no source checkout"
    destroy_name = "Destroy signing material"
    for step_name in (precheck_name, sign_name, destroy_name):
        if step_name not in signer_steps:
            raise SystemExit(f"Isolated signer is missing required step: {step_name}")
    precheck = signer_steps[precheck_name]
    for forbidden in (
        "apksigner",
        "aapt",
        "dump badging",
        "unzip",
        "zipinfo",
        "compare-apk-payloads",
        "scripts/",
    ):
        if forbidden.lower() in precheck.lower():
            raise SystemExit(
                "Signer precheck must not parse attacker-controlled APK contents"
            )
    require_text(
        precheck,
        "APPROVED_RAW_SHA256: ${{ needs.verify_unsigned.outputs.approved_raw_sha256 }}",
        "Signer precheck is not bound to the immutable approved raw digest",
    )

    signing = signer_steps[sign_name]
    require_text(
        signing,
        "APPROVED_RAW_SHA256: ${{ needs.verify_unsigned.outputs.approved_raw_sha256 }}",
        "Signing step is not bound to the immutable approved raw digest",
    )
    for apk_name in ("ORIGINAL_APK", "INDEPENDENT_APK"):
        pattern = re.compile(
            rf'/usr/bin/sha256sum "\${apk_name}".*?=\s*\\?\n?\s*'
            r'"\$APPROVED_RAW_SHA256"',
            re.DOTALL,
        )
        if not pattern.search(signing):
            raise SystemExit(
                f"Signing step does not bind {apk_name} to immutable approval"
            )
    for required in (
        '/usr/bin/cmp "$ORIGINAL_APK" "$INDEPENDENT_APK"',
        "/usr/bin/sha256sum",
        "/usr/bin/awk",
        "/usr/bin/grep",
        "APKSIGNER=/usr/local/lib/android/sdk/build-tools/35.0.0/apksigner",
        "APKSIGNER_JAR=/usr/local/lib/android/sdk/build-tools/35.0.0/lib/apksigner.jar",
        "APKSIGNER_SHADOW_JAR=/usr/local/lib/android/sdk/build-tools/35.0.0/apksigner.jar",
        "b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0",
        "00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7",
        "cleanup_signing_material()",
        "trap cleanup_signing_material EXIT",
        "cleanup_signing_material",
        "trap - EXIT INT TERM",
        'test ! -e "$KEYSTORE"',
    ):
        require_text(
            signing,
            required,
            f"Signing step is missing point-of-use control: {required}",
        )

    sign_index = signer_order.index(sign_name)
    if sign_index + 1 >= len(signer_order) or signer_order[sign_index + 1] != destroy_name:
        raise SystemExit(
            "Destroy signing material must be the immediately adjacent named step"
        )
    require_text(
        signer_steps[destroy_name],
        "if: always()",
        "Signing material destruction is not unconditional",
    )

    release_verifier = blocks["verify_release"]
    release_steps = named_step_blocks(release_verifier)
    c_attestation_name = "Verify second blind-build provenance before comparison"
    require_text(
        release_verifier,
        "attest_post_sign_unsigned",
        "Post-sign verifier does not wait for C attestation",
    )
    if c_attestation_name not in release_steps:
        raise SystemExit("Post-sign verifier does not verify C attestation")
    for required in (
        "gh attestation verify",
        '"$RUNNER_TEMP/post-sign-unsigned-build/clench-$VERSION-independent-unsigned.apk"',
        '--signer-workflow "$GITHUB_REPOSITORY/.github/workflows/release.yml"',
        '--source-digest "$SOURCE_COMMIT"',
        "--source-ref refs/heads/master",
        "--deny-self-hosted-runners",
    ):
        require_text(
            release_steps[c_attestation_name],
            required,
            "Post-sign verifier does not fully verify C attestation",
        )

    publisher = blocks["publish"]
    publish_name = "Reverify the complete bundle and publish without signing credentials"
    publish_steps = named_step_blocks(publisher)
    publish_order = named_step_names(publisher)
    expected_publish_order = [
        "Check out the trusted release gate",
        "Set up JDK 21 for final publication verification",
        "Reverify immutable source tag immediately before publication",
        "Download verified release bundle",
        publish_name,
    ]
    if publish_order != expected_publish_order:
        raise SystemExit("Publisher must contain the exact ordered step list")
    publish_step = publish_steps[publish_name]
    loop_match = re.search(
        r"for evidence in \\\n(?P<subjects>.*?)\s*; do\n"
        r"(?P<body>.*?)^\s*done\s*$",
        publish_step,
        re.MULTILINE | re.DOTALL,
    )
    if not loop_match:
        raise SystemExit("Publication does not use a bounded evidence attestation loop")
    required_evidence = {
        '"release-artifacts/clench-$VERSION-release.apk"',
        '"release-artifacts/clench-$VERSION-unsigned.apk"',
        '"release-artifacts/clench-$VERSION-sbom.cdx.json"',
        "release-artifacts/INDEPENDENT-APK-VERIFICATION.json",
        "release-artifacts/UNSIGNED-APPROVAL.txt",
        "release-artifacts/ORIGINAL-UNSIGNED-BUILD-SHA256SUMS",
        "release-artifacts/POST-SIGN-UNSIGNED-BUILD-SHA256SUMS",
        "release-artifacts/VERIFIED-UNSIGNED-SHA256SUMS",
    }
    loop_subjects = {
        line.strip().removesuffix(" \\")
        for line in loop_match.group("subjects").splitlines()
        if line.strip()
    }
    if loop_subjects != required_evidence:
        raise SystemExit("Publication does not attest the exact required evidence set")
    loop_body = loop_match.group("body")
    for required in (
        'gh attestation verify "$evidence"',
        '--signer-workflow "$GITHUB_REPOSITORY/.github/workflows/release.yml"',
        '--source-digest "$SOURCE_COMMIT"',
        "--source-ref refs/heads/master",
        "--deny-self-hosted-runners",
    ):
        require_text(
            loop_body,
            required,
            "Publication evidence attestation verification is incomplete",
        )
    immediate_publish = (
        "scripts/release/verify-release-bundle.sh release-artifacts\n"
        '          gh release create "$RELEASE_TAG"'
    )
    require_text(
        publish_step,
        immediate_publish,
        "Publication must fully verify the bundle immediately before release creation",
    )


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

    hostile_runner = Path("scripts/verification/run-hostile-fuzz.sh").read_text(
        encoding="utf-8"
    )
    for required_control in (
        "--no-build-cache",
        "--rerun-tasks",
        "HostileFuzzExecutionContractTest",
        "CLENCH_HOSTILE_FUZZ_EXECUTED",
    ):
        if required_control not in hostile_runner:
            raise SystemExit(
                f"Hostile fuzz runner lacks execution control: {required_control}"
            )
    android_workflow = Path(".github/workflows/android.yml").read_text(encoding="utf-8")
    if "scripts/verification/test-hostile-fuzz-runner.py" not in android_workflow:
        raise SystemExit("Android CI does not exercise hostile fuzz runner self-tests")

    instrumentation_workflow = Path(
        ".github/workflows/android-instrumentation.yml"
    ).read_text(encoding="utf-8")
    for required_path in (
        "gradle/libs.versions.toml",
        "app/src/main/java/net/clench/wallet/ClenchApplication.kt",
        "app/src/main/java/net/clench/wallet/data/backup/ClenchStateBackupManager.kt",
        "app/src/main/java/net/clench/wallet/data/repository/BdkBitcoinRepository.kt",
        "app/src/main/java/net/clench/wallet/domain/model/BdkNetworkKind.kt",
        "app/src/main/java/net/clench/wallet/domain/model/ScriptType.kt",
        "app/src/main/java/net/clench/wallet/ui/viewmodel/ImportWalletViewModel.kt",
        "scripts/verification/bdk-wallet-upgrade/**",
        "scripts/verification/run-bdk2-bdk3-inplace-upgrade.sh",
    ):
        if required_path not in instrumentation_workflow:
            raise SystemExit(
                "Android instrumentation path filter omits BDK-sensitive source: "
                f"{required_path}"
            )
    for required_contract in (
        "net.clench.wallet.data.repository.BdkWalletPersistenceTest",
        "sqliteWalletReloadsExactRevealedTestnetAddresses",
        "Expected the exact seven named instrumentation tests to pass",
        "fetch-depth: 0",
        "test -e /dev/kvm",
        "Prove exact BDK 2.3.1 to 3.0.0 persisted-wallet upgrade",
        "CLENCH_BDK_UPGRADE_ALLOW_EMULATOR_RESET: YES",
        "scripts/verification/run-bdk2-bdk3-inplace-upgrade.sh",
        "bdk2-to-bdk3-upgrade-evidence",
        "EXPECTED_SOURCE_COMMIT: ${{ github.event.pull_request.head.sha || github.sha }}",
        "ref: ${{ github.event.pull_request.head.sha || github.sha }}",
        'test "$(git rev-parse --verify HEAD^{commit})" = "$EXPECTED_SOURCE_COMMIT"',
    ):
        if required_contract not in instrumentation_workflow:
            raise SystemExit(
                f"Android instrumentation lacks BDK persistence contract: "
                f"{required_contract}"
            )

    bdk_upgrade_runner = Path(
        "scripts/verification/run-bdk2-bdk3-inplace-upgrade.sh"
    ).read_text(encoding="utf-8")
    testnet3_genesis_hash = (
        "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"
    )
    if not re.fullmatch(r"[0-9a-f]{64}", testnet3_genesis_hash):
        raise SystemExit("Release-control verifier has an invalid Testnet3 genesis hash")
    for fixture_path in (
        "scripts/verification/bdk-wallet-upgrade/fixtures/bdk2/"
        "Bdk2PersistedWalletSeederTest.kt",
        "scripts/verification/bdk-wallet-upgrade/fixtures/bdk3/"
        "Bdk3PersistedWalletVerifierTest.kt",
    ):
        fixture_source = Path(fixture_path).read_text(encoding="utf-8")
        if fixture_source.count(testnet3_genesis_hash) != 1:
            raise SystemExit(
                f"BDK upgrade fixture lacks canonical Testnet3 genesis hash: {fixture_path}"
            )
    if bdk_upgrade_runner.count(testnet3_genesis_hash) != 1:
        raise SystemExit(
            "BDK in-place upgrade evidence validator lacks canonical Testnet3 genesis hash"
        )
    for required_control in (
        "GIT_NO_REPLACE_OBJECTS=1",
        "refs/replace/",
        "info/grafts",
        "merge-base --is-ancestor",
        "--dependency-verification=strict",
        "dump xmltree",
        "HARNESS_ANDROID_USER_HOME",
        "query_installed_packages()",
        "package_is_installed()",
        "cmd package list packages",
        're.fullmatch(r"[0-9a-f]{64}", evidence["checkpoint.hash"])',
        'service check "$service_name"',
        '== "Service $service_name: found"',
        "upgrade_result.sha256",
        "bdk2.database_before_install.sha256",
        "bdk2.database_after_install.sha256",
        "database.install_preserved=true",
        "bdk2.gradle_log.sha256",
        "bdk3.gradle_log.sha256",
        "bdk2.lockfile.sha256",
        "bdk3.lockfile.sha256",
        "bdk2.verification_metadata.sha256",
        "bdk3.verification_metadata.sha256",
        "mktemp -d /tmp/clench-bdk-upgrade.XXXXXX",
        "sha256_file()",
        "UPGRADE_RESULT_SHA256",
        "cleanup_failed",
        'uninstall "$package_name"',
    ):
        if required_control not in bdk_upgrade_runner:
            raise SystemExit(
                f"BDK in-place upgrade gate lacks fail-closed control: {required_control}"
            )
    if bdk_upgrade_runner.index("DEVICE_TOUCHED=1") > bdk_upgrade_runner.index(
        'install -r "$BDK2_APK"'
    ):
        raise SystemExit(
            "BDK in-place upgrade cleanup must activate before the first adb install"
        )
    for package_state_control in (
        'INSTALLED_PACKAGES="$(query_installed_packages)"',
        'if package_is_installed "$INSTALLED_PACKAGES" "$TARGET_PACKAGE"',
        'if package_is_installed "$INSTALLED_PACKAGES" "$TEST_PACKAGE"',
    ):
        if package_state_control not in bdk_upgrade_runner:
            raise SystemExit(
                "BDK in-place upgrade preflight can ignore PackageManager failure"
            )
    if re.search(r"(?m)^\s*assert\s+", bdk_upgrade_runner):
        raise SystemExit(
            "BDK in-place upgrade evidence must not rely on optimizable Python assertions"
        )
    if re.search(r'(?m)^readonly\s+[A-Za-z0-9_]+="\$\(', bdk_upgrade_runner):
        raise SystemExit(
            "BDK in-place upgrade gate masks command-substitution failures with readonly"
        )
    if re.search(r"(?m)^\s*\[\[[^\n]*\$\(", bdk_upgrade_runner):
        raise SystemExit(
            "BDK in-place upgrade gate masks command-substitution failures inside [[ ]]"
        )
    if re.search(r"printf[^\n]*\$\(sha256sum", bdk_upgrade_runner):
        raise SystemExit(
            "BDK in-place upgrade manifest masks checksum failures inside printf"
        )

    bdk2_upgrade_fixture = Path(
        "scripts/verification/bdk-wallet-upgrade/fixtures/bdk2/"
        "Bdk2PersistedWalletSeederTest.kt"
    ).read_text(encoding="utf-8")
    bdk3_upgrade_fixture = Path(
        "scripts/verification/bdk-wallet-upgrade/fixtures/bdk3/"
        "Bdk3PersistedWalletVerifierTest.kt"
    ).read_text(encoding="utf-8")
    for required_graph_control in (
        "applyUnconfirmedTxs",
        "FIXTURE_VALUE_SAT = 50_000L",
        "transaction_txid",
        "unspent_outpoint",
        "checkpoint_hash",
    ):
        if required_graph_control not in bdk2_upgrade_fixture:
            raise SystemExit(
                "BDK2 upgrade fixture lacks non-empty offline graph control: "
                f"{required_graph_control}"
            )
    for required_production_control in (
        "bitcoinRepository.getBalance",
        "bitcoinRepository.getLastAddress",
        "completeSensitiveSessionEviction",
        "production.load_verified",
        "wallet.checkpoints()",
    ):
        if required_production_control not in bdk3_upgrade_fixture:
            raise SystemExit(
                "BDK3 upgrade fixture bypasses production/persisted state control: "
                f"{required_production_control}"
            )

    workflow = Path(".github/workflows/release.yml").read_text(encoding="utf-8")
    verify_release_workflow(workflow)
    blocks = job_blocks(workflow)
    required_jobs = {
        "validate_source",
        "build_unsigned",
        "build_independent_unsigned",
        "attest_independent_unsigned",
        "verify_unsigned",
        "build_post_sign_unsigned",
        "attest_post_sign_unsigned",
        "sign_release",
        "verify_release",
        "publish",
    }
    if set(blocks) != required_jobs:
        raise SystemExit(
            "Release workflow must contain exactly the required isolated jobs"
        )
    if "workflow_dispatch:" not in workflow:
        raise SystemExit("Release workflow must be dispatched from protected master")
    if (
        '[[ ! "$RELEASE_TAG" =~ '
        '^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.'
        '(0|[1-9][0-9]*)$ ]]'
        not in workflow
    ):
        raise SystemExit("Release source gate does not enforce strict semantic tags")
    if re.search(r"(?m)^\s*push:\s*$", workflow):
        raise SystemExit("Release workflow must not execute tag-controlled workflow code")
    if workflow.count("scripts/release/check-osv.py") < 2:
        raise SystemExit("Release build and independent verifier do not both run OSV")
    if "scripts/verification/test-osv-check.py" not in blocks["build_unsigned"]:
        raise SystemExit("Release build does not exercise hostile OSV-gate tests")
    if "environment: release-signing" not in blocks["sign_release"]:
        raise SystemExit("Signing job is not bound to the protected release environment")
    for job in (
        "build_unsigned",
        "build_independent_unsigned",
        "build_post_sign_unsigned",
    ):
        if blocks[job].count("gradle/actions/wrapper-validation@") != 1:
            raise SystemExit(
                f"{job} must validate the wrapper JAR exactly once"
            )
    for job in (
        "validate_source",
        "build_unsigned",
        "build_independent_unsigned",
        "attest_independent_unsigned",
        "verify_unsigned",
        "build_post_sign_unsigned",
        "attest_post_sign_unsigned",
        "verify_release",
        "publish",
    ):
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
    signer_steps = named_step_blocks(signer)
    required_signer_steps = {
        "Download the first no-secrets unsigned build",
        "Download only the pre-sign verified unsigned build",
        "Verify the isolated signer input without parsing APK contents",
        "Require release signing secrets",
        "Sign the prebuilt digest with no source checkout",
        "Destroy signing material",
        "Verify signer continuity after key destruction",
        "Attest signed APK provenance",
        "Attest signed APK SBOM",
        "Upload the minimally signed release inputs",
    }
    if not required_signer_steps.issubset(signer_steps):
        raise SystemExit("Isolated signer is missing a required named step")
    for forbidden in ("actions/checkout@", "./gradlew", "keystore.properties"):
        if forbidden in signer:
            raise SystemExit(f"Isolated signer must not receive source/build input: {forbidden}")
    if re.search(r"(?m)^\s+scripts/", signer):
        raise SystemExit("Isolated signer must not execute tag-controlled repository scripts")
    for required_global_control in (
        "EXPECTED_APKSIGNER_SHA256",
        "EXPECTED_APKSIGNER_JAR_SHA256",
        "EXPECTED_AAPT_SHA256",
        "APKSIGNER_SHADOW_JAR",
    ):
        if required_global_control not in workflow:
            raise SystemExit(
                f"Release workflow is missing pinned signer control: "
                f"{required_global_control}"
            )
    workflow_env: dict[str, str] = {}
    for name in TOOLCHAIN_ENV_TO_PYTHON:
        match = re.search(rf"(?m)^  {re.escape(name)}: ([^\s]+)$", workflow)
        if not match:
            raise SystemExit(f"Release workflow does not define {name} exactly once")
        workflow_env[name] = match.group(1)
    comparator_assignments = literal_assignments(
        Path("scripts/release/compare-apk-payloads.py")
    )
    validator_assignments = literal_assignments(
        Path("scripts/release/validate-independent-report.py")
    )
    for env_name, python_name in TOOLCHAIN_ENV_TO_PYTHON.items():
        if comparator_assignments.get(python_name) != workflow_env[env_name]:
            raise SystemExit(
                "compare-apk-payloads.py "
                f"{python_name} does not match workflow {env_name}"
            )
    validator_toolchain = validator_assignments.get("EXPECTED_TOOLCHAIN")
    expected_validator_toolchain = {
        "aaptSha256": workflow_env["EXPECTED_AAPT_SHA256"],
        "apksignerBuildToolsVersion": workflow_env[
            "APKSIGNER_BUILD_TOOLS_VERSION"
        ],
        "apksignerExecutableSha256": workflow_env[
            "EXPECTED_APKSIGNER_SHA256"
        ],
        "apksignerJarSha256": workflow_env[
            "EXPECTED_APKSIGNER_JAR_SHA256"
        ],
    }
    if validator_toolchain != expected_validator_toolchain:
        raise SystemExit(
            "validate-independent-report.py toolchain does not match workflow pins"
        )
    for name_pair in (
        ("COMPARISON_POLICY", "COMPARISON_POLICY"),
        ("SIGNING_PROFILE", "EXPECTED_SIGNING_PROFILE"),
        ("VERIFICATION_PROFILE", "EXPECTED_VERIFICATION_PROFILE"),
        ("SIGNING_BLOCK_POLICY", "EXPECTED_SIGNING_BLOCK_POLICY"),
    ):
        if comparator_assignments.get(name_pair[0]) != validator_assignments.get(
            name_pair[1]
        ):
            raise SystemExit(
                "Independent APK report producer and validator disagree on "
                f"{name_pair[0]}"
            )
    signer_input_step = signer_steps[
        "Verify the isolated signer input without parsing APK contents"
    ]
    for required_signer_control in (
        "sha256sum --strict -c BUILD-SHA256SUMS",
        "sha256sum --strict -c VERIFIED-UNSIGNED-SHA256SUMS",
        'cmp "$ORIGINAL_APK" "$INDEPENDENT_APK"',
        "rawUnsignedByteIdentical=true",
    ):
        if required_signer_control not in signer_input_step:
            raise SystemExit(
                "Isolated signer input step is missing a pinned-input control: "
                f"{required_signer_control}"
            )
    signing_step = signer_steps["Sign the prebuilt digest with no source checkout"]
    for required_signer_control in (
        '/usr/bin/cmp "$ORIGINAL_APK" "$INDEPENDENT_APK"',
        "APPROVED_RAW_SHA256: ${{ needs.verify_unsigned.outputs.approved_raw_sha256 }}",
        "/usr/bin/sha256sum",
        "/usr/bin/awk",
        "APKSIGNER=/usr/local/lib/android/sdk/build-tools/35.0.0/apksigner",
        "APKSIGNER_JAR=/usr/local/lib/android/sdk/build-tools/35.0.0/lib/apksigner.jar",
        "APKSIGNER_SHADOW_JAR",
        "b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0",
        "00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7",
        "trap cleanup_signing_material EXIT",
        'test ! -e "$KEYSTORE"',
        "--v1-signing-enabled false",
        "--v2-signing-enabled true",
        "--v3-signing-enabled true",
        "--v4-signing-enabled false",
        "--verity-enabled false",
        "--min-sdk-version 26",
        "--alignment-preserved false",
        "--lib-page-alignment 16384",
        'test ! -e "$RUNNER_TEMP/signed-release/clench-$VERSION-release.apk.idsig"',
    ):
        if required_signer_control not in signing_step:
            raise SystemExit(
                "Isolated signing step is missing a point-of-use control: "
                f"{required_signer_control}"
            )
    destroy_step = signer_steps["Destroy signing material"]
    if "if: always()" not in destroy_step:
        raise SystemExit("Signing material destruction is not unconditional")

    independent_verifier = Path(
        "scripts/release/verify-independent-apk.sh"
    ).read_text(encoding="utf-8")
    for required_normalization_control in (
        "EXPECTED_APKSIGNER_SHA256",
        "EXPECTED_APKSIGNER_JAR_SHA256",
        "EXPECTED_AAPT_SHA256",
        "APKSIGNER_SHADOW_JAR",
        "keytool",
        "-keysize 4096",
        "--v1-signing-enabled false",
        "--v2-signing-enabled true",
        "--v3-signing-enabled true",
        "--v4-signing-enabled false",
        "--verity-enabled false",
        "--min-sdk-version 26",
        "--alignment-preserved false",
        "--lib-page-alignment 16384",
        'test ! -e "$NORMALIZED_APK.idsig"',
        'test ! -e "$EPHEMERAL_KEYSTORE"',
        "--independent-unsigned",
        "--signer-input-unsigned",
        "SIGNER_INPUT_UNSIGNED",
        "SIGNER_INPUT_SHA256_BEFORE",
        "INDEPENDENT_SHA256_BEFORE",
        "apksigner-v2-v3-ephemeral-rsa4096",
    ):
        if required_normalization_control not in independent_verifier:
            raise SystemExit(
                "Independent verifier is missing apksigner normalization control: "
                f"{required_normalization_control}"
            )

    pre_sign_verifier = blocks["verify_unsigned"]
    if "needs: validate_source" not in blocks["build_unsigned"]:
        raise SystemExit("Unsigned builder is not gated on validated source")
    blind_builder = blocks["build_independent_unsigned"]
    post_sign_builder = blocks["build_post_sign_unsigned"]
    for job_name, block, gradle_home in (
        (
            "build_independent_unsigned",
            blind_builder,
            "gradle-pre-sign-independent",
        ),
        (
            "build_post_sign_unsigned",
            post_sign_builder,
            "gradle-post-sign-independent",
        ),
    ):
        if "needs: validate_source" not in block:
            raise SystemExit(f"{job_name} is not bound only to validated source")
        if "actions/download-artifact@" in block:
            raise SystemExit(f"{job_name} receives expected artifacts before building")
        for cleared_token in (
            "ACTIONS_RUNTIME_TOKEN: ''",
            "ACTIONS_RESULTS_URL: ''",
            "GH_TOKEN: ''",
            "GITHUB_TOKEN: ''",
        ):
            if cleared_token not in block:
                raise SystemExit(
                    f"{job_name} does not clear artifact/API credentials: "
                    f"{cleared_token}"
                )
        if f"GRADLE_USER_HOME: ${{{{ runner.temp }}}}/{gradle_home}" not in block:
            raise SystemExit(f"{job_name} lacks a distinct Gradle user home")
        if "cache-disabled: true" not in block:
            raise SystemExit(f"{job_name} may restore an untrusted build cache")
        if "scripts/release/rebuild-unsigned.sh" not in block:
            raise SystemExit(f"{job_name} does not run the pinned independent build")
    if (
        "needs: [validate_source, build_unsigned, build_independent_unsigned, attest_independent_unsigned]"
        not in pre_sign_verifier
    ):
        raise SystemExit("Pre-sign verifier is not gated on both blind builds")
    for required_raw_identity_control in (
        "Prove raw reproducibility with core tools before APK parsing",
        "Download the original no-secrets build",
        "sha256sum --strict -c BUILD-SHA256SUMS",
        'cmp "$ORIGINAL" "$INDEPENDENT"',
        "rawUnsignedByteIdentical=true",
        "verified-unsigned-release-$",
    ):
        if required_raw_identity_control not in pre_sign_verifier:
            raise SystemExit(
                "Pre-sign verifier is missing raw reproducibility control: "
                f"{required_raw_identity_control}"
            )
    if "needs: [validate_source, verify_unsigned]" not in signer:
        raise SystemExit("Release signer is not gated on pre-sign reproducibility")
    verifier_job = blocks["verify_release"]
    if (
        "needs: [validate_source, verify_unsigned, build_post_sign_unsigned, attest_post_sign_unsigned, sign_release]"
        not in verifier_job
    ):
        raise SystemExit(
            "Post-sign verifier is not gated on both blind builds and signing"
        )
    for required_post_sign_control in (
        "Download the pre-sign verified signer input",
        "Verify and bind the original no-secrets signer input",
        "ORIGINAL-UNSIGNED-BUILD-SHA256SUMS",
        "VERIFIED-UNSIGNED-SHA256SUMS",
        "POST-SIGN-UNSIGNED-BUILD-SHA256SUMS",
        "UNSIGNED-APPROVAL.txt",
        "verified-unsigned-release/clench-$VERSION-independent-unsigned.apk",
        'scripts/release/verify-release-bundle.sh "$ARTIFACTS" --pre-independent',
        'scripts/release/verify-release-bundle.sh "$RUNNER_TEMP/release-artifacts"',
    ):
        if required_post_sign_control not in verifier_job:
            raise SystemExit(
                "Post-sign verifier is missing evidence control: "
                f"{required_post_sign_control}"
            )
    if "scripts/release/rebuild-unsigned.sh" in verifier_job:
        raise SystemExit("Post-sign verifier must consume a previously blind rebuild")
    rebuild_script = Path(
        "scripts/release/rebuild-unsigned.sh"
    ).read_text(encoding="utf-8")
    if "--no-build-cache" not in rebuild_script:
        raise SystemExit("Independent rebuild script does not disable Gradle build caching")
    bundle_verifier = Path(
        "scripts/release/verify-release-bundle.sh"
    ).read_text(encoding="utf-8")
    for path, source in (
        (Path("scripts/release/rebuild-unsigned.sh"), rebuild_script),
        (Path("scripts/release/verify-independent-apk.sh"), independent_verifier),
        (Path("scripts/release/verify-release-bundle.sh"), bundle_verifier),
    ):
        for forbidden_override in (
            '${APKSIGNER:-',
            '${AAPT:-',
            '${KEYTOOL:-',
        ):
            if forbidden_override in source:
                raise SystemExit(
                    f"{path} permits a verification-tool path override: "
                    f"{forbidden_override}"
                )
    for required_bundle_control in (
        'MODE="${2:-final}"',
        'test -f "$INDEPENDENT_REPORT"',
        'test ! -L "$INDEPENDENT_REPORT"',
        "ORIGINAL-UNSIGNED-BUILD-SHA256SUMS",
        "VERIFIED-UNSIGNED-SHA256SUMS",
        "UNSIGNED-APPROVAL.txt",
        "POST-SIGN-UNSIGNED-BUILD-SHA256SUMS",
        "signerInputUnsignedApkSha256",
        "independentUnsignedApkSha256",
        "EXPECTED_AAPT_SHA256",
        "APKSIGNER_SHADOW_JAR",
        "--min-sdk-version 26",
        "Verified using v1 scheme (JAR signing): false",
        "Verified using v3 scheme (APK Signature Scheme v3): true",
        "Verified using v4 scheme (APK Signature Scheme v4): false",
    ):
        if required_bundle_control not in bundle_verifier:
            raise SystemExit(
                "Release bundle verifier is missing a final evidence control: "
                f"{required_bundle_control}"
            )
    for required_public_evidence in (
        "release-artifacts/ORIGINAL-UNSIGNED-BUILD-SHA256SUMS",
        "release-artifacts/VERIFIED-UNSIGNED-SHA256SUMS",
        "release-artifacts/POST-SIGN-UNSIGNED-BUILD-SHA256SUMS",
        "release-artifacts/UNSIGNED-APPROVAL.txt",
        'release-artifacts/clench-$VERSION-unsigned.apk',
        "INDEPENDENT-APK-VERIFICATION.json",
    ):
        if required_public_evidence not in blocks["publish"]:
            raise SystemExit(
                "Publication omits reproducibility evidence: "
                f"{required_public_evidence}"
            )

    for secret in RELEASE_SECRET_NAMES:
        reference = "${{ secrets." + secret + " }}"
        if reference not in signer:
            raise SystemExit(f"Signing job does not require {secret}")
        for job, block in blocks.items():
            if job != "sign_release" and reference in block:
                raise SystemExit(f"{secret} leaked into non-signing job {job}")

    first_download_offset = signer.find(
        "name: Download the first no-secrets unsigned build"
    )
    second_download_offset = signer.find(
        "name: Download only the pre-sign verified unsigned build"
    )
    destroy_offset = signer.find("name: Destroy signing material")
    input_verification_offset = signer.find(
        "name: Verify the isolated signer input without parsing APK contents"
    )
    secret_offset = signer.find("name: Require release signing secrets")
    sign_offset = signer.find("name: Sign the prebuilt digest with no source checkout")
    verify_offset = signer.find("name: Verify signer continuity after key destruction")
    attest_offset = signer.find("name: Attest signed APK provenance")
    upload_offset = signer.find("name: Upload the minimally signed release inputs")
    if not (
        0
        <= first_download_offset
        < second_download_offset
        < input_verification_offset
        < secret_offset
        < sign_offset
        < destroy_offset
        < verify_offset
        < attest_offset
        < upload_offset
    ):
        raise SystemExit(
            "Signer input is not verified before secret access, or signing material "
            "is not destroyed immediately after signing and before verification, "
            "attestation, or artifact upload"
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
    if "name: Attest the final no-secrets verification evidence" not in blocks["verify_release"]:
        raise SystemExit("Final no-secrets evidence is not publicly attested")
    if "Verify blind-build provenance for the published unsigned evidence" not in blocks["verify_release"]:
        raise SystemExit("Published unsigned evidence is not bound to a blind build attestation")
    if "Reverify the complete bundle and publish without signing credentials" not in blocks["publish"]:
        raise SystemExit("Publication does not reverify no-secrets evidence attestations")
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
