#!/usr/bin/env python3
"""Hostile self-tests for the release OSV gate."""

from __future__ import annotations

import importlib.util
import copy
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

        allowlist.write_text(json.dumps({"schemaVersion": 1, "entries": []}))
        upstream = "pkg:maven/org.bitcoindevkit/bdk-android@3.0.0"
        rebuilt = upstream + "-clench.1"
        component = {
            "purl": rebuilt,
            "pedigree": {"ancestors": [{
                "type": "library", "group": "org.bitcoindevkit", "name": "bdk-android",
                "version": "3.0.0", "purl": upstream,
                "hashes": [{"alg": "SHA-256", "content": "e11f099ab3f7acce9770825d9f431ce0970a30356c46ebed6aef66594491bb1e"}],
            }]},
        }
        sbom.write_text(json.dumps({"components": [component]}))
        assert MODULE.load_purls(sbom) == sorted([upstream, rebuilt])

        def upstream_only_finding(request, timeout):
            queries = json.loads(request.data)["queries"]
            assert {q["package"]["purl"] for q in queries} == {upstream, rebuilt}
            return FakeResponse({"results": [
                {"vulns": [{"id": "GHSA-upstream-wrapper-issue"}]} if q["package"]["purl"] == upstream else {}
                for q in queries
            ]})

        with mock.patch.object(MODULE.urllib.request, "urlopen", side_effect=upstream_only_finding):
            expect_failure(lambda: MODULE.audit(sbom, allowlist), "GHSA-upstream-wrapper-issue")

        for mutation in ("missing", "wrong-version", "wrong-hash", "extra-ancestor"):
            altered = copy.deepcopy(component)
            if mutation == "missing":
                del altered["pedigree"]
            elif mutation == "wrong-version":
                altered["pedigree"]["ancestors"][0]["purl"] = upstream.replace("3.0.0", "999.0.0")
            elif mutation == "wrong-hash":
                altered["pedigree"]["ancestors"][0]["hashes"][0]["content"] = "0" * 64
            else:
                altered["pedigree"]["ancestors"].append(copy.deepcopy(altered["pedigree"]["ancestors"][0]))
            sbom.write_text(json.dumps({"components": [altered]}))
            expect_failure(lambda: MODULE.load_purls(sbom), "exact pinned upstream wrapper pedigree")
        component["purl"] = upstream + "-clench.2"
        sbom.write_text(json.dumps({"components": [component]}))
        expect_failure(lambda: MODULE.load_purls(sbom), "upstream advisory mapping is required")

    print("OSV release-gate hostile self-tests passed.")


if __name__ == "__main__":
    main()
