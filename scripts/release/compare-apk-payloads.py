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
APK_SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"
APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871A
APK_SIGNATURE_SCHEME_V3_BLOCK_ID = 0xF05368C0
VERITY_PADDING_BLOCK_ID = 0x42726577
EXPECTED_SIGNING_BLOCK_PAIR_ORDER = (
    APK_SIGNATURE_SCHEME_V2_BLOCK_ID,
    APK_SIGNATURE_SCHEME_V3_BLOCK_ID,
    VERITY_PADDING_BLOCK_ID,
)
REPORT_SCHEMA_VERSION = 2
COMPARISON_POLICY = "clench-apksigner-normalized-payload-v1"
APKSIGNER_BUILD_TOOLS_VERSION = "35.0.0"
APKSIGNER_EXECUTABLE_SHA256 = "b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0"
APKSIGNER_JAR_SHA256 = "00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7"
AAPT_SHA256 = "393c4c87f675f9555c50921fb53be1f3893cacd29f2270c4863e2e3f99583c39"
SIGNING_PROFILE = {
    "alignmentPreserved": False,
    "libPageAlignment": 16_384,
    "minSdkVersion": 26,
    "v1": False,
    "v2": True,
    "v3": True,
    "v4": False,
    "verity": False,
}
VERIFICATION_PROFILE = {
    "idsigPresent": False,
    "signerCount": 1,
    "sourceStamp": False,
    "v1": False,
    "v1SignatureEntriesPresent": False,
    "v2": True,
    "v3": True,
    "v3_1": False,
    "v4": False,
}
SIGNING_BLOCK_POLICY = {
    "alignmentBytes": 4096,
    "pairOrder": ["0x7109871a", "0xf05368c0", "0x42726577"],
    "paddingLengthRange": [1, 4095],
    "paddingValue": "all-zero",
    "unknownOrDuplicatePairsAllowed": False,
    "v2AndV3ValuesSignerDependent": True,
}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def raw_entry_hashes(
    raw_apk,
    info: zipfile.ZipInfo,
    record_end: int,
) -> tuple[str, str, str, int]:
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
    payload_end = raw_apk.tell()
    if payload_end > record_end:
        raise SystemExit(f"APK ZIP record overlaps the next region: {info.filename}")
    raw_apk.seek(info.header_offset)
    record_digest = hashlib.sha256()
    remaining = record_end - info.header_offset
    while remaining:
        chunk = raw_apk.read(min(remaining, 1024 * 1024))
        if not chunk:
            raise SystemExit(f"APK ZIP record is truncated: {info.filename}")
        record_digest.update(chunk)
        remaining -= len(chunk)
    return (
        hashlib.sha256(fixed + variable).hexdigest(),
        compressed_digest.hexdigest(),
        record_digest.hexdigest(),
        record_end - info.header_offset,
    )


def hash_region(handle, offset: int, size: int) -> str:
    handle.seek(offset)
    digest = hashlib.sha256()
    remaining = size
    while remaining:
        chunk = handle.read(min(remaining, 1024 * 1024))
        if not chunk:
            raise SystemExit("APK ZIP region is truncated")
        digest.update(chunk)
        remaining -= len(chunk)
    return digest.hexdigest()


def parse_signing_block_pairs(
    handle,
    signing_block_start: int,
    central_offset: int,
) -> tuple[int, ...]:
    pair_ids: list[int] = []
    seen_ids: set[int] = set()
    padding_size = 0
    position = signing_block_start + 8
    pairs_end = central_offset - 24
    while position < pairs_end:
        if pairs_end - position < 12:
            raise SystemExit("APK Signing Block pair header is truncated")
        handle.seek(position)
        pair_size = struct.unpack("<Q", handle.read(8))[0]
        if pair_size < 5 or pair_size > pairs_end - position - 8:
            raise SystemExit("APK Signing Block pair has invalid bounds")
        pair_id = struct.unpack("<I", handle.read(4))[0]
        if pair_id in seen_ids:
            raise SystemExit("APK Signing Block contains a duplicate pair ID")
        if pair_id not in EXPECTED_SIGNING_BLOCK_PAIR_ORDER:
            raise SystemExit(
                f"APK Signing Block contains an unknown pair ID: 0x{pair_id:08x}"
            )
        value_size = pair_size - 4
        if value_size <= 0:
            raise SystemExit("APK Signing Block pair has an empty value")
        if pair_id == VERITY_PADDING_BLOCK_ID:
            padding_size = value_size
            remaining = value_size
            while remaining:
                chunk = handle.read(min(remaining, 1024 * 1024))
                if not chunk or any(chunk):
                    raise SystemExit("APK Signing Block padding is not all-zero")
                remaining -= len(chunk)
        seen_ids.add(pair_id)
        pair_ids.append(pair_id)
        position += 8 + pair_size
    if position != pairs_end:
        raise SystemExit("APK Signing Block pairs do not end at the footer")
    if tuple(pair_ids) != EXPECTED_SIGNING_BLOCK_PAIR_ORDER:
        raise SystemExit("APK Signing Block pair order or required IDs are invalid")
    block_size = central_offset - signing_block_start
    if (
        signing_block_start % 4096 != 0
        or central_offset % 4096 != 0
        or block_size % 4096 != 0
        or not 1 <= padding_size <= 4095
    ):
        raise SystemExit("APK Signing Block padding or alignment is noncanonical")
    return tuple(pair_ids)


def zip_layout(path: Path, expected_entries: int) -> tuple[int, str, str]:
    size = path.stat().st_size
    if size < 46:
        raise SystemExit("APK is too small to contain a signed ZIP archive")
    with path.open("rb") as handle:
        tail_size = min(size, 65_557)
        handle.seek(size - tail_size)
        tail = handle.read(tail_size)
        marker = tail.rfind(b"PK\x05\x06")
        if marker < 0 or marker + 22 > len(tail):
            raise SystemExit("APK ZIP end-of-central-directory record is missing")
        eocd_offset = size - tail_size + marker
        fields = struct.unpack("<IHHHHIIH", tail[marker : marker + 22])
        _, disk, central_disk, disk_entries, total_entries, central_size, central_offset, comment_size = fields
        if disk != 0 or central_disk != 0 or disk_entries != total_entries:
            raise SystemExit("Multi-disk APK ZIP archives are forbidden")
        if total_entries != expected_entries:
            raise SystemExit("APK ZIP entry count disagrees with the central directory")
        if eocd_offset + 22 + comment_size != size:
            raise SystemExit("APK ZIP end record or comment length is malformed")
        if central_offset + central_size != eocd_offset:
            raise SystemExit("APK ZIP central-directory bounds are malformed")
        if central_offset < 24:
            raise SystemExit("APK Signing Block is missing")
        handle.seek(central_offset - 24)
        footer = handle.read(24)
        block_size = struct.unpack("<Q", footer[:8])[0]
        if footer[8:] != APK_SIGNING_BLOCK_MAGIC or block_size < 24:
            raise SystemExit("APK Signing Block footer is malformed")
        signing_block_start = central_offset - (block_size + 8)
        if signing_block_start < 8:
            raise SystemExit("APK Signing Block bounds are malformed")
        handle.seek(signing_block_start)
        if struct.unpack("<Q", handle.read(8))[0] != block_size:
            raise SystemExit("APK Signing Block size fields disagree")
        parse_signing_block_pairs(handle, signing_block_start, central_offset)
        central_sha256 = hash_region(handle, central_offset, central_size)
        archive_comment_sha256 = hashlib.sha256(
            tail[marker + 22 : marker + 22 + comment_size]
        ).hexdigest()
    return signing_block_start, central_sha256, archive_comment_sha256


def reject_v1_signature_entries(infos: list[zipfile.ZipInfo]) -> None:
    by_upper: dict[str, str] = {}
    for info in infos:
        upper = info.filename.upper()
        if upper.startswith("META-INF/") and upper in by_upper:
            raise SystemExit(
                "APK contains case-conflicting META-INF entries: "
                f"{by_upper[upper]} and {info.filename}"
            )
        by_upper[upper] = info.filename
    for upper, original in by_upper.items():
        if upper == "META-INF/MANIFEST.MF" or SIGNATURE_FILE.fullmatch(upper):
            raise SystemExit(
                "APK contains forbidden v1 signature metadata: " + original
            )


def require_unsigned_apk(path: Path) -> None:
    if Path(str(path) + ".idsig").exists():
        raise SystemExit(f"Unsigned APK has a v4 signature sidecar: {path.name}")
    size = path.stat().st_size
    with path.open("rb") as handle, zipfile.ZipFile(path) as archive:
        reject_v1_signature_entries(archive.infolist())
        tail_size = min(size, 65_557)
        handle.seek(size - tail_size)
        tail = handle.read(tail_size)
        marker = tail.rfind(b"PK\x05\x06")
        if marker < 0 or marker + 22 > len(tail):
            raise SystemExit(f"Unsigned APK has no valid ZIP end record: {path.name}")
        fields = struct.unpack("<IHHHHIIH", tail[marker : marker + 22])
        central_offset = fields[6]
        comment_size = fields[7]
        if size - tail_size + marker + 22 + comment_size != size:
            raise SystemExit(f"Unsigned APK ZIP end record is malformed: {path.name}")
        if central_offset >= 24:
            handle.seek(central_offset - 16)
            if handle.read(16) == APK_SIGNING_BLOCK_MAGIC:
                raise SystemExit(
                    f"Unsigned APK contains an APK Signing Block: {path.name}"
                )


def inventory(path: Path) -> tuple[dict[str, dict[str, object]], str, str]:
    entries: dict[str, dict[str, object]] = {}
    seen_names: set[str] = set()
    total = 0
    with path.open("rb") as raw_apk, zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ZIP_ENTRIES:
            raise SystemExit("APK contains an excessive number of ZIP entries")
        reject_v1_signature_entries(infos)
        signing_block_start, central_sha256, archive_comment_sha256 = zip_layout(
            path,
            len(infos),
        )
        local_order = sorted(infos, key=lambda candidate: candidate.header_offset)
        if not local_order or local_order[0].header_offset != 0:
            raise SystemExit("APK contains a prefix before the first ZIP entry")
        if len({info.header_offset for info in local_order}) != len(local_order):
            raise SystemExit("APK contains duplicate local ZIP header offsets")
        record_ends = {
            info.filename: (
                local_order[index + 1].header_offset
                if index + 1 < len(local_order)
                else signing_block_start
            )
            for index, info in enumerate(local_order)
        }
        for info in infos:
            name = info.filename
            pure = PurePosixPath(name)
            if pure.is_absolute() or ".." in pure.parts:
                raise SystemExit(f"APK contains an unsafe ZIP path: {name}")
            if name in seen_names:
                raise SystemExit(f"APK contains a duplicate ZIP entry: {name}")
            seen_names.add(name)
            total += info.file_size
            if total > MAX_TOTAL_UNCOMPRESSED:
                raise SystemExit("APK uncompressed payload exceeds verification safety limit")
            digest = hashlib.sha256()
            with archive.open(info) as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
            (
                local_header_sha256,
                compressed_sha256,
                local_record_sha256,
                local_record_size,
            ) = raw_entry_hashes(
                raw_apk,
                info,
                record_ends[name],
            )
            entries[name] = {
                "isDirectory": info.is_dir(),
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
                "localHeaderOffset": info.header_offset,
                "localRecordSha256": local_record_sha256,
                "localRecordSize": local_record_size,
            }
        if archive_comment_sha256 != hashlib.sha256(archive.comment).hexdigest():
            raise SystemExit("APK ZIP comment disagrees with the parsed end record")
    return entries, archive_comment_sha256, central_sha256


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("signed_apk")
    parser.add_argument("normalized_independent_apk")
    parser.add_argument("--signer-input-unsigned", required=True)
    parser.add_argument("--independent-unsigned", required=True)
    parser.add_argument("--unsigned-approval", required=True)
    parser.add_argument("--original-unsigned-build-sha256s", required=True)
    parser.add_argument("--verified-unsigned-sha256s", required=True)
    parser.add_argument("--post-sign-unsigned-sha256s", required=True)
    parser.add_argument(
        "--normalization-signer-certificate-sha256",
        required=True,
    )
    parser.add_argument("--release-signer-certificate-sha256", required=True)
    parser.add_argument(
        "--comparison-preparation",
        choices=("apksigner-v2-v3-ephemeral-rsa4096",),
        required=True,
    )
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    signed = Path(args.signed_apk)
    independent = Path(args.normalized_independent_apk)
    signer_input_unsigned = Path(args.signer_input_unsigned)
    independent_unsigned = Path(args.independent_unsigned)
    unsigned_approval = Path(args.unsigned_approval)
    original_unsigned_build_sha256s = Path(args.original_unsigned_build_sha256s)
    verified_unsigned_sha256s = Path(args.verified_unsigned_sha256s)
    post_sign_unsigned_sha256s = Path(args.post_sign_unsigned_sha256s)
    report_path = Path(args.report)

    version_match = re.fullmatch(
        r"clench-(\d+\.\d+\.\d+)-release\.apk",
        signed.name,
    )
    if not version_match:
        raise SystemExit("Signed APK does not use the canonical release name")
    version = version_match.group(1)
    if signer_input_unsigned.name != f"clench-{version}-unsigned.apk":
        raise SystemExit("Signer-input APK does not use the canonical name")
    if independent_unsigned.name != f"clench-{version}-independent-unsigned.apk":
        raise SystemExit("Independent unsigned APK does not use the canonical name")
    require_unsigned_apk(signer_input_unsigned)
    require_unsigned_apk(independent_unsigned)

    for label, value in (
        ("normalization signer", args.normalization_signer_certificate_sha256),
        ("release signer", args.release_signer_certificate_sha256),
    ):
        if not re.fullmatch(r"[0-9a-f]{64}", value):
            raise SystemExit(f"Invalid {label} certificate SHA-256")
    if (
        args.normalization_signer_certificate_sha256
        == args.release_signer_certificate_sha256
    ):
        raise SystemExit("Disposable and release signer certificates must differ")

    signer_input_sha256 = file_sha256(signer_input_unsigned)
    independent_unsigned_sha256 = file_sha256(independent_unsigned)
    if signer_input_sha256 != independent_unsigned_sha256:
        raise SystemExit(
            "Original signer input and independent raw unsigned APK differ"
        )
    signed_entries, signed_comment, signed_central = inventory(signed)
    independent_entries, independent_comment, independent_central = inventory(independent)

    missing = sorted(set(signed_entries) - set(independent_entries))
    unexpected = sorted(set(independent_entries) - set(signed_entries))
    changed = sorted(
        name
        for name in set(signed_entries) & set(independent_entries)
        if signed_entries[name] != independent_entries[name]
    )
    if (
        missing
        or unexpected
        or changed
        or signed_comment != independent_comment
        or signed_central != independent_central
    ):
        summary = {
            "missingFromIndependent": missing[:20],
            "unexpectedInIndependent": unexpected[:20],
            "changedEntries": changed[:20],
            "archiveCommentChanged": signed_comment != independent_comment,
            "centralDirectoryChanged": signed_central != independent_central,
        }
        raise SystemExit("APK payload comparison failed: " + json.dumps(summary, sort_keys=True))

    manifest = json.dumps(
        {
            "archiveCommentSha256": signed_comment,
            "centralDirectorySha256": signed_central,
            "entries": [
                [name, signed_entries[name]]
                for name in sorted(signed_entries)
            ],
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    report = {
        "schemaVersion": REPORT_SCHEMA_VERSION,
        "comparison": "PASS",
        "comparisonPolicy": COMPARISON_POLICY,
        "toolchain": {
            "aaptSha256": AAPT_SHA256,
            "apksignerBuildToolsVersion": APKSIGNER_BUILD_TOOLS_VERSION,
            "apksignerExecutableSha256": APKSIGNER_EXECUTABLE_SHA256,
            "apksignerJarSha256": APKSIGNER_JAR_SHA256,
        },
        "signingProfile": SIGNING_PROFILE,
        "signingBlockPolicy": SIGNING_BLOCK_POLICY,
        "normalization": {
            "ephemeralKeystoreRemovedBeforeComparison": True,
            "method": args.comparison_preparation,
            "verification": VERIFICATION_PROFILE,
        },
        "buildEvidence": {
            "originalUnsignedBuildSha256sSha256": file_sha256(
                original_unsigned_build_sha256s
            ),
            "postSignUnsignedSha256sSha256": file_sha256(
                post_sign_unsigned_sha256s
            ),
            "unsignedApprovalSha256": file_sha256(unsigned_approval),
            "verifiedUnsignedSha256sSha256": file_sha256(
                verified_unsigned_sha256s
            ),
        },
        "signedApk": signed.name,
        "signedApkSha256": file_sha256(signed),
        "releaseSignerCertificateSha256": args.release_signer_certificate_sha256,
        "signerInputUnsignedApk": signer_input_unsigned.name,
        "signerInputUnsignedApkSha256": signer_input_sha256,
        "independentUnsignedApk": independent_unsigned.name,
        "independentUnsignedApkSha256": independent_unsigned_sha256,
        "rawUnsignedByteIdentical": True,
        "comparedPayloadEntries": len(signed_entries),
        "payloadManifestSha256": hashlib.sha256(manifest).hexdigest(),
        "excludedZipEntries": [],
        "excludedArchiveRegions": [
            "APK Signing Block v2/v3 signer-dependent values"
        ],
        "normalizedArchiveFields": [
            "APK Signing Block zero-padding length",
            "EOCD.centralDirectoryOffset",
        ],
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"Independent APK payload verification passed for "
        f"{len(signed_entries)} ZIP entries after pinned apksigner "
        f"normalization."
    )


if __name__ == "__main__":
    main()
