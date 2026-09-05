#!/usr/bin/env python3
"""Finite regressions for native artifact identity, drift detection and inventory bounds."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("native_inventory", ROOT / "scripts/release/inventory-native-artifacts.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class NativeInventoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "app").mkdir()
        (self.root / "gradle").mkdir()
        self.jar = self.root / "example-1.jar"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("jni/arm64-v8a/libexample.so", b"finite native fixture")
            archive.writestr("example.class", b"not native")
        digest = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        (self.root / "app/gradle.lockfile").write_text("example:example:1=releaseRuntimeClasspath\n")
        (self.root / "gradle/verification-metadata.xml").write_text(
            '<verification-metadata><components><component group="example" name="example" version="1">'
            f'<artifact name="{self.jar.name}"><sha256 value="{digest}"/></artifact>'
            '</component></components></verification-metadata>')
        self.manifest = self.root / "resolved.json"
        self.records = {"schemaVersion": 1, "modules": ["example:example:1"], "artifacts": [
            {"coordinate": "example:example:1", "name": self.jar.name, "file": str(self.jar)}]}
        self.save()

    def save(self):
        self.manifest.write_text(json.dumps(self.records))

    def inventory(self):
        return MODULE.inventory(self.root, self.manifest)

    def baseline(self, document):
        baseline = copy.deepcopy(document)
        for record in baseline["artifacts"]:
            record["source_review"] = {"status": "PARTIAL", "advisory_disposition": "NOT_REVIEWED"}
        return baseline

    def test_exact_payload_identity_without_vulnerability_claim(self):
        document = self.inventory()
        self.assertEqual(len(document["artifacts"]), 1)
        owner = document["artifacts"][0]
        self.assertEqual(owner["native_payloads"], [{"path": "jni/arm64-v8a/libexample.so",
            "bytes": 21, "sha256": hashlib.sha256(b"finite native fixture").hexdigest()}])
        self.assertEqual(owner["source_dependency_assurance"], "NOT_REVIEWED")

    def test_archive_tamper_rejected_before_inventory(self):
        with self.jar.open("ab") as handle:
            handle.write(b"changed")
        with self.assertRaisesRegex(ValueError, "verification metadata"):
            self.inventory()

    def test_resolved_graph_must_equal_locked_runtime(self):
        self.records["modules"].append("other:module:1")
        self.save()
        with self.assertRaisesRegex(ValueError, "module graph"):
            self.inventory()

    def test_duplicate_resolved_artifact_rejected(self):
        self.records["artifacts"] *= 2
        self.save()
        with self.assertRaisesRegex(ValueError, "duplicate runtime artifact"):
            self.inventory()

    def test_duplicate_native_path_rejected(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.jar, "a") as archive:
                archive.writestr("jni/arm64-v8a/libexample.so", b"different")
        with self.assertRaisesRegex(ValueError, "Duplicate native"):
            MODULE.native_entries(self.jar)

    def test_finite_bounds(self):
        for limit in ("MAX_NATIVE_BYTES", "MAX_TOTAL_NATIVE_BYTES"):
            with self.subTest(limit=limit), patch.object(MODULE, limit, 4):
                with self.assertRaisesRegex(ValueError, "bound"):
                    MODULE.native_entries(self.jar)

    def test_baseline_preserves_unreviewed_advisory_status(self):
        document = self.inventory()
        result = MODULE.check_baseline(document, self.baseline(document))
        self.assertEqual(result["artifacts"][0]["source_review"]["advisory_disposition"], "NOT_REVIEWED")

    def test_new_owner_or_removed_owner_requires_review(self):
        document = self.inventory()
        baseline = self.baseline(document)
        baseline["artifacts"] = []
        with self.assertRaisesRegex(ValueError, "owner inventory changed"):
            MODULE.check_baseline(document, baseline)

    def test_payload_drift_requires_review(self):
        document = self.inventory()
        baseline = self.baseline(document)
        baseline["artifacts"][0]["native_payloads"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "payload changed"):
            MODULE.check_baseline(document, baseline)

    def test_duplicate_baseline_owner_rejected(self):
        document = self.inventory()
        baseline = self.baseline(document)
        baseline["artifacts"] *= 2
        with self.assertRaisesRegex(ValueError, "owner inventory changed"):
            MODULE.check_baseline(document, baseline)


if __name__ == "__main__":
    unittest.main()
