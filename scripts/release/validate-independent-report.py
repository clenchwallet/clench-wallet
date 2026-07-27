#!/usr/bin/env python3
"""Validate exact published independent APK payload-comparison evidence."""

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


def sha256(path: Path) -> str:
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
        raise SystemExit(f"Signed APK local ZIP header is truncated: {info.filename}")
    fields = struct.unpack("<IHHHHHIIIHH", fixed)
    if fields[0] != 0x04034B50:
        raise SystemExit(f"Signed APK local ZIP header is malformed: {info.filename}")
    name_length, extra_length = fields[-2:]
    variable = raw_apk.read(name_length + extra_length)
    if len(variable) != name_length + extra_length:
        raise SystemExit(f"Signed APK local ZIP metadata is truncated: {info.filename}")
    compressed_digest = hashlib.sha256()
    remaining = info.compress_size
    while remaining:
        chunk = raw_apk.read(min(remaining, 1024 * 1024))
        if not chunk:
            raise SystemExit(f"Signed APK compressed payload is truncated: {info.filename}")
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
                "Signed APK contains case-conflicting META-INF entries: "
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


def signed_payload_identity(path: Path) -> tuple[int, str]:
    entries: dict[str, dict[str, object]] = {}
    seen_names: set[str] = set()
    total = 0
    with path.open("rb") as raw_apk, zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ZIP_ENTRIES:
            raise SystemExit("Signed APK contains too many ZIP entries")
        excluded_signatures = v1_signature_entries(infos)
        for info in infos:
            name = info.filename
            pure = PurePosixPath(name)
            if pure.is_absolute() or ".." in pure.parts:
                raise SystemExit(f"Signed APK contains an unsafe ZIP path: {name}")
            if name in seen_names:
                raise SystemExit(f"Signed APK contains a duplicate ZIP entry: {name}")
            seen_names.add(name)
            if info.is_dir() or name in excluded_signatures:
                continue
            total += info.file_size
            if total > MAX_TOTAL_UNCOMPRESSED:
                raise SystemExit("Signed APK payload exceeds the safety limit")
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
    manifest = json.dumps(
        {
            "archiveCommentSha256": archive_comment_sha256,
            "entries": [[name, entries[name]] for name in sorted(entries)],
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return len(entries), hashlib.sha256(manifest).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report")
    parser.add_argument("--signed-apk", required=True)
    args = parser.parse_args()

    report_path = Path(args.report)
    raw = report_path.read_text(encoding="utf-8")
    report = json.loads(raw)
    signed = Path(args.signed_apk)
    entry_count, payload_manifest = signed_payload_identity(signed)
    expected_keys = {
        "comparison",
        "signedApk",
        "signedApkSha256",
        "independentUnsignedApk",
        "independentUnsignedApkSha256",
        "comparedPayloadEntries",
        "payloadManifestSha256",
        "excludedEntries",
    }
    if set(report) != expected_keys:
        raise SystemExit("Independent APK report fields are incomplete or unexpected")
    if report.get("comparison") != "PASS":
        raise SystemExit("Independent APK report does not record a pass")
    if report.get("signedApk") != signed.name:
        raise SystemExit("Independent APK report names a different signed APK")
    if report.get("signedApkSha256") != sha256(signed):
        raise SystemExit("Independent APK report digest does not match the signed APK")
    unsigned_name = report.get("independentUnsignedApk")
    if not isinstance(unsigned_name, str) or Path(unsigned_name).name != unsigned_name:
        raise SystemExit("Independent APK report has an unsafe unsigned APK name")
    if report.get("comparedPayloadEntries") != entry_count or entry_count < 100:
        raise SystemExit("Independent APK report entry count does not match the signed APK")
    if report.get("payloadManifestSha256") != payload_manifest:
        raise SystemExit("Independent APK report payload manifest does not match the signed APK")
    if not re.fullmatch(
        r"[0-9a-f]{64}",
        str(report.get("independentUnsignedApkSha256", "")),
    ):
        raise SystemExit("Independent APK report has an invalid unsigned APK SHA-256")
    if report.get("excludedEntries") != "APK v1 META-INF signature records only":
        raise SystemExit("Independent APK report used an unexpected exclusion policy")
    canonical = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if raw != canonical:
        raise SystemExit("Independent APK report is not canonically serialized")
    print(
        "Validated exact independent APK payload-comparison report for "
        f"{entry_count} entries."
    )


if __name__ == "__main__":
    main()
