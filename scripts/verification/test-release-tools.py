#!/usr/bin/env python3
"""Hostile self-tests for Clench release-evidence tooling."""

from __future__ import annotations

import json
import copy
import importlib.util
import re
import struct
import subprocess
import sys
import tempfile
import warnings
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PYTHON = [sys.executable, "-B"]


def load_release_controls():
    path = ROOT / "scripts/release/verify-release-controls.py"
    spec = importlib.util.spec_from_file_location("clench_release_controls", path)
    if spec is None or spec.loader is None:
        raise SystemExit("Could not load release-control verifier")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def replace_once(source: str, old: str, new: str, *, label: str) -> str:
    if source.count(old) != 1:
        raise SystemExit(
            f"Workflow mutation {label} expected one match, found {source.count(old)}"
        )
    return source.replace(old, new, 1)


def replace_once_in_job(
    workflow: str,
    controls,
    job_name: str,
    old: str,
    new: str,
    *,
    label: str,
) -> str:
    block = controls.job_blocks(workflow)[job_name]
    mutated = replace_once(block, old, new, label=label)
    return replace_once(workflow, block, mutated, label=f"{label}-job-block")


def swap_named_steps(
    workflow: str,
    controls,
    job_name: str,
    first: str,
    second: str,
) -> str:
    job = controls.job_blocks(workflow)[job_name]
    steps = controls.named_step_blocks(job)
    first_block = steps[first]
    second_block = steps[second]
    placeholder = "__CLENCH_RELEASE_STEP_SWAP_PLACEHOLDER__\n"
    if placeholder in job:
        raise SystemExit("Unexpected workflow step-swap placeholder collision")
    mutated = job.replace(first_block, placeholder, 1)
    mutated = mutated.replace(second_block, first_block, 1)
    mutated = mutated.replace(placeholder, second_block, 1)
    return replace_once(workflow, job, mutated, label="swap-named-steps")


def expect_workflow_failure(
    controls,
    workflow: str,
    label: str,
    expected: str,
) -> None:
    try:
        controls.verify_release_workflow(workflow)
    except SystemExit as error:
        if expected not in str(error):
            raise SystemExit(
                f"Workflow mutation {label} failed for the wrong reason.\n"
                f"Expected: {expected}\nActual: {error}"
            ) from error
    else:
        raise SystemExit(f"Workflow mutation unexpectedly passed: {label}")


def run(
    *arguments: object,
    should_pass: bool = True,
    expected_output: str | None = None,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [str(argument) for argument in arguments],
        cwd=ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if (result.returncode == 0) != should_pass:
        outcome = "pass" if should_pass else "fail"
        raise SystemExit(
            f"Expected command to {outcome}: {' '.join(map(str, arguments))}\n"
            f"{result.stdout}"
        )
    if expected_output is not None and expected_output not in result.stdout:
        raise SystemExit(
            "Command failed for the wrong reason: "
            f"{' '.join(map(str, arguments))}\n"
            f"Expected output: {expected_output}\nActual output:\n{result.stdout}"
        )
    return result


def write_canonical_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def write_apk(
    path: Path,
    *,
    changed_entry: int | None = None,
    custom_meta: bool = False,
    duplicate: bool = False,
    traversal: bool = False,
    signatures: bool = False,
    stored_entry: int | None = None,
    orphan_signature: bool = False,
    extra_directory: bool = False,
    prefix: bool = False,
    signed_layout: bool = True,
    signing_block_variant: str = "valid",
) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for index in range(100):
            info = zipfile.ZipInfo(
                f"assets/fixture-{index:03d}.bin",
                date_time=(1980, 1, 1, 0, 0, 0),
            )
            info.compress_type = (
                zipfile.ZIP_STORED
                if stored_entry == index
                else zipfile.ZIP_DEFLATED
            )
            payload = (
                b"changed"
                if changed_entry == index
                else f"fixture-{index:03d}".encode("ascii")
            )
            archive.writestr(info, payload)
        if signatures:
            archive.writestr("META-INF/MANIFEST.MF", b"manifest")
            archive.writestr("META-INF/CLENCH.SF", b"signature metadata")
            archive.writestr("META-INF/CLENCH.RSA", b"signature block")
        if custom_meta:
            archive.writestr("META-INF/SECURITY-POLICY.MF", b"must compare")
        if orphan_signature:
            archive.writestr("META-INF/ORPHAN.EC", b"not a signature pair")
        if extra_directory:
            archive.writestr("assets/extra-directory/", b"")
        if duplicate:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr("assets/fixture-000.bin", b"duplicate")
        if traversal:
            archive.writestr("../escaped", b"unsafe")
    if signed_layout:
        add_fake_apk_signing_block(path, variant=signing_block_variant)
    if prefix:
        add_aligned_zip_prefix(path)


def add_aligned_zip_prefix(path: Path) -> None:
    """Add a valid 4 KiB ZIP prefix while preserving every archive offset."""

    raw = bytearray(path.read_bytes())
    marker = raw.rfind(b"PK\x05\x06")
    if marker < 0 or marker + 22 > len(raw):
        raise SystemExit("Test APK has no complete end-of-central-directory record")
    fields = struct.unpack("<IHHHHIIH", raw[marker : marker + 22])
    central_size = fields[5]
    central_offset = fields[6]
    central_end = central_offset + central_size
    if central_end != marker:
        raise SystemExit("Test APK central-directory bounds are unexpected")

    prefix = b"CLENCH-APK-PREFIX-TEST\0".ljust(4096, b"\0")
    cursor = central_offset
    while cursor < central_end:
        if raw[cursor : cursor + 4] != b"PK\x01\x02":
            raise SystemExit("Test APK central-directory entry is malformed")
        name_length, extra_length, comment_length = struct.unpack_from(
            "<HHH", raw, cursor + 28
        )
        local_offset = struct.unpack_from("<I", raw, cursor + 42)[0]
        struct.pack_into("<I", raw, cursor + 42, local_offset + len(prefix))
        cursor += 46 + name_length + extra_length + comment_length
    if cursor != central_end:
        raise SystemExit("Test APK central-directory walk has invalid bounds")
    struct.pack_into("<I", raw, marker + 16, central_offset + len(prefix))
    path.write_bytes(prefix + raw)


def add_fake_apk_signing_block(path: Path, *, variant: str = "valid") -> None:
    raw = path.read_bytes()
    marker = raw.rfind(b"PK\x05\x06")
    if marker < 0:
        raise SystemExit("Test APK has no end-of-central-directory record")
    fields = struct.unpack("<IHHHHIIH", raw[marker : marker + 22])
    central_offset = fields[6]
    def pair(identifier: int, value: bytes) -> bytes:
        return struct.pack("<Q", 4 + len(value)) + struct.pack("<I", identifier) + value

    v2_value = b"synthetic-v2"
    v3_value = b"synthetic-v3"
    padding_size = (-(68 + len(v2_value) + len(v3_value))) % 4096
    if padding_size == 0:
        raise SystemExit("Synthetic Signing Block unexpectedly needs no padding")
    pairs = [
        pair(0x7109871A, v2_value),
        pair(0xF05368C0, v3_value),
        pair(0x42726577, b"\0" * padding_size),
    ]
    if variant == "unknown":
        pairs.insert(2, pair(0xDEADBEEF, b"hidden"))
    elif variant == "duplicate":
        pairs.insert(1, pair(0x7109871A, b"duplicate-v2"))
    elif variant == "missing-v3":
        del pairs[1]
    elif variant == "nonzero-padding":
        pairs[-1] = pair(0x42726577, b"\0" * (padding_size - 1) + b"X")
    elif variant == "oversized-padding":
        pairs[-1] = pair(0x42726577, b"\0" * (padding_size + 4096))
    elif variant == "wrong-order":
        pairs[0], pairs[1] = pairs[1], pairs[0]
    elif variant != "valid":
        raise ValueError(f"Unknown signing-block test variant: {variant}")
    pair_bytes = b"".join(pairs)
    block_size = len(pair_bytes) + 24
    signing_block = (
        struct.pack("<Q", block_size)
        + pair_bytes
        + struct.pack("<Q", block_size)
        + b"APK Sig Block 42"
    )
    pre_block_padding = b"\0" * ((-central_offset) % 4096)
    inserted = pre_block_padding + signing_block
    updated = bytearray(raw[:central_offset] + inserted + raw[central_offset:])
    updated_marker = marker + len(inserted)
    struct.pack_into("<I", updated, updated_marker + 16, central_offset + len(inserted))
    path.write_bytes(updated)


def version_name() -> str:
    text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not match:
        raise SystemExit("Could not read app version for release-tool self-test")
    return match.group(1)


def test_apk_comparison(temp: Path) -> None:
    signed = temp / "clench-0.0.0-release.apk"
    normalized = temp / "normalized-independent.apk"
    signer_input = temp / "clench-0.0.0-unsigned.apk"
    unsigned = temp / "clench-0.0.0-independent-unsigned.apk"
    report = temp / "report.json"
    unsigned_approval = temp / "UNSIGNED-APPROVAL.txt"
    original_build_sums = temp / "ORIGINAL-UNSIGNED-BUILD-SHA256SUMS"
    verified_build_sums = temp / "VERIFIED-UNSIGNED-SHA256SUMS"
    post_sign_build_sums = temp / "POST-SIGN-UNSIGNED-BUILD-SHA256SUMS"
    unsigned_approval.write_text("test approval\n", encoding="utf-8")
    original_build_sums.write_text("original test checksums\n", encoding="utf-8")
    verified_build_sums.write_text("verified test checksums\n", encoding="utf-8")
    post_sign_build_sums.write_text("post-sign test checksums\n", encoding="utf-8")
    comparison_evidence = (
        "--unsigned-approval",
        unsigned_approval,
        "--original-unsigned-build-sha256s",
        original_build_sums,
        "--verified-unsigned-sha256s",
        verified_build_sums,
        "--post-sign-unsigned-sha256s",
        post_sign_build_sums,
        "--normalization-signer-certificate-sha256",
        "a" * 64,
        "--release-signer-certificate-sha256",
        "b" * 64,
    )
    validation_evidence = (
        "--unsigned-approval",
        unsigned_approval,
        "--original-unsigned-build-sha256s",
        original_build_sums,
        "--verified-unsigned-sha256s",
        verified_build_sums,
        "--post-sign-unsigned-sha256s",
        post_sign_build_sums,
        "--expected-release-signer-sha256",
        "b" * 64,
    )
    write_apk(signed)
    write_apk(normalized)
    write_apk(signer_input, signed_layout=False)
    write_apk(unsigned, signed_layout=False)
    run(
        *PYTHON,
        "scripts/release/compare-apk-payloads.py",
        signed,
        normalized,
        *comparison_evidence,
        "--signer-input-unsigned",
        signer_input,
        "--independent-unsigned",
        unsigned,
        "--comparison-preparation",
        "apksigner-v2-v3-ephemeral-rsa4096",
        "--report",
        report,
    )
    run(
        *PYTHON,
        "scripts/release/validate-independent-report.py",
        report,
        "--signed-apk",
        signed,
        *validation_evidence,
    )

    mutated_report = json.loads(report.read_text(encoding="utf-8"))
    mutated_report["comparedPayloadEntries"] -= 1
    mutated_path = temp / "mutated-report.json"
    write_canonical_json(mutated_path, mutated_report)
    run(
        *PYTHON,
        "scripts/release/validate-independent-report.py",
        mutated_path,
        "--signed-apk",
        signed,
        *validation_evidence,
        should_pass=False,
    )

    different_raw_dir = temp / "different-raw"
    different_raw_dir.mkdir()
    different_raw = different_raw_dir / "clench-0.0.0-unsigned.apk"
    write_apk(different_raw, changed_entry=23, signed_layout=False)
    run(
        *PYTHON,
        "scripts/release/compare-apk-payloads.py",
        signed,
        unsigned,
        *comparison_evidence,
        "--signer-input-unsigned",
        different_raw,
        "--independent-unsigned",
        unsigned,
        "--comparison-preparation",
        "apksigner-v2-v3-ephemeral-rsa4096",
        "--report",
        temp / "raw-mismatch-report.json",
        should_pass=False,
    )

    mutated_preparation = json.loads(report.read_text(encoding="utf-8"))
    mutated_preparation["comparisonPolicy"] = "none"
    mutated_preparation_path = temp / "mutated-preparation-report.json"
    write_canonical_json(mutated_preparation_path, mutated_preparation)
    run(
        *PYTHON,
        "scripts/release/validate-independent-report.py",
        mutated_preparation_path,
        "--signed-apk",
        signed,
        *validation_evidence,
        should_pass=False,
    )

    original_report = json.loads(report.read_text(encoding="utf-8"))
    hostile_report_mutations = {
        "schema": ("schemaVersion", 1),
        "comparison": ("comparison", "FAIL"),
        "signed-name": ("signedApk", "other.apk"),
        "signed-hash": ("signedApkSha256", "0" * 64),
        "signer-input-hash": ("signerInputUnsignedApkSha256", "0" * 64),
        "independent-hash": ("independentUnsignedApkSha256", "1" * 64),
        "raw-identity": ("rawUnsignedByteIdentical", False),
        "manifest": ("payloadManifestSha256", "0" * 64),
        "zip-exclusion": ("excludedZipEntries", ["assets/fixture-000.bin"]),
        "archive-exclusion": ("excludedArchiveRegions", []),
        "normalized-fields": ("normalizedArchiveFields", []),
        "toolchain": ("toolchain", {}),
        "signing-profile": ("signingProfile", {}),
        "signing-block-policy": ("signingBlockPolicy", {}),
        "normalization": ("normalization", {}),
        "build-evidence": ("buildEvidence", {}),
    }
    for label, (field, value) in hostile_report_mutations.items():
        hostile_report = copy.deepcopy(original_report)
        hostile_report[field] = value
        hostile_report_path = temp / f"report-{label}.json"
        write_canonical_json(hostile_report_path, hostile_report)
        run(
            *PYTHON,
            "scripts/release/validate-independent-report.py",
            hostile_report_path,
            "--signed-apk",
            signed,
            *validation_evidence,
            should_pass=False,
        )

    nested_report_mutations = {
        "tool-apksigner": ("toolchain", "apksignerExecutableSha256", "0" * 64),
        "signing-v1": ("signingProfile", "v1", True),
        "normalization-extra-field": (
            "normalization",
            "signerCertificateSha256",
            "b" * 64,
        ),
        "normalization-method": ("normalization", "method", "untrusted"),
        "normalization-v3": ("normalization", "verification", None),
        "release-cert": (None, "releaseSignerCertificateSha256", "c" * 64),
    }
    for label, (parent, field, value) in nested_report_mutations.items():
        hostile_report = copy.deepcopy(original_report)
        if label == "normalization-v3":
            hostile_report["normalization"]["verification"]["v3"] = False
        elif parent is None:
            hostile_report[field] = value
        else:
            hostile_report[parent][field] = value
        hostile_report_path = temp / f"report-nested-{label}.json"
        write_canonical_json(hostile_report_path, hostile_report)
        run(
            *PYTHON,
            "scripts/release/validate-independent-report.py",
            hostile_report_path,
            "--signed-apk",
            signed,
            *validation_evidence,
            should_pass=False,
        )

    unexpected_report = dict(original_report)
    unexpected_report["unexpected"] = True
    write_canonical_json(temp / "report-unexpected.json", unexpected_report)
    run(
        *PYTHON,
        "scripts/release/validate-independent-report.py",
        temp / "report-unexpected.json",
        "--signed-apk",
        signed,
        *validation_evidence,
        should_pass=False,
    )
    missing_report = dict(original_report)
    del missing_report["rawUnsignedByteIdentical"]
    write_canonical_json(temp / "report-missing.json", missing_report)
    run(
        *PYTHON,
        "scripts/release/validate-independent-report.py",
        temp / "report-missing.json",
        "--signed-apk",
        signed,
        *validation_evidence,
        should_pass=False,
    )
    (temp / "report-noncanonical.json").write_text(
        json.dumps(original_report),
        encoding="utf-8",
    )
    run(
        *PYTHON,
        "scripts/release/validate-independent-report.py",
        temp / "report-noncanonical.json",
        "--signed-apk",
        signed,
        *validation_evidence,
        should_pass=False,
    )

    hostile_builders = (
        ("changed-entry", {"changed_entry": 7}, "APK payload comparison failed"),
        ("extra-meta", {"custom_meta": True}, "APK payload comparison failed"),
        (
            "v1-signature",
            {"signatures": True},
            "APK contains forbidden v1 signature metadata",
        ),
        ("duplicate-entry", {"duplicate": True}, "APK contains a duplicate ZIP entry"),
        ("path-traversal", {"traversal": True}, "APK contains an unsafe ZIP path"),
        ("compression", {"stored_entry": 7}, "APK payload comparison failed"),
        (
            "orphan-signature",
            {"orphan_signature": True},
            "APK contains forbidden v1 signature metadata",
        ),
        ("extra-directory", {"extra_directory": True}, "APK payload comparison failed"),
        ("prefix", {"prefix": True}, "APK contains a prefix before the first ZIP entry"),
        (
            "unknown-signing-pair",
            {"signing_block_variant": "unknown"},
            "APK Signing Block contains an unknown pair ID",
        ),
        (
            "duplicate-signing-pair",
            {"signing_block_variant": "duplicate"},
            "APK Signing Block contains a duplicate pair ID",
        ),
        (
            "missing-v3",
            {"signing_block_variant": "missing-v3"},
            "APK Signing Block pair order or required IDs are invalid",
        ),
        (
            "nonzero-padding",
            {"signing_block_variant": "nonzero-padding"},
            "APK Signing Block padding is not all-zero",
        ),
        (
            "oversized-padding",
            {"signing_block_variant": "oversized-padding"},
            "APK Signing Block padding or alignment is noncanonical",
        ),
        (
            "wrong-pair-order",
            {"signing_block_variant": "wrong-order"},
            "APK Signing Block pair order or required IDs are invalid",
        ),
    )
    for label, options, expected_error in hostile_builders:
        hostile = temp / f"hostile-{label}.apk"
        write_apk(hostile, **options)
        run(
            *PYTHON,
            "scripts/release/compare-apk-payloads.py",
            signed,
            hostile,
            *comparison_evidence,
            "--signer-input-unsigned",
            signer_input,
            "--independent-unsigned",
            unsigned,
            "--comparison-preparation",
            "apksigner-v2-v3-ephemeral-rsa4096",
            "--report",
            temp / f"hostile-{label}.json",
            should_pass=False,
            expected_output=expected_error,
        )


def test_sbom_and_provenance(temp: Path) -> None:
    commit = run("git", "rev-parse", "HEAD").stdout.strip()
    sbom_first = temp / "sbom-first.json"
    sbom_second = temp / "sbom-second.json"
    version = version_name()
    for output in (sbom_first, sbom_second):
        run(
            *PYTHON,
            "scripts/release/generate-sbom.py",
            "--commit",
            commit,
            "--output",
            output,
        )
    if sbom_first.read_bytes() != sbom_second.read_bytes():
        raise SystemExit("Deterministic SBOM generations differed")
    run(
        *PYTHON,
        "scripts/release/validate-sbom.py",
        sbom_first,
        "--version",
        version,
        "--commit",
        commit,
    )
    truncated_sbom = json.loads(sbom_first.read_text(encoding="utf-8"))
    truncated_sbom["components"].pop()
    truncated_sbom_path = temp / "sbom-truncated.json"
    truncated_sbom_path.write_text(json.dumps(truncated_sbom), encoding="utf-8")
    run(
        *PYTHON,
        "scripts/release/validate-sbom.py",
        truncated_sbom_path,
        "--version",
        version,
        "--commit",
        commit,
        should_pass=False,
    )

    apk = temp / "provenance-subject.apk"
    write_apk(apk)
    provenance = temp / "provenance.jsonl"
    common = (
        "--apk",
        apk,
        "--sbom",
        sbom_first,
        "--tag",
        f"v{version}",
        "--commit",
        commit,
        "--repository",
        "clenchwallet/clench-wallet",
    )
    run(
        *PYTHON,
        "scripts/release/generate-provenance.py",
        *common,
        "--output",
        provenance,
    )
    run(
        *PYTHON,
        "scripts/release/validate-provenance.py",
        provenance,
        *common,
    )
    for label, flag, value in (
        ("repository-url", "--repository", "https://github.com/clenchwallet/clench-wallet"),
        ("short-commit", "--commit", commit[:12]),
        ("missing-subject", "--apk", temp / "missing.apk"),
    ):
        hostile_common = list(common)
        hostile_common[hostile_common.index(flag) + 1] = value
        run(
            *PYTHON,
            "scripts/release/generate-provenance.py",
            *hostile_common,
            "--output",
            temp / f"provenance-{label}.jsonl",
            should_pass=False,
        )
    truncated_provenance = json.loads(provenance.read_text(encoding="utf-8"))
    truncated_provenance["predicate"]["buildDefinition"]["resolvedDependencies"].pop()
    truncated_provenance_path = temp / "provenance-truncated.jsonl"
    truncated_provenance_path.write_text(
        json.dumps(truncated_provenance),
        encoding="utf-8",
    )
    run(
        *PYTHON,
        "scripts/release/validate-provenance.py",
        truncated_provenance_path,
        *common,
        should_pass=False,
    )


def test_workflow_control_mutations() -> None:
    controls = load_release_controls()
    workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
    controls.verify_release_workflow(workflow)

    strict_semver = (
        '[[ ! "$RELEASE_TAG" =~ '
        '^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.'
        '(0|[1-9][0-9]*)$ ]]'
    )
    permissive_semver = '[[ ! "$RELEASE_TAG" =~ ^v[0-9]+\\.[0-9]+\\.[0-9]+$ ]]'
    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "validate_source",
            strict_semver,
            permissive_semver,
            label="permissive-semver",
        ),
        "permissive-semver",
        "semantic-version leading zeros",
    )

    expect_workflow_failure(
        controls,
        replace_once(
            workflow,
            "  attest_independent_unsigned:\n",
            "  removed_independent_attestor:\n",
            label="missing-attestor-job",
        ),
        "missing-attestor-job",
        "exactly the required isolated jobs",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "validate_source",
            "      contents: read\n",
            "      contents: write\n",
            label="source-gate-write-permission",
        ),
        "source-gate-write-permission",
        "unsafe permissions",
    )

    extra_job = (
        "  unexpected_writer:\n"
        "    runs-on: ubuntu-24.04\n"
        "    permissions:\n"
        "      contents: write\n"
        "    steps:\n"
        "      - name: Unexpected writer\n"
        "        run: echo unsafe\n\n"
        "  publish:\n"
    )
    expect_workflow_failure(
        controls,
        replace_once(
            workflow,
            "  publish:\n",
            extra_job,
            label="unexpected-release-job",
        ),
        "unexpected-release-job",
        "exactly the required isolated jobs",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "attest_independent_unsigned",
            "needs: [validate_source, build_independent_unsigned]",
            "needs: build_independent_unsigned",
            label="attestor-dag",
        ),
        "attestor-dag",
        "unsafe dependency edge",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "build_independent_unsigned",
            "    permissions:\n      contents: read\n",
            "    permissions:\n      contents: read\n      id-token: write\n",
            label="blind-builder-oidc",
        ),
        "blind-builder-oidc",
        "unsafe permissions",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "build_independent_unsigned",
            "          persist-credentials: false\n",
            "          persist-credentials: true\n",
            label="blind-checkout-credentials",
        ),
        "blind-checkout-credentials",
        "must not persist checkout credentials",
    )

    job_level_runner_context = (
        "    runs-on: ubuntu-24.04\n"
        "    env:\n"
        "      GRADLE_USER_HOME: ${{ runner.temp }}/unsafe-job-scope\n"
    )
    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "build_independent_unsigned",
            "    runs-on: ubuntu-24.04\n",
            job_level_runner_context,
            label="job-level-runner-context",
        ),
        "job-level-runner-context",
        "uses runner context before runner assignment",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "build_post_sign_unsigned",
            "          ACTIONS_ID_TOKEN_REQUEST_TOKEN: ''\n",
            "",
            label="blind-oidc-token",
        ),
        "blind-oidc-token",
        "does not clear blind-build credential",
    )

    blind_download = (
        "    steps:\n"
        "      - name: Download attacker-selected expected bytes\n"
        "        uses: actions/download-artifact@"
        "d3f86a106a0bac45b974a628896c90dbdf5c8093\n"
    )
    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "build_post_sign_unsigned",
            "    steps:\n",
            blind_download,
            label="blind-artifact-download",
        ),
        "blind-artifact-download",
        "must not download expected release artifacts",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "attest_post_sign_unsigned",
            "      attestations: write\n",
            "      attestations: read\n",
            label="attestor-permissions",
        ),
        "attestor-permissions",
        "unsafe permissions",
    )

    attestor_checkout = (
        "    steps:\n"
        "      - name: Unsafe source checkout\n"
        "        uses: actions/checkout@"
        "34e114876b0b11c390a56381ad16ebd13914f8d5\n"
    )
    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "attest_independent_unsigned",
            "    steps:\n",
            attestor_checkout,
            label="attestor-source-checkout",
        ),
        "attestor-source-checkout",
        "must not check out or execute release source",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "attest_independent_unsigned",
            "subject-path: ${{ runner.temp }}/independent-unsigned-build/"
            "clench-${{ needs.validate_source.outputs.version }}-independent-unsigned.apk",
            "subject-path: ${{ runner.temp }}/independent-unsigned-build/RELEASE-NOTES.md",
            label="wrong-b-attestation-subject",
        ),
        "wrong-b-attestation-subject",
        "does not attest the exact blind APK subject",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "verify_unsigned",
            "approved_raw_sha256: ${{ steps.raw_compare.outputs.approved_raw_sha256 }}",
            "approved_raw_sha256: ${{ env.APPROVED_RAW_SHA256 }}",
            label="mutable-job-output",
        ),
        "mutable-job-output",
        "does not expose the immutable approved raw digest",
    )

    expect_workflow_failure(
        controls,
        swap_named_steps(
            workflow,
            controls,
            "verify_unsigned",
            "Prove raw reproducibility with core tools before APK parsing",
            "Verify blind-build provenance before approval",
        ),
        "raw-compare-reordered",
        "compare raw APKs, verify B attestation, then parse",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "verify_unsigned",
            "        id: raw_compare\n",
            "        id: parser_output\n",
            label="raw-output-id",
        ),
        "raw-output-id",
        "not the immutable output producer",
    )

    precheck_job = controls.job_blocks(workflow)["sign_release"]
    precheck = controls.named_step_blocks(precheck_job)[
        "Verify the isolated signer input without parsing APK contents"
    ]
    mutated_precheck = replace_once(
        precheck,
        "        run: |\n",
        "        run: |\n          aapt dump badging \"$ORIGINAL_APK\"\n",
        label="signer-precheck-parser",
    )
    mutated_signer_job = replace_once(
        precheck_job,
        precheck,
        mutated_precheck,
        label="signer-precheck-parser-step",
    )
    expect_workflow_failure(
        controls,
        replace_once(
            workflow,
            precheck_job,
            mutated_signer_job,
            label="signer-precheck-parser-job",
        ),
        "signer-precheck-parser",
        "must not parse attacker-controlled APK contents",
    )

    sign_job = controls.job_blocks(workflow)["sign_release"]
    sign_step = controls.named_step_blocks(sign_job)[
        "Sign the prebuilt digest with no source checkout"
    ]
    mutable_digest_step = replace_once(
        sign_step,
        "APPROVED_RAW_SHA256: ${{ needs.verify_unsigned.outputs.approved_raw_sha256 }}",
        "APPROVED_RAW_SHA256: ${{ env.APPROVED_RAW_SHA256 }}",
        label="mutable-sign-digest",
    )
    expect_workflow_failure(
        controls,
        replace_once(
            workflow,
            sign_step,
            mutable_digest_step,
            label="mutable-sign-digest-workflow",
        ),
        "mutable-sign-digest",
        "Signing step is not bound to the immutable approved raw digest",
    )

    for label, old, new, expected in (
        (
            "relative-core-cmp",
            '/usr/bin/cmp "$ORIGINAL_APK" "$INDEPENDENT_APK"',
            'cmp "$ORIGINAL_APK" "$INDEPENDENT_APK"',
            "missing point-of-use control",
        ),
        (
            "mutable-apksigner-path",
            "APKSIGNER=/usr/local/lib/android/sdk/build-tools/35.0.0/apksigner",
            "APKSIGNER=$ANDROID_HOME/build-tools/35.0.0/apksigner",
            "missing point-of-use control",
        ),
        (
            "missing-sign-trap",
            "trap cleanup_signing_material EXIT",
            "# cleanup trap removed",
            "missing point-of-use control",
        ),
    ):
        mutated_step = replace_once(sign_step, old, new, label=label)
        expect_workflow_failure(
            controls,
            replace_once(
                workflow,
                sign_step,
                mutated_step,
                label=f"{label}-workflow",
            ),
            label,
            expected,
        )

    inserted_step = (
        "      - name: Unsafe secret-window command\n"
        "        run: echo unsafe\n\n"
        "      - name: Destroy signing material"
    )
    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "sign_release",
            "      - name: Destroy signing material",
            inserted_step,
            label="secret-window-insertion",
        ),
        "secret-window-insertion",
        "exact ordered step list",
    )

    unnamed_inserted_step = (
        "      - run: echo unsafe\n\n"
        "      - name: Destroy signing material"
    )
    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "sign_release",
            "      - name: Destroy signing material",
            unnamed_inserted_step,
            label="unnamed-secret-window-insertion",
        ),
        "unnamed-secret-window-insertion",
        "unnamed top-level step",
    )

    named_presecret_parser = (
        "      - name: Parse attacker-selected APK before secrets\n"
        "        run: apksigner verify unsafe.apk\n\n"
        "      - name: Require release signing secrets"
    )
    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "sign_release",
            "      - name: Require release signing secrets",
            named_presecret_parser,
            label="separate-presecret-parser-step",
        ),
        "separate-presecret-parser-step",
        "exact ordered step list",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "publish",
            "      - name: Reverify the complete bundle and publish without signing credentials",
            "      - name: Unsafe early publisher\n"
            "        run: gh release create unsafe\n\n"
            "      - name: Reverify the complete bundle and publish without signing credentials",
            label="extra-publish-step",
        ),
        "extra-publish-step",
        "exact ordered step list",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "sign_release",
            "        if: always()\n",
            "        if: success()\n",
            label="conditional-key-destruction",
        ),
        "conditional-key-destruction",
        "destruction is not unconditional",
    )

    expect_workflow_failure(
        controls,
        replace_once_in_job(
            workflow,
            controls,
            "verify_release",
            "needs: [validate_source, verify_unsigned, build_post_sign_unsigned, "
            "attest_post_sign_unsigned, sign_release]",
            "needs: [validate_source, verify_unsigned, build_post_sign_unsigned, "
            "sign_release]",
            label="missing-c-attestor-edge",
        ),
        "missing-c-attestor-edge",
        "unsafe dependency edge",
    )

    release_job = controls.job_blocks(workflow)["verify_release"]
    c_attestation_step = controls.named_step_blocks(release_job)[
        "Verify second blind-build provenance before comparison"
    ]
    wrong_c_attestation_step = replace_once(
        c_attestation_step,
        '"$RUNNER_TEMP/post-sign-unsigned-build/clench-$VERSION-independent-unsigned.apk"',
        '"$RUNNER_TEMP/release-artifacts/clench-$VERSION-release.apk"',
        label="wrong-c-attestation-subject",
    )
    expect_workflow_failure(
        controls,
        replace_once(
            workflow,
            c_attestation_step,
            wrong_c_attestation_step,
            label="wrong-c-attestation-workflow",
        ),
        "wrong-c-attestation-subject",
        "does not fully verify C attestation",
    )

    publish_job = controls.job_blocks(workflow)["publish"]
    publish_step = controls.named_step_blocks(publish_job)[
        "Reverify the complete bundle and publish without signing credentials"
    ]
    evidence_line = "            release-artifacts/UNSIGNED-APPROVAL.txt \\\n"
    if publish_step.count(evidence_line) < 2:
        raise SystemExit("Could not locate publish-time evidence in both required contexts")
    missing_evidence_step = publish_step.replace(evidence_line, "", 1)
    expect_workflow_failure(
        controls,
        replace_once(
            workflow,
            publish_step,
            missing_evidence_step,
            label="publish-evidence-removal",
        ),
        "publish-evidence-removal",
        "does not attest the exact required evidence set",
    )

    verification_boundary = (
        "scripts/release/verify-release-bundle.sh release-artifacts\n"
        '          gh release create "$RELEASE_TAG"'
    )
    mutated_boundary = (
        "scripts/release/verify-release-bundle.sh release-artifacts\n"
        "          printf 'mutable command between verification and publication\\n'\n"
        '          gh release create "$RELEASE_TAG"'
    )
    delayed_publish_step = replace_once(
        publish_step,
        verification_boundary,
        mutated_boundary,
        label="publish-toctou-command",
    )
    expect_workflow_failure(
        controls,
        replace_once(
            workflow,
            publish_step,
            delayed_publish_step,
            label="publish-toctou-workflow",
        ),
        "publish-toctou-command",
        "immediately before release creation",
    )


def main() -> None:
    run(*PYTHON, "scripts/release/verify-release-controls.py")
    test_workflow_control_mutations()
    with tempfile.TemporaryDirectory(prefix="clench-release-tool-test-") as directory:
        temp = Path(directory)
        test_apk_comparison(temp)
        test_sbom_and_provenance(temp)
    print("Release evidence hostile self-tests passed.")


if __name__ == "__main__":
    main()
