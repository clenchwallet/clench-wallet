#!/usr/bin/env python3
"""Hostile self-tests for the release OSV gate."""

from __future__ import annotations

import importlib.util
import json
import tempfile
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts/release/check-osv.py"
SPEC = importlib.util.spec_from_file_location("clench_check_osv", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise SystemExit("Could not load OSV checker")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeResponse:
    def __init__(self, document: dict[str, object]):
        self.payload = json.dumps(document).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, _limit: int) -> bytes:
        return self.payload


def expect_failure(callable_, text: str) -> None:
    try:
        callable_()
    except SystemExit as exc:
        if text not in str(exc):
            raise AssertionError(f"Expected {text!r}, got {exc!r}") from exc
    else:
        raise AssertionError(f"Expected failure containing {text!r}")


def main() -> None:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        sbom = root / "sbom.json"
        allowlist = root / "allowlist.json"
        sbom.write_text(
            json.dumps(
                {
                    "components": [
                        {"purl": "pkg:maven/org.example/example@1.0"}
                    ]
                }
            ),
            encoding="utf-8",
        )
        allowlist.write_text(
            json.dumps({"schemaVersion": 1, "entries": []}),
            encoding="utf-8",
        )

        clean = FakeResponse({"results": [{}]})
        with mock.patch.object(MODULE.urllib.request, "urlopen", return_value=clean):
            MODULE.audit(sbom, allowlist)

        vulnerable = FakeResponse(
            {"results": [{"vulns": [{"id": "GHSA-test-test-test"}]}]}
        )
        with mock.patch.object(MODULE.urllib.request, "urlopen", return_value=vulnerable):
            expect_failure(
                lambda: MODULE.audit(sbom, allowlist),
                "unsuppressed OSV findings",
            )

        allowlist.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "entries": [
                        {
                            "purl": "pkg:maven/org.example/example@1.0",
                            "id": "GHSA-test-test-test",
                            "reason": "Reviewed test-only unreachable path.",
                            "expires": "2999-01-01",
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        with mock.patch.object(MODULE.urllib.request, "urlopen", return_value=clean):
            expect_failure(lambda: MODULE.audit(sbom, allowlist), "stale entries")

        oversized = FakeResponse({"results": [{}]})
        oversized.payload = b"x" * (MODULE.MAX_RESPONSE_BYTES + 1)
        with mock.patch.object(MODULE.urllib.request, "urlopen", return_value=oversized):
            expect_failure(lambda: MODULE.audit(sbom, allowlist), "32 MiB")

    print("OSV release-gate hostile self-tests passed.")


if __name__ == "__main__":
    main()
