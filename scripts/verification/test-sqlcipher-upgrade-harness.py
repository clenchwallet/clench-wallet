#!/usr/bin/env python3
"""Finite, device-free acceptance-parser regressions for the upgrade fixture."""
import importlib.util
from pathlib import Path
import unittest

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


if __name__ == "__main__":
    unittest.main()
