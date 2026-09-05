#!/usr/bin/env python3
"""Finite, device-free acceptance-parser regressions for the upgrade fixture."""
import importlib.util
from pathlib import Path
import unittest
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location("upgrade", Path(__file__).with_name("run-sqlcipher-upgrade.py"))
upgrade = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upgrade)


class ResultTests(unittest.TestCase):
    good = "\n".join((
        "INSTRUMENTATION_STATUS: class=example.Fixture",
        "INSTRUMENTATION_STATUS_CODE: 1",
        "INSTRUMENTATION_STATUS: class=example.Fixture",
        "INSTRUMENTATION_STATUS_CODE: 0", "OK (1 test)", "INSTRUMENTATION_CODE: -1", ""
    ))

    def test_actual_single_case(self):
        upgrade.require_one_passing_case(self.good, "example.Fixture")
        upgrade.require_one_passing_case(self.good.replace("\n", "\r\n"), "example.Fixture")

    def test_skip_failure_or_incomplete_cannot_look_green(self):
        for status in ("-2", "-3", "-4", ""):
            with self.subTest(status=status), self.assertRaises(RuntimeError):
                upgrade.require_one_passing_case(self.good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: " + status), "example.Fixture")

    def test_wrong_class_or_missing_execution_is_rejected(self):
        for result in (self.good.replace("example.Fixture", "example.Other"),
                       "OK (1 test)\nINSTRUMENTATION_CODE: -1\n",
                       self.good.replace("OK (1 test)", "OK (0 tests)"),
                       self.good.replace("INSTRUMENTATION_CODE: -1", "")):
            with self.subTest(result=result), self.assertRaises(RuntimeError):
                upgrade.require_one_passing_case(result, "example.Fixture")

    def test_extra_case_and_crash_are_rejected(self):
        for extra in ("INSTRUMENTATION_STATUS_CODE: 0", "Process crashed", "FAILURES!!!"):
            with self.subTest(extra=extra), self.assertRaises(RuntimeError):
                upgrade.require_one_passing_case(self.good + extra, "example.Fixture")


class SourceHistoryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Synthetic Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.base = self.commit("base")
        self.producer = self.commit("producer snapshot")
        self.git("checkout", "--quiet", "--detach", self.base)
        self.consumer = self.commit("squashed consumer")

    def git(self, *args):
        return subprocess.run(
            ["git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *args],
            cwd=self.root, check=True, capture_output=True, text=True, timeout=20
        ).stdout.strip()

    def commit(self, message):
        self.git("commit", "--quiet", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")

    def test_squashed_consumer_does_not_require_development_ancestry(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.git("merge-base", "--is-ancestor", self.producer, self.consumer)
        upgrade.require_source_history(self.root, self.producer, self.consumer, self.base)

    def test_unrelated_source_and_symbolic_ref_are_rejected(self):
        self.git("checkout", "--quiet", "--orphan", "unrelated")
        unrelated = self.commit("unrelated root")
        for producer, consumer in ((unrelated, self.consumer),
                                   (self.producer, unrelated),
                                   (self.producer, "HEAD")):
            with self.subTest(producer=producer, consumer=consumer), self.assertRaises(RuntimeError):
                upgrade.require_source_history(self.root, producer, consumer, self.base)


if __name__ == "__main__":
    unittest.main()
