#!/usr/bin/env python3
"""Validate exact, inspectable Clench in-toto/SLSA provenance evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


SECRET_LABEL_PATTERN = re.compile(
    r"(?i)(?:xprv|tprv|storePassword|keyPassword|RELEASE_KEYSTORE_BASE64)"
)
PRIVATE_VALUE_PATTERN = re.compile(
    r"(?:[KL][1-9A-HJ-NP-Za-km-z]{51}|[59c][1-9A-HJ-NP-Za-km-z]{50,51})"
)
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
REPOSITORY_PATTERN = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
RESOLVED_FILES = (
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/verification-metadata.xml",
    "app/gradle.lockfile",
    "settings-gradle.lockfile",
    "app/build.gradle.kts",
    "gradle/libs.versions.toml",
    ".github/workflows/release.yml",
    ".github/release-signers.allowed",
    "scripts/release/generate-sbom.py",
    "scripts/release/validate-sbom.py",
    "scripts/release/check-osv.py",
    "scripts/release/osv-allowlist.json",
    "scripts/release/generate-provenance.py",
    "scripts/release/validate-provenance.py",
    "scripts/release/verify-sbom-attestation.py",
    "scripts/release/verify-release-bundle.sh",
    "scripts/release/verify-independent-apk.sh",
    "scripts/release/compare-apk-payloads.py",
    "scripts/release/validate-independent-report.py",
    "scripts/release/verify-release-controls.py",
    "scripts/verification/test-release-tools.py",
    "scripts/verification/test-osv-check.py",
)


def string_values(value: object):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, item in value.items():
            yield str(key)
            yield from string_values(item)
    elif isinstance(value, list):
        for item in value:
            yield from string_values(item)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def version_metadata(build_file: Path) -> tuple[str, str]:
    text = build_file.read_text(encoding="utf-8")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not version or not code:
        raise SystemExit("Could not read release version metadata")
    return version.group(1), code.group(1)


def wrapper_metadata(properties: Path) -> tuple[str, str]:
    values: dict[str, str] = {}
    for line in properties.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            values[key] = value.replace("\\:", ":")
    distribution = values.get("distributionUrl", "")
    checksum = values.get("distributionSha256Sum", "")
    if not distribution.startswith("https://services.gradle.org/distributions/"):
        raise SystemExit("Gradle wrapper distribution is not official")
    if not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise SystemExit("Gradle wrapper distribution SHA-256 is not pinned")
    return distribution, checksum


def expected_statement(
    apk: Path,
    sbom: Path,
    tag: str,
    commit: str,
    repository: str,
) -> dict[str, object]:
    version, version_code = version_metadata(Path("app/build.gradle.kts"))
    if tag != f"v{version}":
        raise SystemExit("Release tag does not match app version")
    distribution, distribution_sha256 = wrapper_metadata(
        Path("gradle/wrapper/gradle-wrapper.properties")
    )
    workflow_id = (
        f"https://github.com/{repository}/.github/workflows/"
        "release.yml@refs/heads/master"
    )
    dependencies = [
        {
            "uri": f"git+https://github.com/{repository}@{commit}",
            "digest": {"gitCommit": commit},
        }
    ]
    for relative_path in RESOLVED_FILES:
        path = Path(relative_path)
        if not path.is_file():
            raise SystemExit(f"Required provenance input is missing: {relative_path}")
        dependencies.append(
            {
                "uri": f"file:{relative_path}",
                "digest": {"sha256": sha256(path)},
            }
        )
    return {
        "_type": "https://in-toto.io/Statement/v1",
        "subject": [
            {"name": apk.name, "digest": {"sha256": sha256(apk)}},
            {"name": sbom.name, "digest": {"sha256": sha256(sbom)}},
        ],
        "predicateType": "https://slsa.dev/provenance/v1",
        "predicate": {
            "buildDefinition": {
                "buildType": workflow_id,
                "externalParameters": {
                    "repository": f"https://github.com/{repository}",
                    "tag": tag,
                    "commit": commit,
                    "versionName": version,
                    "versionCode": version_code,
                    "runnerImage": "ubuntu-24.04",
                    "jdk": "temurin-21",
                    "gradleDistribution": distribution,
                    "gradleDistributionSha256": distribution_sha256,
                },
                "internalParameters": {},
                "resolvedDependencies": dependencies,
            },
            "runDetails": {
                "builder": {"id": workflow_id},
                "metadata": {
                    "invocationId": f"{repository}:{tag}:{commit}",
                },
            },
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("provenance")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--sbom", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()

    if not COMMIT_PATTERN.fullmatch(args.commit):
        raise SystemExit("Release commit must be a full lowercase Git SHA-1")
    if not REPOSITORY_PATTERN.fullmatch(args.repository):
        raise SystemExit("Repository must be an owner/name identifier")

    provenance = Path(args.provenance)
    apk = Path(args.apk)
    sbom = Path(args.sbom)
    if not apk.is_file() or not sbom.is_file():
        raise SystemExit("Provenance subject APK or SBOM is missing")
    raw = provenance.read_text(encoding="utf-8")
    statement = json.loads(raw)
    values = list(string_values(statement))
    if any(SECRET_LABEL_PATTERN.search(value) for value in values) or any(
        PRIVATE_VALUE_PATTERN.fullmatch(value) for value in values
    ):
        raise SystemExit("Provenance contains signing-secret metadata")

    expected = expected_statement(
        apk,
        sbom,
        args.tag,
        args.commit,
        args.repository,
    )
    if statement != expected:
        raise SystemExit(
            "Provenance does not exactly match the release subjects, source, "
            "environment, workflow, or resolved input hashes"
        )
    canonical = json.dumps(expected, sort_keys=True, separators=(",", ":")) + "\n"
    if raw != canonical:
        raise SystemExit("Provenance is not canonically serialized")

    print(
        "Validated exact inspectable in-toto/SLSA provenance with "
        f"{len(RESOLVED_FILES) + 1} resolved inputs."
    )


if __name__ == "__main__":
    main()
