#!/usr/bin/env python3
"""Validate an exact deterministic Clench CycloneDX 1.6 SBOM."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote


SECRET_LABEL_PATTERN = re.compile(
    r"(?i)(?:xprv|tprv|storePassword|keyPassword|RELEASE_KEYSTORE_BASE64)"
)
PRIVATE_VALUE_PATTERN = re.compile(
    r"(?:[KL][1-9A-HJ-NP-Za-km-z]{51}|[59c][1-9A-HJ-NP-Za-km-z]{50,51})"
)
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")


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


def app_metadata(build_file: Path) -> tuple[str, str]:
    text = build_file.read_text(encoding="utf-8")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not version or not code:
        raise SystemExit("Could not read versionName/versionCode")
    return version.group(1), code.group(1)


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
    inventory: dict[tuple[str, str, str], list[tuple[str, str]]] = {}
    for component in root.findall(".//{*}component"):
        coordinate = (
            component.attrib.get("group", ""),
            component.attrib.get("name", ""),
            component.attrib.get("version", ""),
        )
        artifacts: list[tuple[str, str]] = []
        for artifact in component.findall("{*}artifact"):
            artifact_name = artifact.attrib.get("name", "")
            hashes = {
                node.attrib.get("value", "").lower()
                for node in artifact.findall("{*}sha256")
                if node.attrib.get("value")
            }
            if len(hashes) > 1:
                raise SystemExit(
                    f"Conflicting verified SHA-256 values for {artifact_name}"
                )
            if hashes:
                digest = hashes.pop()
                if not re.fullmatch(r"[0-9a-f]{64}", digest):
                    raise SystemExit(
                        f"Invalid verified SHA-256 for {artifact_name}"
                    )
                artifacts.append((artifact_name, digest))
        if artifacts:
            if coordinate in inventory:
                raise SystemExit(
                    "Gradle verification metadata contains a duplicate component: "
                    f"{':'.join(coordinate)}"
                )
            inventory[coordinate] = sorted(artifacts)
    return inventory


def expected_components(
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
        if "releaseRuntimeClasspath" not in configurations.split(","):
            continue
        parts = coordinate.split(":")
        if len(parts) != 3 or not all(parts):
            raise SystemExit(f"Unsupported Gradle coordinate: {coordinate}")
        group, name, version = parts
        artifacts = artifact_inventory.get((group, name, version))
        if not artifacts:
            raise SystemExit(
                "Locked release component has no verified artifact SHA-256: "
                f"{coordinate}"
            )
        purl = maven_purl(group, name, version)
        if purl in components:
            raise SystemExit(f"Duplicate locked release component: {coordinate}")
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
                        "name": (
                            f"net.clench.gradle.artifact.{artifact_name}.sha256"
                        ),
                        "value": digest,
                    }
                    for artifact_name, digest in artifacts
                ],
            ],
        }
    return [components[key] for key in sorted(components)]


def expected_document(commit: str) -> dict[str, object]:
    lockfile = Path("app/gradle.lockfile")
    build_file = Path("app/build.gradle.kts")
    verification_metadata = Path("gradle/verification-metadata.xml")
    for path in (lockfile, build_file, verification_metadata):
        if not path.is_file():
            raise SystemExit(f"Required SBOM input is missing: {path}")
    version_name, version_code = app_metadata(build_file)
    components = expected_components(lockfile, verification_metadata)
    if not components:
        raise SystemExit("No locked releaseRuntimeClasspath components found")
    application_ref = f"pkg:generic/net.clench.wallet@{quote(version_name, safe='.')}"
    identity = (
        f"clenchwallet/clench-wallet@{commit}:{version_name}:{sha256(lockfile)}"
    )
    serial = uuid.uuid5(uuid.NAMESPACE_URL, identity)
    return {
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
                    {
                        "name": "net.clench.android.versionCode",
                        "value": version_code,
                    },
                    {"name": "net.clench.source.commit", "value": commit},
                    {
                        "name": "net.clench.gradle.lockfile.sha256",
                        "value": sha256(lockfile),
                    },
                    {
                        "name": (
                            "net.clench.gradle.verification-metadata.sha256"
                        ),
                        "value": sha256(verification_metadata),
                    },
                ],
            }
        },
        "components": components,
        "dependencies": [
            {
                "ref": application_ref,
                "dependsOn": [
                    component["bom-ref"] for component in components
                ],
            }
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sbom")
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()

    if not COMMIT_PATTERN.fullmatch(args.commit):
        raise SystemExit("SBOM commit must be a full lowercase Git SHA-1")
    raw = Path(args.sbom).read_text(encoding="utf-8")
    document = json.loads(raw)
    values = list(string_values(document))
    if any(SECRET_LABEL_PATTERN.search(value) for value in values) or any(
        PRIVATE_VALUE_PATTERN.fullmatch(value) for value in values
    ):
        raise SystemExit("SBOM contains key-shaped or signing-secret metadata")

    expected = expected_document(args.commit)
    expected_version = expected["metadata"]["component"]["version"]  # type: ignore[index]
    if args.version != expected_version:
        raise SystemExit("Requested SBOM version does not match app version")
    if document != expected:
        raise SystemExit(
            "SBOM does not exactly match the app identity, lockfile, and "
            "Gradle verification metadata"
        )
    canonical = (
        json.dumps(expected, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    )
    if raw != canonical:
        raise SystemExit("SBOM is not deterministically serialized")

    components = expected["components"]
    print(
        f"Validated exact CycloneDX SBOM with {len(components)} "
        "locked release runtime components."
    )


if __name__ == "__main__":
    main()
