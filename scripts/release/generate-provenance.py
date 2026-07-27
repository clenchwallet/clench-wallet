#!/usr/bin/env python3
"""Generate deterministic, inspectable in-toto/SLSA release provenance evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


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
    values = {}
    for line in properties.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            values[key] = value.replace("\\:", ":")
    distribution = values.get("distributionUrl")
    checksum = values.get("distributionSha256Sum")
    if not distribution or not re.fullmatch(r"[0-9a-f]{64}", checksum or ""):
        raise SystemExit("Gradle wrapper distribution or checksum is not pinned")
    return distribution, checksum


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--sbom", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    apk = Path(args.apk)
    sbom = Path(args.sbom)
    output = Path(args.output)
    version, version_code = version_metadata(Path("app/build.gradle.kts"))
    gradle_distribution, gradle_distribution_sha256 = wrapper_metadata(
        Path("gradle/wrapper/gradle-wrapper.properties")
    )
    if args.tag != f"v{version}":
        raise SystemExit("Release tag does not match app version")

    workflow = Path(".github/workflows/release.yml")
    resolved_files = [
        Path("gradle/wrapper/gradle-wrapper.properties"),
        Path("gradle/wrapper/gradle-wrapper.jar"),
        Path("gradle/verification-metadata.xml"),
        Path("app/gradle.lockfile"),
        Path("settings-gradle.lockfile"),
        Path("app/build.gradle.kts"),
        Path("gradle/libs.versions.toml"),
        workflow,
        Path("scripts/release/generate-sbom.py"),
        Path("scripts/release/validate-sbom.py"),
        Path("scripts/release/generate-provenance.py"),
        Path("scripts/release/validate-provenance.py"),
        Path("scripts/release/verify-sbom-attestation.py"),
        Path("scripts/release/verify-release-bundle.sh"),
        Path("scripts/release/verify-independent-apk.sh"),
        Path("scripts/release/compare-apk-payloads.py"),
        Path("scripts/release/validate-independent-report.py"),
        Path("scripts/release/verify-release-controls.py"),
        Path("scripts/verification/test-release-tools.py"),
    ]
    statement = {
        "_type": "https://in-toto.io/Statement/v1",
        "subject": [
            {"name": apk.name, "digest": {"sha256": sha256(apk)}},
            {"name": sbom.name, "digest": {"sha256": sha256(sbom)}},
        ],
        "predicateType": "https://slsa.dev/provenance/v1",
        "predicate": {
            "buildDefinition": {
                "buildType": (
                    f"https://github.com/{args.repository}/.github/workflows/"
                    f"release.yml@refs/tags/{args.tag}"
                ),
                "externalParameters": {
                    "repository": f"https://github.com/{args.repository}",
                    "tag": args.tag,
                    "commit": args.commit,
                    "versionName": version,
                    "versionCode": version_code,
                    "runnerImage": "ubuntu-24.04",
                    "jdk": "temurin-21",
                    "gradleDistribution": gradle_distribution,
                    "gradleDistributionSha256": gradle_distribution_sha256,
                },
                "internalParameters": {},
                "resolvedDependencies": [
                    {
                        "uri": f"git+https://github.com/{args.repository}@{args.commit}",
                        "digest": {"gitCommit": args.commit},
                    }
                ]
                + [
                    {
                        "uri": f"file:{path.as_posix()}",
                        "digest": {"sha256": sha256(path)},
                    }
                    for path in resolved_files
                ],
            },
            "runDetails": {
                "builder": {
                    "id": (
                        f"https://github.com/{args.repository}/.github/workflows/"
                        f"release.yml@refs/tags/{args.tag}"
                    )
                },
                "metadata": {"invocationId": f"{args.repository}:{args.tag}:{args.commit}"},
            },
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(statement, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
