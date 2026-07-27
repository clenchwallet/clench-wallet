#!/usr/bin/env python3
"""Generate a deterministic CycloneDX 1.6 SBOM from the locked release runtime."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def app_metadata(build_file: Path) -> tuple[str, str]:
    text = build_file.read_text(encoding="utf-8")
    version_name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    version_code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not version_name or not version_code:
        raise SystemExit("Could not read versionName/versionCode from app build file")
    return version_name.group(1), version_code.group(1)


def maven_purl(group: str, name: str, version: str) -> str:
    return "pkg:maven/{}/{}@{}".format(
        quote(group, safe=".~-"),
        quote(name, safe=".~-"),
        quote(version, safe=".~-+"),
    )


def verified_artifacts(
    metadata: Path,
) -> dict[tuple[str, str, str], list[tuple[str, str]]]:
    root = ET.parse(metadata).getroot()
    result: dict[tuple[str, str, str], list[tuple[str, str]]] = {}
    for component in root.findall(".//{*}component"):
        coordinate = (
            component.attrib.get("group", ""),
            component.attrib.get("name", ""),
            component.attrib.get("version", ""),
        )
        artifacts: list[tuple[str, str]] = []
        for artifact in component.findall("{*}artifact"):
            name = artifact.attrib.get("name", "")
            digests = {
                item.attrib.get("value", "").lower()
                for item in artifact.findall("{*}sha256")
                if item.attrib.get("value")
            }
            if len(digests) > 1:
                raise SystemExit(
                    f"Gradle verification metadata has conflicting SHA-256 values for {name}"
                )
            if digests:
                digest = digests.pop()
                if not re.fullmatch(r"[0-9a-f]{64}", digest):
                    raise SystemExit(f"Invalid Gradle verification SHA-256 for {name}")
                artifacts.append((name, digest))
        if artifacts:
            if coordinate in result:
                raise SystemExit(
                    "Gradle verification metadata contains a duplicate component: "
                    f"{':'.join(coordinate)}"
                )
            result[coordinate] = sorted(artifacts)
    return result


def locked_runtime_components(
    lockfile: Path,
    verification_metadata: Path,
) -> list[dict[str, object]]:
    artifact_inventory = verified_artifacts(verification_metadata)
    components: dict[str, dict[str, object]] = {}
    for raw_line in lockfile.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("empty="):
            continue
        coordinate, separator, configurations = line.partition("=")
        if not separator:
            raise SystemExit(f"Malformed Gradle lock line: {line[:120]}")
        configuration_names = configurations.split(",")
        if "releaseRuntimeClasspath" not in configuration_names:
            continue
        parts = coordinate.split(":")
        if len(parts) != 3 or not all(parts):
            raise SystemExit(f"Unsupported Gradle coordinate in lockfile: {coordinate}")
        group, name, version = parts
        artifacts = artifact_inventory.get((group, name, version))
        if not artifacts:
            raise SystemExit(
                f"Release component has no SHA-256 evidence in Gradle verification metadata: {coordinate}"
            )
        purl = maven_purl(group, name, version)
        components[purl] = {
            "type": "library",
            "bom-ref": purl,
            "group": group,
            "name": name,
            "version": version,
            "scope": "required",
            "purl": purl,
            "properties": [
                {
                    "name": "net.clench.gradle.configuration",
                    "value": "releaseRuntimeClasspath",
                },
                *[
                    {
                        "name": f"net.clench.gradle.artifact.{artifact_name}.sha256",
                        "value": digest,
                    }
                    for artifact_name, digest in artifacts
                ],
            ],
        }
    return [components[key] for key in sorted(components)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lockfile", default="app/gradle.lockfile")
    parser.add_argument("--build-file", default="app/build.gradle.kts")
    parser.add_argument(
        "--verification-metadata",
        default="gradle/verification-metadata.xml",
    )
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    lockfile = Path(args.lockfile)
    build_file = Path(args.build_file)
    verification_metadata = Path(args.verification_metadata)
    output = Path(args.output)
    version_name, version_code = app_metadata(build_file)
    components = locked_runtime_components(lockfile, verification_metadata)
    if not components:
        raise SystemExit("No releaseRuntimeClasspath components found in Gradle lockfile")

    application_ref = f"pkg:generic/net.clench.wallet@{quote(version_name, safe='.')}"
    identity = f"clenchwallet/clench-wallet@{args.commit}:{version_name}:{sha256(lockfile)}"
    serial = uuid.uuid5(uuid.NAMESPACE_URL, identity)
    document = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": f"urn:uuid:{serial}",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "bom-ref": application_ref,
                "group": "net.clench",
                "name": "wallet",
                "version": version_name,
                "purl": application_ref,
                "properties": [
                    {"name": "net.clench.android.versionCode", "value": version_code},
                    {"name": "net.clench.source.commit", "value": args.commit},
                    {"name": "net.clench.gradle.lockfile.sha256", "value": sha256(lockfile)},
                    {
                        "name": "net.clench.gradle.verification-metadata.sha256",
                        "value": sha256(verification_metadata),
                    },
                ],
            }
        },
        "components": components,
        "dependencies": [
            {
                "ref": application_ref,
                "dependsOn": [component["bom-ref"] for component in components],
            }
        ],
    }

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
