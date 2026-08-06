#!/usr/bin/env python3
"""Hostile self-test for the deterministic fuzz wrapper's execution contract."""

from __future__ import annotations

import os
from pathlib import Path
import stat
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts" / "verification" / "run-hostile-fuzz.sh"
REPORT = Path(
    "app/build/test-results/testDebugUnitTest/"
    "TEST-net.clench.wallet.verification.HostileFuzzExecutionContractTest.xml"
)


FAKE_GRADLEW = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import sys

report = Path(
    "app/build/test-results/testDebugUnitTest/"
    "TEST-net.clench.wallet.verification.HostileFuzzExecutionContractTest.xml"
)
args = sys.argv[1:]
if report.exists() and "--rerun-tasks" not in args:
    print("> Task :app:testDebugUnitTest UP-TO-DATE")
    raise SystemExit(0)

if "--no-build-cache" not in args or "--rerun-tasks" not in args:
    print("required Gradle execution controls are missing", file=sys.stderr)
    raise SystemExit(3)

cases = os.environ["CLENCH_FUZZ_CASES"]
report.parent.mkdir(parents=True, exist_ok=True)
report.write_text(
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<testsuite name="net.clench.wallet.verification.HostileFuzzExecutionContractTest" '
    'tests="1" skipped="0" failures="0" errors="0">\n'
    f'<system-out><![CDATA[CLENCH_HOSTILE_FUZZ_EXECUTED={cases};\n]]></system-out>\n'
    '</testsuite>\n',
    encoding="utf-8",
)
print("> Task :app:testDebugUnitTest")
'''


def write_executable(path: Path, source: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


def run_lane(root: Path, cases: int, *, expect_success: bool) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [str(root / "scripts" / "verification" / "run-hostile-fuzz.sh"), str(cases)],
        cwd=root,
        env={**os.environ, "CLENCH_FUZZ_CASES": str(cases)},
        text=True,
        capture_output=True,
        check=False,
    )
    if expect_success and result.returncode != 0:
        raise AssertionError(result.stdout + result.stderr)
    if not expect_success and result.returncode == 0:
        raise AssertionError("Hostile runner mutation unexpectedly passed")
    return result


def prepare(root: Path, runner_source: str) -> None:
    write_executable(root / "gradlew", FAKE_GRADLEW)
    write_executable(
        root / "scripts" / "verification" / "run-hostile-fuzz.sh",
        runner_source,
    )


def main() -> None:
    runner_source = RUNNER.read_text(encoding="utf-8")
    for required in (
        "--no-build-cache",
        "--rerun-tasks",
        "HostileFuzzExecutionContractTest",
        "CLENCH_HOSTILE_FUZZ_EXECUTED",
    ):
        if required not in runner_source:
            raise AssertionError(f"Hostile fuzz runner is missing {required}")

    with tempfile.TemporaryDirectory(prefix="clench-hostile-runner-") as temp:
        root = Path(temp)
        prepare(root, runner_source)
        first = run_lane(root, 5000, expect_success=True)
        second = run_lane(root, 64, expect_success=True)
        if "UP-TO-DATE" in first.stdout or "UP-TO-DATE" in second.stdout:
            raise AssertionError("A requested hostile fuzz run was treated as up-to-date")
        report = (root / REPORT).read_text(encoding="utf-8")
        if "CLENCH_HOSTILE_FUZZ_EXECUTED=64;" not in report:
            raise AssertionError("The second requested execution count was not recorded")

    mutant_source = runner_source.replace("  --rerun-tasks \\\n", "", 1)
    if mutant_source == runner_source:
        raise AssertionError("Self-test could not construct the missing-rerun mutation")
    with tempfile.TemporaryDirectory(prefix="clench-hostile-runner-mutant-") as temp:
        root = Path(temp)
        prepare(root, mutant_source)
        # Let the fake Gradle accept the first mutant run so it can model a stale report.
        fake = (root / "gradlew").read_text(encoding="utf-8")
        fake = fake.replace(
            'if "--no-build-cache" not in args or "--rerun-tasks" not in args:',
            'if "--no-build-cache" not in args:',
        )
        write_executable(root / "gradlew", fake)
        run_lane(root, 5000, expect_success=True)
        stale = run_lane(root, 64, expect_success=False)
        if "UP-TO-DATE" not in stale.stdout:
            raise AssertionError("Mutation did not model the stale UP-TO-DATE failure")
        if "did not execute the requested 64 cases" not in stale.stderr:
            raise AssertionError("Runner did not fail closed on the stale 5000-case report")

    print("Hostile fuzz runner execution-contract self-tests passed.")


if __name__ == "__main__":
    main()
