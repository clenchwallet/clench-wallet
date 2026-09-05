#!/usr/bin/env python3
"""Inventory native payloads in checksum-verified locked artifacts; not a native vulnerability scan."""
import argparse
import hashlib
import importlib.util
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("sbom", ROOT / "scripts/release/generate-sbom.py")
SBOM = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SBOM)
MAX_NATIVE_BYTES = 256 * 1024 * 1024
MAX_TOTAL_NATIVE_BYTES = 1024 * 1024 * 1024
MAX_NATIVE_ENTRIES = 256


def native_entries(archive):
    result = []
    total = 0
    with zipfile.ZipFile(archive) as z:
        for item in sorted(z.infolist(), key=lambda i: i.filename):
            if not item.filename.endswith((".so", ".dylib", ".jnilib", ".dll")):
                continue
            if item.file_size > MAX_NATIVE_BYTES:
                raise ValueError("Native library exceeds inventory bound")
            total += item.file_size
            if total > MAX_TOTAL_NATIVE_BYTES or len(result) >= MAX_NATIVE_ENTRIES:
                raise ValueError("Native archive exceeds aggregate inventory bound")
            digest = hashlib.sha256()
            consumed = 0
            with z.open(item) as handle:
                while chunk := handle.read(1024 * 1024):
                    consumed += len(chunk)
                    if consumed > MAX_NATIVE_BYTES: raise ValueError("Native stream exceeds inventory bound")
                    digest.update(chunk)
            result.append({"path": item.filename, "bytes": consumed, "sha256": digest.hexdigest()})
    if len({entry["path"] for entry in result}) != len(result):
        raise ValueError("Duplicate native archive entries")
    return result


def check_baseline(document, baseline):
    """Fail on native-owner/payload drift; a reviewed classification is not a clean advisory scan."""
    records = baseline.get("artifacts")
    if baseline.get("schemaVersion") != 1 or not isinstance(records, list):
        raise ValueError("Invalid native baseline")
    def key(record):
        return record["owner_purl"], record["artifact"]
    expected = {key(record): record for record in records}
    actual = {key(record): record for record in document["artifacts"]}
    if len(expected) != len(records) or set(expected) != set(actual):
        raise ValueError("Native owner inventory changed; review and update the baseline")
    for identity, record in actual.items():
        prior = expected[identity]
        if (record["artifact_sha256"] != prior["artifact_sha256"] or
                record["native_payloads"] != prior["native_payloads"]):
            raise ValueError("Native payload changed; source/advisory review required: " + identity[0])
        review = prior.get("source_review")
        if not isinstance(review, dict) or review.get("status") not in ("PARTIAL", "NOT_REVIEWED"):
            raise ValueError("Native baseline must explicitly preserve incomplete source/advisory coverage")
        record["source_review"] = review
    return document


def inventory(root, resolved_manifest):
    components = SBOM.locked_runtime_components(root / "app/gradle.lockfile", root / "gradle/verification-metadata.xml")
    verified = SBOM.verified_artifacts(root / "gradle/verification-metadata.xml")
    locked = {":".join((c["group"], c["name"], c["version"])): c for c in components}
    resolved = json.loads(resolved_manifest.read_text())
    if resolved.get("schemaVersion") != 1 or set(resolved.get("modules", [])) != set(locked):
        raise ValueError("Resolved module graph does not match the locked release runtime")
    records = resolved.get("artifacts")
    if not isinstance(records, list) or not records:
        raise ValueError("Resolved runtime has no artifact records")
    owners, seen = [], set()
    for record in records:
        coordinate, name = record["coordinate"], record["name"]
        if coordinate not in locked or (coordinate, name) in seen:
            raise ValueError("Unknown or duplicate runtime artifact")
        seen.add((coordinate, name))
        artifact = Path(record["file"])
        if artifact.name != name:
            raise ValueError("Resolved artifact name mismatch")
        expected = dict(verified[tuple(coordinate.split(":"))]).get(name)
        if expected is None or SBOM.sha256(artifact) != expected:
            raise ValueError("Runtime artifact differs from verification metadata: " + name)
        if not name.endswith((".aar", ".jar")):
            raise ValueError("Unrecognized runtime artifact format: " + name)
        entries = native_entries(artifact)
        if entries:
            owners.append({"owner_purl": locked[coordinate]["purl"], "artifact": name, "artifact_sha256": expected,
                           "native_payloads": entries, "source_dependency_assurance": "NOT_REVIEWED"})
    return {"schemaVersion": 1, "coverage": "native payload identity only; upstream source graph and advisory review required",
            "lockfile_sha256": SBOM.sha256(root / "app/gradle.lockfile"),
            "verification_metadata_sha256": SBOM.sha256(root / "gradle/verification-metadata.xml"),
            "artifacts": sorted(owners, key=lambda c: (c["owner_purl"], c["artifact"]))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resolved-artifacts", type=Path, default=ROOT / "build/reports/native-runtime-artifacts.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, help="Fail on changes to the committed native payload inventory")
    args = parser.parse_args()
    try:
        document = inventory(ROOT, args.resolved_artifacts)
        if args.baseline:
            document = check_baseline(document, json.loads(args.baseline.read_text()))
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as exc:
        raise SystemExit(str(exc)) from exc
    args.output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
    print(f"Inventoried {len(document['artifacts'])} native-bearing artifacts. Source/advisory assurance remains NOT_REVIEWED.")


if __name__ == "__main__": main()
