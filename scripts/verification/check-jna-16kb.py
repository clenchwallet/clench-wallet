#!/usr/bin/env python3
"""Check packaged JNA ELF layout and APK native ZIP alignment; not a runtime test."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

PAGE = 16384
MAX_LIBRARY = 32 * 1024 * 1024
MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def check_elf(data, abi):
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError(f"{abi}: expected a little-endian ELF64 library")
    kind, machine, version = struct.unpack_from("<HHI", data, 16)
    if (kind, machine, version) != (3, MACHINES[abi], 1):
        raise ValueError(f"{abi}: wrong ELF type, machine or version")
    offset = struct.unpack_from("<Q", data, 32)[0]
    header_size, entry_size, count = struct.unpack_from("<HHH", data, 52)
    if header_size != 64 or entry_size != 56 or not 1 <= count <= 256:
        raise ValueError(f"{abi}: unsupported ELF program-header table")
    if offset < 64 or offset + count * entry_size > len(data):
        raise ValueError(f"{abi}: truncated ELF program-header table")
    loads, relros = [], []
    for index in range(count):
        segment, flags, file_offset, address, _, file_size, memory_size, alignment = (
            struct.unpack_from("<IIQQQQQQ", data, offset + index * entry_size)
        )
        if segment not in (1, 0x6474E552):
            continue
        if file_size > memory_size or file_offset + file_size > len(data):
            raise ValueError(f"{abi}: invalid segment bounds")
        if address + memory_size >= 1 << 64:
            raise ValueError(f"{abi}: segment address overflow")
        record = {"offset": file_offset, "vaddr": address, "filesz": file_size,
                  "memsz": memory_size, "alignment": alignment, "flags": flags}
        if segment == 1:
            if alignment < PAGE or alignment & (alignment - 1):
                raise ValueError(f"{abi}: LOAD alignment is below 16 KB or invalid")
            if (address - file_offset) % PAGE:
                raise ValueError(f"{abi}: LOAD offset/address are not 16 KB congruent")
            loads.append(record)
        else:
            if not memory_size or (address + memory_size) % PAGE:
                raise ValueError(f"{abi}: RELRO end is not 16 KB aligned")
            relros.append(record)
    if not loads or len(relros) != 1:
        raise ValueError(f"{abi}: expected LOAD segments and one GNU_RELRO segment")
    relro = relros[0]
    if not any(load["flags"] & 2 and load["vaddr"] <= relro["vaddr"]
               and relro["vaddr"] + relro["memsz"] <= load["vaddr"] + load["memsz"]
               for load in loads):
        raise ValueError(f"{abi}: RELRO is not contained in a writable LOAD segment")
    return {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
            "loads": loads, "relro": relro}


def zip_data_offset(stream, item):
    stream.seek(item.header_offset)
    header = stream.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("Malformed ZIP local header")
    name_size, extra_size = struct.unpack_from("<HH", header, 26)
    return item.header_offset + 30 + name_size + extra_size


def inspect_archive(path, kind):
    prefix = "lib" if kind == "apk" else "jni"
    result = {"schemaVersion": 1, "status": "PASS_STATIC_ONLY", "kind": kind,
              "archive_sha256": sha256(path), "page_size": PAGE,
              "scope": "JNA ARM64/x86_64 ELF layout; APK native ZIP alignment when applicable",
              "runtime_acceptance": "NOT_RUN", "jna": {}, "apk_native_alignment": []}
    with zipfile.ZipFile(path) as archive, path.open("rb") as stream:
        items = archive.infolist()
        names = [item.filename for item in items]
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP member")
        for abi in MACHINES:
            name = f"{prefix}/{abi}/libjnidispatch.so"
            if name not in names:
                raise ValueError(f"Missing expected packaged JNA library: {name}")
            item = archive.getinfo(name)
            if not 0 < item.file_size <= MAX_LIBRARY:
                raise ValueError(f"JNA member exceeds size bound: {name}")
            result["jna"][abi] = check_elf(archive.read(item), abi)
        if kind == "apk":
            for item in items:
                if not item.filename.startswith("lib/") or not item.filename.endswith(".so"):
                    continue
                if item.compress_type != zipfile.ZIP_STORED:
                    raise ValueError("Expected uncompressed Clench APK native libraries")
                offset = zip_data_offset(stream, item)
                if offset % PAGE:
                    raise ValueError(f"APK native member is not 16 KB ZIP-aligned: {item.filename}")
                result["apk_native_alignment"].append({"path": item.filename, "offset": offset})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path)
    source.add_argument("--aar", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    path, kind = (args.apk, "apk") if args.apk else (args.aar, "aar")
    try:
        result = inspect_archive(path, kind)
    except (ValueError, OSError, zipfile.BadZipFile, RuntimeError, struct.error) as exc:
        result = {"schemaVersion": 1, "status": "FAIL", "kind": kind,
                  "runtime_acceptance": "NOT_RUN", "error": str(exc)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
        raise SystemExit(str(exc)) from exc
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print("PASS: packaged JNA static 16 KB checks. Runtime acceptance is NOT RUN.")


if __name__ == "__main__":
    main()
