#!/usr/bin/env python3
"""Negative controls for the packaged JNA 16 KB check; no emulator claims."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile

SPEC = importlib.util.spec_from_file_location("jna16", Path(__file__).with_name("check-jna-16kb.py"))
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


def elf(machine, relro_size=0x4000, alignment=0x4000):
    data = bytearray(0x9000)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHIQQQIHHHHHH", data, 16,
                     3, machine, 1, 0, 64, 0, 0, 64, 56, 3, 0, 0, 0)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 0x4000, 0x4000, alignment)
    struct.pack_into("<IIQQQQQQ", data, 120, 1, 6, 0x4000, 0x4000, 0, 0x5000, 0x5000, alignment)
    struct.pack_into("<IIQQQQQQ", data, 176, 0x6474E552, 4, 0x4000, 0x4000, 0,
                     relro_size, relro_size, 1)
    return data


class Jna16KbTest(unittest.TestCase):
    def test_both_architectures_pass_static_only(self):
        for abi, machine in CHECK.MACHINES.items():
            self.assertEqual(CHECK.check_elf(elf(machine), abi)["relro"]["memsz"], 0x4000)

    def test_writable_tail_in_same_page_as_relro_rejected(self):
        with self.assertRaisesRegex(ValueError, "RELRO end"):
            CHECK.check_elf(elf(183, relro_size=0x2000), "arm64-v8a")

    def test_insufficient_load_alignment_rejected_even_when_relro_aligned(self):
        with self.assertRaisesRegex(ValueError, "LOAD alignment"):
            CHECK.check_elf(elf(183, alignment=4096), "arm64-v8a")

    def test_wrong_architecture_rejected(self):
        with self.assertRaisesRegex(ValueError, "machine"):
            CHECK.check_elf(elf(62), "arm64-v8a")

    def test_truncated_headers_rejected(self):
        with self.assertRaisesRegex(ValueError, "truncated"):
            CHECK.check_elf(elf(183)[:130], "arm64-v8a")

    def test_missing_relro_does_not_pass_as_a_fix(self):
        data = elf(183)
        struct.pack_into("<I", data, 176, 0)
        with self.assertRaisesRegex(ValueError, "GNU_RELRO"):
            CHECK.check_elf(data, "arm64-v8a")

    def make_archive(self, directory, kind, aligned=True, omit=None, duplicate=False):
        path = Path(directory) / ("fixture." + kind)
        prefix = "lib" if kind == "apk" else "jni"
        with zipfile.ZipFile(path, "w") as archive:
            for abi, machine in CHECK.MACHINES.items():
                if abi == omit:
                    continue
                name = f"{prefix}/{abi}/libjnidispatch.so"
                info = zipfile.ZipInfo(name)
                if kind == "apk" and aligned:
                    start = archive.fp.tell() + 30 + len(name.encode())
                    pad = (-start) % CHECK.PAGE
                    if pad < 4:
                        pad += CHECK.PAGE
                    info.extra = struct.pack("<HH", 0xCAFE, pad - 4) + b"\0" * (pad - 4)
                archive.writestr(info, elf(machine))
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr(name, elf(machine))
        return path

    def test_aligned_apk_records_all_native_entries_but_no_runtime_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            result = CHECK.inspect_archive(self.make_archive(directory, "apk"), "apk")
        self.assertEqual(result["status"], "PASS_STATIC_ONLY")
        self.assertEqual(result["runtime_acceptance"], "NOT_RUN")
        self.assertEqual(len(result["apk_native_alignment"]), 2)

    def test_valid_elf_does_not_hide_bad_apk_zip_alignment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_archive(directory, "apk", aligned=False)
            with self.assertRaisesRegex(ValueError, "ZIP-aligned"):
                CHECK.inspect_archive(path, "apk")

    def test_missing_expected_abi_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_archive(directory, "aar", omit="x86_64")
            with self.assertRaisesRegex(ValueError, "Missing expected"):
                CHECK.inspect_archive(path, "aar")

    def test_duplicate_members_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_archive(directory, "aar", duplicate=True)
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                CHECK.inspect_archive(path, "aar")


if __name__ == "__main__":
    unittest.main()
