#!/usr/bin/env python3
"""Build patched BDK JNI and package a deterministic, private local Maven artifact.

The original, checksum-pinned Kotlin bindings and dependency variants are retained.
No signing credentials or remote Maven publication are involved.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VERSION = "3.0.0-clench.1"
BASE = "bdk-android-3.0.0"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
PINS = {
    "aar": "e11f099ab3f7acce9770825d9f431ce0970a30356c46ebed6aef66594491bb1e",
    "pom": "1580a08433701701497960935ba2e6eb84719a63c26e03199f8dbc3dc797c1d8",
    "module": "89c2093d255b4c653dda57cac3dc026a711cd61c8af656315a1dc0065d62e34d",
}


def digest(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def upstream(extension, destination):
    """Use only exact pinned bytes, whether cached or fetched from Maven Central."""
    name = f"{BASE}.{extension}"
    target = destination / name
    if target.is_file():
        if digest(target) != PINS[extension]:
            raise ValueError(f"Cached upstream input changed: {name}")
        return target
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    cache = gradle_home / "caches/modules-2/files-2.1/org.bitcoindevkit/bdk-android/3.0.0"
    for candidate in sorted(cache.glob(f"*/{name}")):
        if digest(candidate) == PINS[extension]:
            shutil.copyfile(candidate, target)
            return target
    partial = target.with_suffix(target.suffix + ".partial")
    subprocess.run([
        "curl", "--proto", "=https", "--tlsv1.2", "--fail", "--location",
        "--silent", "--show-error", "--retry", "3", "--connect-timeout", "20",
        "--max-time", "300", "--max-filesize", "67108864", "--output", str(partial),
        f"https://repo.maven.apache.org/maven2/org/bitcoindevkit/bdk-android/3.0.0/{name}",
    ], check=True)
    if digest(partial) != PINS[extension]:
        raise ValueError(f"Upstream input checksum mismatch: {name}")
    partial.replace(target)
    return target


def package_aar(vendor, jni, output):
    expected = {f"jni/{abi}/libbdkffi.so" for abi in ABIS}
    replacements = {name: (jni / Path(name).relative_to("jni")).read_bytes() for name in expected}
    if any(not data.startswith(b"\x7fELF") for data in replacements.values()):
        raise ValueError("Rebuilt JNI input is not ELF")
    with zipfile.ZipFile(vendor) as source:
        names = source.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate upstream AAR entries")
        if {name for name in names if name.endswith(".so")} != expected:
            raise ValueError("Upstream native entry set changed")
        partial = output.with_suffix(".aar.partial")
        with zipfile.ZipFile(partial, "w", compression=zipfile.ZIP_STORED) as target:
            for name in sorted(names):
                if name.endswith("/"):
                    continue
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                target.writestr(info, replacements[name] if name in replacements else source.read(name))
        partial.replace(output)
    with zipfile.ZipFile(vendor) as source, zipfile.ZipFile(output) as target:
        for name in names:
            if not name.endswith("/") and name not in expected and source.read(name) != target.read(name):
                raise ValueError("Non-native upstream bytes changed")


def package_metadata(pom, module, aar, output_dir):
    stem = f"bdk-android-{VERSION}"
    text = pom.read_text()
    marker = "<version>3.0.0</version>"
    if text.count(marker) != 1:
        raise ValueError("Unexpected upstream POM version")
    (output_dir / f"{stem}.pom").write_text(text.replace(marker, f"<version>{VERSION}</version>"))
    document = json.loads(module.read_text())
    if document["component"]["version"] != "3.0.0":
        raise ValueError("Unexpected upstream module version")
    document["component"]["version"] = VERSION
    # Do not advertise an unbuilt sources artifact under the patched coordinate.
    document["variants"] = [v for v in document["variants"]
                            if v["attributes"]["org.gradle.category"] != "documentation"]
    raw = aar.read_bytes()
    for variant in document["variants"]:
        files = variant["files"]
        if len(files) != 1 or files[0]["name"] != f"{BASE}.aar":
            raise ValueError("Unexpected upstream runtime artifact")
        variant["files"] = [{"name": aar.name, "url": aar.name, "size": len(raw),
                             "sha512": hashlib.sha512(raw).hexdigest(),
                             "sha256": hashlib.sha256(raw).hexdigest()}]
    (output_dir / f"{stem}.module").write_text(json.dumps(document, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package-only", action="store_true", help="Package already rebuilt JNI; Gradle still enforces committed artifact hashes")
    args = parser.parse_args()
    build = ROOT / "build/native-bdk"
    vendor_dir = build / "vendor"
    vendor_dir.mkdir(parents=True, exist_ok=True)
    inputs = {kind: upstream(kind, vendor_dir) for kind in PINS}
    if not args.package_only:
        subprocess.run(["bash", str(ROOT / "scripts/native/build-bdk.sh")], cwd=ROOT, check=True)
    output = build / f"maven/org/bitcoindevkit/bdk-android/{VERSION}"
    output.mkdir(parents=True, exist_ok=True)
    aar = output / f"bdk-android-{VERSION}.aar"
    package_aar(inputs["aar"], build / "jni", aar)
    package_metadata(inputs["pom"], inputs["module"], aar, output)
    manifest = {
        "coordinate": f"org.bitcoindevkit:bdk-android:{VERSION}",
        "upstream_inputs": {p.name: digest(p) for p in inputs.values()},
        "outputs": {p.name: digest(p) for p in sorted(output.iterdir()) if p.suffix in (".aar", ".pom", ".module")},
        "jni": {abi: digest(build / "jni" / abi / "libbdkffi.so") for abi in ABIS},
        "non_native_vendor_bytes_unchanged": True,
        "scope": "Local rebuilt native artifact; not release signing or complete native vulnerability clearance",
    }
    (build / "packaging-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(json.dumps(manifest, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
