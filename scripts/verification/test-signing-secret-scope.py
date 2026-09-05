#!/usr/bin/env python3
"""Synthetic metadata checks only; no GitHub calls or credentials."""
import importlib.util
from pathlib import Path
import unittest

p = Path(__file__).resolve().parents[1] / "release/verify-signing-secret-scope.py"
spec = importlib.util.spec_from_file_location("scope", p)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def document(names):
    return {"total_count": len(names), "secrets": [{"name": name} for name in names]}


class ScopeTest(unittest.TestCase):
    def test_repository_only_fails(self):
        self.assertEqual(2, len(m.validate(document(m.REQUIRED), document([]))))
    def test_duplicate_copies_fail(self):
        self.assertEqual(1, len(m.validate(document(m.REQUIRED), document(m.REQUIRED))))
    def test_environment_only_passes(self):
        self.assertEqual([], m.validate(document(["UNRELATED"]), document(m.REQUIRED)))
    def test_missing_one_value_fails(self):
        self.assertTrue(m.validate(document([]), document(sorted(m.REQUIRED)[1:])))
    def test_partial_metadata_fails_closed(self):
        with self.assertRaises(ValueError): m.validate({"total_count": 3, "secrets": []}, document(m.REQUIRED))


if __name__ == "__main__": unittest.main()
