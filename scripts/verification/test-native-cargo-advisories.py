#!/usr/bin/env python3
"""Finite source-binding/coverage regressions; no network requests."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("native_cargo", Path(__file__).resolve().parents[1] / "release/check-native-cargo-advisories.py")
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class NativeCargoTests(unittest.TestCase):
    def fixture(self, text, *, digest=None):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        raw = text.encode()
        lock = root / "Cargo.lock"
        lock.write_bytes(raw)
        baseline = root / "baseline.json"
        baseline.write_text(json.dumps({"artifacts": [{
            "owner_purl": scanner.OWNER, "artifact_sha256": "a" * 64,
            "source_review": {"evidence": [{"url": scanner.SOURCE,
                "sha256": digest or hashlib.sha256(raw).hexdigest()}]},
        }]}))
        return lock, baseline

    ROOT = '[[package]]\nname="bdk-ffi"\nversion="3.0.0"\n'
    PACKAGE = '[[package]]\nname="example"\nversion="1.2.3"\nsource="registry+https://github.com/rust-lang/crates.io-index"\n'

    def test_reviewed_real_lock_keeps_all_registry_candidates(self):
        owner, digest, purls = scanner.load_candidates(
            scanner.ROOT / "docs/security/upstream/bdk-ffi-3.0.0-Cargo.lock",
            scanner.ROOT / "docs/security/native-dependencies.json")
        self.assertEqual(198, len(purls))
        self.assertIn("pkg:cargo/rustls-webpki@0.101.7", purls)
        self.assertIn("pkg:cargo/rustls-webpki@0.103.13", purls)
        self.assertIn("pkg:cargo/anyhow@1.0.102", purls)

    def test_changed_lock_fails_before_query(self):
        with self.assertRaisesRegex(ValueError, "reviewed source hash"):
            scanner.load_candidates(*self.fixture(self.ROOT + self.PACKAGE, digest="0" * 64))

    def test_unknown_registry_is_not_silently_omitted(self):
        text = (self.ROOT + self.PACKAGE).replace(scanner.REGISTRY, "git+https://example.invalid/dependency")
        with self.assertRaisesRegex(ValueError, "Unreviewed Cargo source"):
            scanner.load_candidates(*self.fixture(text))

    def test_unreviewed_local_dependency_is_not_silently_omitted(self):
        text = self.ROOT + self.PACKAGE + '[[package]]\nname="local-extra"\nversion="1.0.0"\n'
        with self.assertRaisesRegex(ValueError, "source-less"):
            scanner.load_candidates(*self.fixture(text))

    def test_duplicate_candidate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            scanner.load_candidates(*self.fixture(self.ROOT + self.PACKAGE * 2))

    def test_empty_registry_inventory_is_not_a_clean_scan(self):
        with self.assertRaisesRegex(ValueError, "registry candidates"):
            scanner.load_candidates(*self.fixture(self.ROOT))


if __name__ == "__main__":
    unittest.main()
