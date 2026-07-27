#!/usr/bin/env python3
"""Hostile self-tests for Clench release-evidence tooling."""

from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
import warnings
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PYTHON = [sys.executable, "-B"]


def run(*arguments: object, should_pass: bool = True) -> subprocess.CompletedProcess[str]:
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
    return result


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
        if duplicate:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr("assets/fixture-000.bin", b"duplicate")
        if traversal:
            archive.writestr("../escaped", b"unsafe")


def version_name() -> str:
    text = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not match:
        raise SystemExit("Could not read app version for release-tool self-test")
    return match.group(1)


def test_apk_comparison(temp: Path) -> None:
    signed = temp / "signed.apk"
    unsigned = temp / "unsigned.apk"
    report = temp / "report.json"
    write_apk(signed, signatures=True)
    write_apk(unsigned)
    run(
        *PYTHON,
        "scripts/release/compare-apk-payloads.py",
        signed,
        unsigned,
        "--report",
        report,
    )
    run(
        *PYTHON,
        "scripts/release/validate-independent-report.py",
        report,
        "--signed-apk",
        signed,
    )

    mutated_report = json.loads(report.read_text(encoding="utf-8"))
    mutated_report["comparedPayloadEntries"] -= 1
    mutated_path = temp / "mutated-report.json"
    mutated_path.write_text(json.dumps(mutated_report), encoding="utf-8")
    run(
        *PYTHON,
        "scripts/release/validate-independent-report.py",
        mutated_path,
        "--signed-apk",
        signed,
        should_pass=False,
    )

    hostile_builders = (
        {"changed_entry": 7},
        {"custom_meta": True},
        {"duplicate": True},
        {"traversal": True},
        {"stored_entry": 7},
        {"orphan_signature": True},
    )
    for index, options in enumerate(hostile_builders):
        hostile = temp / f"hostile-{index}.apk"
        write_apk(hostile, **options)
        run(
            *PYTHON,
            "scripts/release/compare-apk-payloads.py",
            signed,
            hostile,
            "--report",
            temp / f"hostile-{index}.json",
            should_pass=False,
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


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="clench-release-tool-test-") as directory:
        temp = Path(directory)
        test_apk_comparison(temp)
        test_sbom_and_provenance(temp)
    print("Release evidence hostile self-tests passed.")


if __name__ == "__main__":
    main()
