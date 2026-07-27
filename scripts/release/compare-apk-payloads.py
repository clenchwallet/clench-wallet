#!/usr/bin/env python3
"""Compare signed and independently rebuilt APK payload entries."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path, PurePosixPath


SIGNATURE_FILE = re.compile(r"^META-INF/([^/]+)\.(SF|RSA|DSA|EC)$", re.I)
MAX_TOTAL_UNCOMPRESSED = 2 * 1024 * 1024 * 1024
MAX_ZIP_ENTRIES = 100_000


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def raw_entry_hashes(
    raw_apk,
    info: zipfile.ZipInfo,
) -> tuple[str, str]:
    raw_apk.seek(info.header_offset)
    fixed = raw_apk.read(30)
    if len(fixed) != 30:
        raise SystemExit(f"APK local ZIP header is truncated: {info.filename}")
    fields = struct.unpack("<IHHHHHIIIHH", fixed)
    if fields[0] != 0x04034B50:
        raise SystemExit(f"APK local ZIP header is malformed: {info.filename}")
    name_length, extra_length = fields[-2:]
    variable = raw_apk.read(name_length + extra_length)
    if len(variable) != name_length + extra_length:
        raise SystemExit(f"APK local ZIP metadata is truncated: {info.filename}")
    compressed_digest = hashlib.sha256()
    remaining = info.compress_size
    while remaining:
        chunk = raw_apk.read(min(remaining, 1024 * 1024))
        if not chunk:
            raise SystemExit(f"APK compressed payload is truncated: {info.filename}")
        compressed_digest.update(chunk)
        remaining -= len(chunk)
    return (
        hashlib.sha256(fixed + variable).hexdigest(),
        compressed_digest.hexdigest(),
    )


def v1_signature_entries(infos: list[zipfile.ZipInfo]) -> set[str]:
    by_upper: dict[str, str] = {}
    for info in infos:
        upper = info.filename.upper()
        if upper.startswith("META-INF/") and upper in by_upper:
            raise SystemExit(
                "APK contains case-conflicting META-INF entries: "
                f"{by_upper[upper]} and {info.filename}"
            )
        by_upper[upper] = info.filename
    excluded: set[str] = set()
    signer_pair_found = False
    for upper, original in by_upper.items():
        match = SIGNATURE_FILE.fullmatch(upper)
        if not match or match.group(2) != "SF":
            continue
        base = match.group(1)
        blocks = [
            by_upper[candidate]
            for extension in ("RSA", "DSA", "EC")
            if (candidate := f"META-INF/{base}.{extension}") in by_upper
        ]
        if blocks:
            signer_pair_found = True
            excluded.add(original)
            excluded.update(blocks)
    manifest = by_upper.get("META-INF/MANIFEST.MF")
    if signer_pair_found and manifest:
        excluded.add(manifest)
    return excluded


def inventory(path: Path) -> tuple[dict[str, dict[str, object]], str]:
    entries: dict[str, dict[str, object]] = {}
    seen_names: set[str] = set()
    total = 0
    with path.open("rb") as raw_apk, zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ZIP_ENTRIES:
            raise SystemExit("APK contains an excessive number of ZIP entries")
        excluded_signatures = v1_signature_entries(infos)
        for info in infos:
            name = info.filename
            pure = PurePosixPath(name)
            if pure.is_absolute() or ".." in pure.parts:
                raise SystemExit(f"APK contains an unsafe ZIP path: {name}")
            if name in seen_names:
                raise SystemExit(f"APK contains a duplicate ZIP entry: {name}")
            seen_names.add(name)
            if info.is_dir() or name in excluded_signatures:
                continue
            total += info.file_size
            if total > MAX_TOTAL_UNCOMPRESSED:
                raise SystemExit("APK uncompressed payload exceeds verification safety limit")
            digest = hashlib.sha256()
            with archive.open(info) as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
            local_header_sha256, compressed_sha256 = raw_entry_hashes(
                raw_apk,
                info,
            )
            entries[name] = {
                "sha256": digest.hexdigest(),
                "size": info.file_size,
                "compressedSize": info.compress_size,
                "compressedSha256": compressed_sha256,
                "compression": info.compress_type,
                "payloadOrder": len(entries),
                "timestamp": list(info.date_time),
                "flags": info.flag_bits,
                "createSystem": info.create_system,
                "createVersion": info.create_version,
                "extractVersion": info.extract_version,
                "internalAttributes": info.internal_attr,
                "externalAttributes": info.external_attr,
                "extraSha256": hashlib.sha256(info.extra).hexdigest(),
                "commentSha256": hashlib.sha256(info.comment).hexdigest(),
                "localHeaderSha256": local_header_sha256,
            }
        archive_comment_sha256 = hashlib.sha256(archive.comment).hexdigest()
    return entries, archive_comment_sha256


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("signed_apk")
    parser.add_argument("independent_apk")
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    signed = Path(args.signed_apk)
    independent = Path(args.independent_apk)
    report_path = Path(args.report)
    signed_entries, signed_comment = inventory(signed)
    independent_entries, independent_comment = inventory(independent)

    missing = sorted(set(signed_entries) - set(independent_entries))
    unexpected = sorted(set(independent_entries) - set(signed_entries))
    changed = sorted(
        name
        for name in set(signed_entries) & set(independent_entries)
        if signed_entries[name] != independent_entries[name]
    )
    if missing or unexpected or changed or signed_comment != independent_comment:
        summary = {
            "missingFromIndependent": missing[:20],
            "unexpectedInIndependent": unexpected[:20],
            "changedEntries": changed[:20],
            "archiveCommentChanged": signed_comment != independent_comment,
        }
        raise SystemExit("APK payload comparison failed: " + json.dumps(summary, sort_keys=True))

    manifest = json.dumps(
        {
            "archiveCommentSha256": signed_comment,
            "entries": [
                [name, signed_entries[name]]
                for name in sorted(signed_entries)
            ],
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    report = {
        "comparison": "PASS",
        "signedApk": signed.name,
        "signedApkSha256": file_sha256(signed),
        "independentUnsignedApk": independent.name,
        "independentUnsignedApkSha256": file_sha256(independent),
        "comparedPayloadEntries": len(signed_entries),
        "payloadManifestSha256": hashlib.sha256(manifest).hexdigest(),
        "excludedEntries": "APK v1 META-INF signature records only",
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"Independent APK payload verification passed for "
        f"{len(signed_entries)} ZIP entries."
    )


if __name__ == "__main__":
    main()
