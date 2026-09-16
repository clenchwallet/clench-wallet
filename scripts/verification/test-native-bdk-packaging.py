#!/usr/bin/env python3
"""Check native replacement completeness, unchanged bindings and stable packaging."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("prepare_bdk", ROOT / "scripts/native/prepare-bdk.py")
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class PackagingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.vendor = self.root / "vendor.aar"
        self.jni = self.root / "jni"
        self.output = self.root / "patched.aar"
        with zipfile.ZipFile(self.vendor, "w") as z:
            z.writestr("classes.jar", b"exact original Kotlin bindings")
            z.writestr("AndroidManifest.xml", b"exact original manifest")
            for abi in MOD.ABIS:
                z.writestr(f"jni/{abi}/libbdkffi.so", b"old library")
                lib = self.jni / abi / "libbdkffi.so"
                lib.parent.mkdir(parents=True)
                lib.write_bytes(b"\x7fELF" + abi.encode())

    def test_all_abis_replaced_and_bindings_preserved_deterministically(self):
        MOD.package_aar(self.vendor, self.jni, self.output)
        first = self.output.read_bytes()
        MOD.package_aar(self.vendor, self.jni, self.output)
        self.assertEqual(first, self.output.read_bytes())
        with zipfile.ZipFile(self.output) as z:
            self.assertEqual(z.read("classes.jar"), b"exact original Kotlin bindings")
            self.assertEqual(z.read("AndroidManifest.xml"), b"exact original manifest")
            for abi in MOD.ABIS:
                self.assertEqual(z.read(f"jni/{abi}/libbdkffi.so"), b"\x7fELF" + abi.encode())

    def test_missing_rebuilt_abi_cannot_fall_back_to_vendor_library(self):
        (self.jni / "x86_64/libbdkffi.so").unlink()
        with self.assertRaises(FileNotFoundError):
            MOD.package_aar(self.vendor, self.jni, self.output)
        self.assertFalse(self.output.exists())

    def test_extra_vendor_native_library_rejected(self):
        with zipfile.ZipFile(self.vendor, "a") as z:
            z.writestr("jni/x86/libbdkffi.so", b"unexpected")
        with self.assertRaisesRegex(ValueError, "native entry set"):
            MOD.package_aar(self.vendor, self.jni, self.output)

    def test_non_elf_rebuild_rejected(self):
        (self.jni / "arm64-v8a/libbdkffi.so").write_bytes(b"not ELF")
        with self.assertRaisesRegex(ValueError, "not ELF"):
            MOD.package_aar(self.vendor, self.jni, self.output)

    def test_metadata_preserves_dependencies_but_binds_rebuilt_bytes(self):
        MOD.package_aar(self.vendor, self.jni, self.output)
        pom, module = self.root / "base.pom", self.root / "base.module"
        pom.write_text("<project><version>3.0.0</version><dependency>same</dependency></project>")
        dependencies = [{"group": "net.java.dev.jna", "module": "jna", "version": {"requires": "5.14.0"},
                         "thirdPartyCompatibility": {"artifactSelector": {"type": "aar"}}}]
        module.write_text(json.dumps({"component": {"version": "3.0.0"}, "variants": [
            {"attributes": {"org.gradle.category": "library"}, "dependencies": dependencies,
             "files": [{"name": "bdk-android-3.0.0.aar"}]},
            {"attributes": {"org.gradle.category": "documentation"}, "files": []}]}))
        MOD.package_metadata(pom, module, self.output, self.root)
        updated = json.loads((self.root / f"bdk-android-{MOD.VERSION}.module").read_text())
        self.assertEqual(updated["component"]["version"], MOD.VERSION)
        self.assertEqual(len(updated["variants"]), 1)
        self.assertEqual(updated["variants"][0]["dependencies"], dependencies)
        self.assertEqual(updated["variants"][0]["files"][0]["sha256"], MOD.digest(self.output))


if __name__ == "__main__":
    unittest.main()
