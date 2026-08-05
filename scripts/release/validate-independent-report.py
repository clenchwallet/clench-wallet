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
EXPECTED_TOOLCHAIN = {
    "aaptSha256": "393c4c87f675f9555c50921fb53be1f3893cacd29f2270c4863e2e3f99583c39",
    "apksignerBuildToolsVersion": "35.0.0",
    "apksignerExecutableSha256": "b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0",
    "apksignerJarSha256": "00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7",
}
EXPECTED_SIGNING_PROFILE = {
    "alignmentPreserved": False,
    "libPageAlignment": 16_384,
    "minSdkVersion": 26,
    "v1": False,
    "v2": True,
    "v3": True,
    "v4": False,
    "verity": False,
}
EXPECTED_VERIFICATION_PROFILE = {
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
EXPECTED_SIGNING_BLOCK_POLICY = {
    "alignmentBytes": 4096,
    "pairOrder": ["0x7109871a", "0xf05368c0", "0x42726577"],
    "paddingLengthRange": [1, 4095],
    "paddingValue": "all-zero",
    "unknownOrDuplicatePairsAllowed": False,
    "v2AndV3ValuesSignerDependent": True,
}


def sha256(path: Path) -> str:
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
    payload_end = raw_apk.tell()
    if payload_end > record_end:
        raise SystemExit(
            f"Signed APK ZIP record overlaps the next region: {info.filename}"
        )
    raw_apk.seek(info.header_offset)
    record_digest = hashlib.sha256()
    remaining = record_end - info.header_offset
    while remaining:
        chunk = raw_apk.read(min(remaining, 1024 * 1024))
        if not chunk:
            raise SystemExit(f"Signed APK ZIP record is truncated: {info.filename}")
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
            raise SystemExit("Signed APK ZIP region is truncated")
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
            raise SystemExit("Signed APK Signing Block pair header is truncated")
        handle.seek(position)
        pair_size = struct.unpack("<Q", handle.read(8))[0]
        if pair_size < 5 or pair_size > pairs_end - position - 8:
            raise SystemExit("Signed APK Signing Block pair has invalid bounds")
        pair_id = struct.unpack("<I", handle.read(4))[0]
        if pair_id in seen_ids:
            raise SystemExit("Signed APK Signing Block contains a duplicate pair ID")
        if pair_id not in EXPECTED_SIGNING_BLOCK_PAIR_ORDER:
            raise SystemExit(
                "Signed APK Signing Block contains an unknown pair ID: "
                f"0x{pair_id:08x}"
            )
        value_size = pair_size - 4
        if value_size <= 0:
            raise SystemExit("Signed APK Signing Block pair has an empty value")
        if pair_id == VERITY_PADDING_BLOCK_ID:
            padding_size = value_size
            remaining = value_size
            while remaining:
                chunk = handle.read(min(remaining, 1024 * 1024))
                if not chunk or any(chunk):
                    raise SystemExit(
                        "Signed APK Signing Block padding is not all-zero"
                    )
                remaining -= len(chunk)
        seen_ids.add(pair_id)
        pair_ids.append(pair_id)
        position += 8 + pair_size
    if position != pairs_end:
        raise SystemExit("Signed APK Signing Block pairs do not end at the footer")
    if tuple(pair_ids) != EXPECTED_SIGNING_BLOCK_PAIR_ORDER:
        raise SystemExit(
            "Signed APK Signing Block pair order or required IDs are invalid"
        )
    block_size = central_offset - signing_block_start
    if (
        signing_block_start % 4096 != 0
        or central_offset % 4096 != 0
        or block_size % 4096 != 0
        or not 1 <= padding_size <= 4095
    ):
        raise SystemExit(
            "Signed APK Signing Block padding or alignment is noncanonical"
        )
    return tuple(pair_ids)


def zip_layout(path: Path, expected_entries: int) -> tuple[int, str, str]:
    size = path.stat().st_size
    if size < 46:
        raise SystemExit("Signed APK is too small to contain a ZIP archive")
    with path.open("rb") as handle:
        tail_size = min(size, 65_557)
        handle.seek(size - tail_size)
        tail = handle.read(tail_size)
        marker = tail.rfind(b"PK\x05\x06")
        if marker < 0 or marker + 22 > len(tail):
            raise SystemExit("Signed APK end-of-central-directory record is missing")
        eocd_offset = size - tail_size + marker
        fields = struct.unpack("<IHHHHIIH", tail[marker : marker + 22])
        _, disk, central_disk, disk_entries, total_entries, central_size, central_offset, comment_size = fields
        if disk != 0 or central_disk != 0 or disk_entries != total_entries:
            raise SystemExit("Multi-disk signed APK archives are forbidden")
        if total_entries != expected_entries:
            raise SystemExit("Signed APK entry count disagrees with its central directory")
        if eocd_offset + 22 + comment_size != size:
            raise SystemExit("Signed APK end record or comment length is malformed")
        if central_offset + central_size != eocd_offset:
            raise SystemExit("Signed APK central-directory bounds are malformed")
        if central_offset < 24:
            raise SystemExit("Signed APK Signing Block is missing")
        handle.seek(central_offset - 24)
        footer = handle.read(24)
        block_size = struct.unpack("<Q", footer[:8])[0]
        if footer[8:] != APK_SIGNING_BLOCK_MAGIC or block_size < 24:
            raise SystemExit("Signed APK Signing Block footer is malformed")
        signing_block_start = central_offset - (block_size + 8)
        if signing_block_start < 8:
            raise SystemExit("Signed APK Signing Block bounds are malformed")
        handle.seek(signing_block_start)
        if struct.unpack("<Q", handle.read(8))[0] != block_size:
            raise SystemExit("Signed APK Signing Block size fields disagree")
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
                "Signed APK contains case-conflicting META-INF entries: "
                f"{by_upper[upper]} and {info.filename}"
            )
        by_upper[upper] = info.filename
    for upper, original in by_upper.items():
        if upper == "META-INF/MANIFEST.MF" or SIGNATURE_FILE.fullmatch(upper):
            raise SystemExit(
                "Signed APK contains forbidden v1 signature metadata: " + original
            )


def signed_payload_identity(path: Path) -> tuple[int, str]:
    entries: dict[str, dict[str, object]] = {}
    seen_names: set[str] = set()
    total = 0
    with path.open("rb") as raw_apk, zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ZIP_ENTRIES:
            raise SystemExit("Signed APK contains too many ZIP entries")
        reject_v1_signature_entries(infos)
        signing_block_start, central_sha256, archive_comment_sha256 = zip_layout(
            path,
            len(infos),
        )
        local_order = sorted(infos, key=lambda candidate: candidate.header_offset)
        if not local_order or local_order[0].header_offset != 0:
            raise SystemExit("Signed APK contains a prefix before its first ZIP entry")
        if len({info.header_offset for info in local_order}) != len(local_order):
            raise SystemExit("Signed APK contains duplicate local ZIP header offsets")
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
                raise SystemExit(f"Signed APK contains an unsafe ZIP path: {name}")
            if name in seen_names:
                raise SystemExit(f"Signed APK contains a duplicate ZIP entry: {name}")
            seen_names.add(name)
            total += info.file_size
            if total > MAX_TOTAL_UNCOMPRESSED:
                raise SystemExit("Signed APK payload exceeds the safety limit")
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
            raise SystemExit("Signed APK comment disagrees with its end record")
    manifest = json.dumps(
        {
            "archiveCommentSha256": archive_comment_sha256,
            "centralDirectorySha256": central_sha256,
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
    parser.add_argument("--unsigned-approval", required=True)
    parser.add_argument("--original-unsigned-build-sha256s", required=True)
    parser.add_argument("--verified-unsigned-sha256s", required=True)
    parser.add_argument("--post-sign-unsigned-sha256s", required=True)
    parser.add_argument("--expected-release-signer-sha256", required=True)
    args = parser.parse_args()

    if not re.fullmatch(r"[0-9a-f]{64}", args.expected_release_signer_sha256):
        raise SystemExit("Expected release signer SHA-256 is invalid")

    report_path = Path(args.report)
    raw = report_path.read_text(encoding="utf-8")
    report = json.loads(raw)
    signed = Path(args.signed_apk)
    unsigned_approval = Path(args.unsigned_approval)
    original_unsigned_build_sha256s = Path(args.original_unsigned_build_sha256s)
    verified_unsigned_sha256s = Path(args.verified_unsigned_sha256s)
    post_sign_unsigned_sha256s = Path(args.post_sign_unsigned_sha256s)
    entry_count, payload_manifest = signed_payload_identity(signed)
    expected_keys = {
        "schemaVersion",
        "comparison",
        "comparisonPolicy",
        "toolchain",
        "signingProfile",
        "signingBlockPolicy",
        "normalization",
        "buildEvidence",
        "signedApk",
        "signedApkSha256",
        "releaseSignerCertificateSha256",
        "signerInputUnsignedApk",
        "signerInputUnsignedApkSha256",
        "independentUnsignedApk",
        "independentUnsignedApkSha256",
        "rawUnsignedByteIdentical",
        "comparedPayloadEntries",
        "payloadManifestSha256",
        "excludedZipEntries",
        "excludedArchiveRegions",
        "normalizedArchiveFields",
    }
    if set(report) != expected_keys:
        raise SystemExit("Independent APK report fields are incomplete or unexpected")
    if report.get("schemaVersion") != REPORT_SCHEMA_VERSION:
        raise SystemExit("Independent APK report has an unexpected schema version")
    if report.get("comparison") != "PASS":
        raise SystemExit("Independent APK report does not record a pass")
    if report.get("comparisonPolicy") != COMPARISON_POLICY:
        raise SystemExit("Independent APK report used an unexpected comparison policy")
    if report.get("toolchain") != EXPECTED_TOOLCHAIN:
        raise SystemExit("Independent APK report used an unexpected toolchain")
    if report.get("signingProfile") != EXPECTED_SIGNING_PROFILE:
        raise SystemExit("Independent APK report used an unexpected signing profile")
    if report.get("signingBlockPolicy") != EXPECTED_SIGNING_BLOCK_POLICY:
        raise SystemExit("Independent APK report used an unexpected signing-block policy")
    normalization = report.get("normalization")
    if not isinstance(normalization, dict) or set(normalization) != {
        "ephemeralKeystoreRemovedBeforeComparison",
        "method",
        "verification",
    }:
        raise SystemExit("Independent APK report has invalid normalization evidence")
    if normalization.get("ephemeralKeystoreRemovedBeforeComparison") is not True:
        raise SystemExit("Independent APK report did not destroy its ephemeral key")
    if normalization.get("method") != "apksigner-v2-v3-ephemeral-rsa4096":
        raise SystemExit("Independent APK report used an unexpected normalization method")
    if normalization.get("verification") != EXPECTED_VERIFICATION_PROFILE:
        raise SystemExit("Independent APK report has unexpected verification results")
    build_evidence = report.get("buildEvidence")
    if build_evidence != {
        "originalUnsignedBuildSha256sSha256": sha256(
            original_unsigned_build_sha256s
        ),
        "postSignUnsignedSha256sSha256": sha256(post_sign_unsigned_sha256s),
        "unsignedApprovalSha256": sha256(unsigned_approval),
        "verifiedUnsignedSha256sSha256": sha256(verified_unsigned_sha256s),
    }:
        raise SystemExit("Independent APK report does not bind pre-sign evidence")
    if report.get("signedApk") != signed.name:
        raise SystemExit("Independent APK report names a different signed APK")
    if report.get("signedApkSha256") != sha256(signed):
        raise SystemExit("Independent APK report digest does not match the signed APK")
    if report.get("releaseSignerCertificateSha256") != args.expected_release_signer_sha256:
        raise SystemExit("Independent APK report names the wrong release signer")
    version_match = re.fullmatch(r"clench-(\d+\.\d+\.\d+)-release\.apk", signed.name)
    if not version_match:
        raise SystemExit("Signed APK does not use the canonical release name")
    version = version_match.group(1)
    expected_unsigned_names = {
        "signerInputUnsignedApk": f"clench-{version}-unsigned.apk",
        "independentUnsignedApk": f"clench-{version}-independent-unsigned.apk",
    }
    for field, expected_name in expected_unsigned_names.items():
        if report.get(field) != expected_name:
            raise SystemExit(
                f"Independent APK report has a noncanonical unsigned APK name: {field}"
            )
    if report.get("comparedPayloadEntries") != entry_count or entry_count < 100:
        raise SystemExit("Independent APK report entry count does not match the signed APK")
    if report.get("payloadManifestSha256") != payload_manifest:
        raise SystemExit("Independent APK report payload manifest does not match the signed APK")
    signer_input_sha256 = str(report.get("signerInputUnsignedApkSha256", ""))
    independent_sha256 = str(report.get("independentUnsignedApkSha256", ""))
    if not re.fullmatch(r"[0-9a-f]{64}", signer_input_sha256):
        raise SystemExit("Independent APK report has an invalid signer-input SHA-256")
    if not re.fullmatch(r"[0-9a-f]{64}", independent_sha256):
        raise SystemExit("Independent APK report has an invalid rebuild SHA-256")
    if signer_input_sha256 != independent_sha256:
        raise SystemExit("Independent APK report raw unsigned digests differ")
    if report.get("rawUnsignedByteIdentical") is not True:
        raise SystemExit("Independent APK report does not prove raw byte identity")
    if report.get("excludedZipEntries") != []:
        raise SystemExit("Independent APK report used an unexpected exclusion policy")
    if report.get("excludedArchiveRegions") != [
        "APK Signing Block v2/v3 signer-dependent values"
    ]:
        raise SystemExit("Independent APK report used an unexpected archive exclusion")
    if report.get("normalizedArchiveFields") != [
        "APK Signing Block zero-padding length",
        "EOCD.centralDirectoryOffset",
    ]:
        raise SystemExit("Independent APK report normalized unexpected archive fields")
    canonical = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if raw != canonical:
        raise SystemExit("Independent APK report is not canonically serialized")
    print(
        "Validated exact independent APK payload-comparison report for "
        f"{entry_count} entries."
    )


if __name__ == "__main__":
    main()
