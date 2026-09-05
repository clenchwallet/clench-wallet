#!/usr/bin/env python3
"""Finite source-binding/coverage regressions; no network requests."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import datetime as dt
import copy

spec = importlib.util.spec_from_file_location("native_cargo", Path(__file__).resolve().parents[1] / "release/check-native-cargo-advisories.py")
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class NativeCargoTests(unittest.TestCase):
    def disposition_fixture(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / "app/src/main").mkdir(parents=True)
        (root / "docs/security").mkdir(parents=True)
        for name in ["src/main/Test.kt", "build.gradle.kts", "gradle.lockfile", "proguard-rules.pro"]:
            (root / "app" / name).write_text("reviewed")
        evidence = root / "docs/security/evidence.md"
        evidence.write_text("Specific reviewed Android source paths")
        advisory = {"id": "RUSTSEC-2099-0001", "details": "Original reviewed advisory"}
        entry = dict(purl="pkg:cargo/example@1.0.0", id=advisory["id"],
                     status="not_affected_reviewed_call_path", reason="A concrete source-bound applicability rationale. " * 3,
                     advisory_sha256=scanner.canonical_hash(advisory))
        owner = {"artifact_sha256": "a" * 64}
        document = dict(schema_version=1, owner_purl=scanner.OWNER,
                        owner_artifact_sha256=owner["artifact_sha256"], source_lock_sha256="b" * 64,
                        application_sha256=scanner.application_hash(root), reviewed_on="2099-01-01",
                        expires_on="2099-01-31", evidence=[dict(path="docs/security/evidence.md",
                        sha256=hashlib.sha256(evidence.read_bytes()).hexdigest())], entries=[entry])
        findings = {(entry["purl"], entry["id"])}
        def run(doc=None, live=None, matches=None, today=None):
            return scanner.disposition_results(doc if doc is not None else document, owner, "b" * 64,
                    findings if matches is None else matches, root,
                    today=today or dt.date(2099, 1, 5), fetch_advisory=lambda _: live or advisory)
        return root, document, advisory, findings, run

    def test_exact_review_keeps_raw_match_and_reports_disposition(self):
        _, doc, _, findings, run = self.disposition_fixture()
        reviewed, unresolved = run()
        self.assertEqual(doc["entries"], reviewed)
        self.assertEqual([], unresolved)
        self.assertEqual(1, len(findings))

    def test_new_advisory_is_unresolved_not_package_wide_suppressed(self):
        _, _, _, findings, run = self.disposition_fixture()
        new = ("pkg:cargo/example@1.0.0", "RUSTSEC-2099-0002")
        self.assertEqual([new], run(matches=findings | {new})[1])

    def test_changed_or_new_production_source_invalidates_review(self):
        root, _, _, _, run = self.disposition_fixture()
        (root / "app/src/main/Added.kt").write_text("new native client")
        with self.assertRaisesRegex(ValueError, "binding changed"):
            run()

    def test_new_release_source_set_invalidates_review(self):
        root, _, _, _, run = self.disposition_fixture()
        (root / "app/src/release").mkdir()
        (root / "app/src/release/Added.kt").write_text("new release-only native client")
        with self.assertRaisesRegex(ValueError, "binding changed"):
            run()

    def test_changed_artifact_or_lock_invalidates_review(self):
        _, doc, _, _, run = self.disposition_fixture()
        for field in ["owner_artifact_sha256", "source_lock_sha256"]:
            changed = copy.deepcopy(doc)
            changed[field] = "0" * 64
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "binding changed"):
                run(changed)

    def test_expired_future_or_overlong_review_is_rejected(self):
        _, doc, _, _, run = self.disposition_fixture()
        for today in [dt.date(2098, 12, 31), dt.date(2099, 2, 1)]:
            with self.assertRaisesRegex(ValueError, "review interval"):
                run(today=today)
        doc["expires_on"] = "2099-03-01"
        with self.assertRaisesRegex(ValueError, "review interval"):
            run()

    def test_advisory_content_change_invalidates_disposition(self):
        _, _, advisory, _, run = self.disposition_fixture()
        advisory = dict(advisory, details="Expanded affected operation")
        with self.assertRaisesRegex(ValueError, "content changed"):
            run(live=advisory)

    def test_changed_evidence_invalidates_disposition(self):
        root, _, _, _, run = self.disposition_fixture()
        (root / "docs/security/evidence.md").write_text("different conclusion")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            run()

    def test_stale_duplicate_and_blanket_dispositions_are_rejected(self):
        _, doc, _, _, run = self.disposition_fixture()
        with self.assertRaisesRegex(ValueError, "Stale"):
            run(matches=set())
        changed = copy.deepcopy(doc)
        changed["entries"].append(changed["entries"][0])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            run(changed)
        doc["entries"][0]["status"] = "accepted_risk"
        with self.assertRaisesRegex(ValueError, "rationale"):
            run()

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
