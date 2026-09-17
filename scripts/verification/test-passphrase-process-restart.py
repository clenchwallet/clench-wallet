#!/usr/bin/env python3
"""Device-free fail-closed result parsing tests; these do not claim Android execution."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("restart", Path(__file__).with_name("run-passphrase-process-restart.py"))
restart = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restart)


class ResultTests(unittest.TestCase):
    good = "\n".join((
        "INSTRUMENTATION_STATUS: class=" + restart.CLASS,
        "INSTRUMENTATION_STATUS_CODE: 1",
        "INSTRUMENTATION_STATUS: clenchPassphraseRestartMarker=CLENCH_PASSPHRASE_RESTART_WRITE_PASS",
        "INSTRUMENTATION_STATUS: clenchPassphraseProcess=208b35c1-bc99-48d2-9589-ebc9b482af99",
        "INSTRUMENTATION_STATUS: clenchPassphrasePid=12345",
        "INSTRUMENTATION_STATUS_CODE: 2",
        "INSTRUMENTATION_STATUS: class=" + restart.CLASS,
        "INSTRUMENTATION_STATUS_CODE: 0", "OK (1 test)", "INSTRUMENTATION_CODE: -1", ""
    ))

    def test_single_pass_with_distinct_phase_evidence(self):
        for result in (self.good, self.good.replace("\n", "\r\n")):
            evidence = restart.require_phase_pass(result, "write")
            self.assertEqual(12345, evidence["pid"])
            self.assertEqual("208b35c1-bc99-48d2-9589-ebc9b482af99", evidence["process"])
        evidence = restart.require_phase_pass(self.good.replace("_WRITE_PASS", "_VERIFY_PASS"), "verify")
        self.assertEqual("CLENCH_PASSPHRASE_RESTART_VERIFY_PASS", evidence["marker"])

    def test_skip_failure_incomplete_and_extra_execution_rejected(self):
        for result in (
            *(self.good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: " + code) for code in ("-2", "-3", "-4", "")),
            self.good.replace("OK (1 test)", "OK (0 tests)"),
            self.good.replace("INSTRUMENTATION_CODE: -1", ""),
            self.good + "INSTRUMENTATION_STATUS_CODE: 0\n",
            self.good + "Process crashed\n",
            self.good.replace(restart.CLASS, "other.Fixture"),
        ):
            with self.subTest(result=result), self.assertRaises(RuntimeError):
                restart.require_phase_pass(result, "write")

    def test_wrong_missing_and_repeated_markers_rejected(self):
        for result in (
            self.good.replace("_WRITE_PASS", "_VERIFY_PASS"),
            self.good.replace("clenchPassphraseRestartMarker", "otherMarker"),
            self.good + "INSTRUMENTATION_STATUS: clenchPassphraseRestartMarker=CLENCH_PASSPHRASE_RESTART_WRITE_PASS\n",
            self.good.replace("clenchPassphraseProcess", "otherProcess"),
            self.good.replace("208b35c1-bc99-48d2-9589-ebc9b482af99", "not-a-process-uuid"),
            self.good.replace("clenchPassphrasePid=12345", "clenchPassphrasePid=0"),
        ):
            with self.subTest(result=result), self.assertRaises((RuntimeError, ValueError)):
                restart.require_phase_pass(result, "write")


if __name__ == "__main__":
    unittest.main()
