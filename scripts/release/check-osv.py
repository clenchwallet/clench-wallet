#!/usr/bin/env python3
"""Fail a release when its exact Maven SBOM has unsuppressed OSV findings."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import urllib.error
import urllib.request
from pathlib import Path


OSV_BATCH_API = "https://api.osv.dev/v1/querybatch"
MAX_RESPONSE_BYTES = 32 * 1024 * 1024


def load_purls(sbom_path: Path) -> list[str]:
    document = json.loads(sbom_path.read_text(encoding="utf-8"))
    components = document.get("components")
    if not isinstance(components, list) or not components:
        raise SystemExit("SBOM has no components to audit")
    purls = [component.get("purl") for component in components]
    if any(not isinstance(purl, str) or not purl.startswith("pkg:maven/") for purl in purls):
        raise SystemExit("SBOM contains a missing or unsupported dependency PURL")
    if len(set(purls)) != len(purls):
        raise SystemExit("SBOM contains duplicate dependency PURLs")
    return sorted(purls)


def query_osv(purls: list[str]) -> set[tuple[str, str]]:
    pending: list[tuple[str, str | None]] = [(purl, None) for purl in purls]
    findings: set[tuple[str, str]] = set()
    while pending:
        batch, pending = pending[:250], pending[250:]
        queries = []
        for purl, page_token in batch:
            query: dict[str, object] = {"package": {"purl": purl}}
            if page_token:
                query["page_token"] = page_token
            queries.append(query)
        request = urllib.request.Request(
            OSV_BATCH_API,
            data=json.dumps({"queries": queries}, separators=(",", ":")).encode(),
            headers={
                "Content-Type": "application/json",
                "User-Agent": "ClenchWallet-release-audit/1",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
        except (OSError, urllib.error.URLError) as exc:
            raise SystemExit(f"OSV audit failed closed: {exc}") from exc
        if len(raw) > MAX_RESPONSE_BYTES:
            raise SystemExit("OSV audit response exceeded the 32 MiB safety bound")
        results = json.loads(raw).get("results")
        if not isinstance(results, list) or len(results) != len(batch):
            raise SystemExit("OSV returned a malformed or incomplete batch response")
        for (purl, _), result in zip(batch, results, strict=True):
            if not isinstance(result, dict):
                raise SystemExit("OSV returned a malformed result")
            for vulnerability in result.get("vulns", []):
                vulnerability_id = vulnerability.get("id")
                if not isinstance(vulnerability_id, str) or not vulnerability_id:
                    raise SystemExit("OSV returned a vulnerability without an ID")
                findings.add((purl, vulnerability_id))
            next_token = result.get("next_page_token")
            if next_token:
                if not isinstance(next_token, str):
                    raise SystemExit("OSV returned a malformed page token")
                pending.append((purl, next_token))
    return findings


def load_allowlist(path: Path) -> dict[tuple[str, str], str]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schemaVersion") != 1 or not isinstance(document.get("entries"), list):
        raise SystemExit("OSV allowlist schema is invalid")
    today = dt.datetime.now(dt.timezone.utc).date()
    allowed: dict[tuple[str, str], str] = {}
    for entry in document["entries"]:
        if not isinstance(entry, dict):
            raise SystemExit("OSV allowlist entry is invalid")
        purl, vulnerability_id = entry.get("purl"), entry.get("id")
        reason, expires = entry.get("reason"), entry.get("expires")
        if not all(isinstance(value, str) and value for value in (purl, vulnerability_id, reason, expires)):
            raise SystemExit("OSV allowlist entry has a missing field")
        if not purl.startswith("pkg:maven/") or len(reason) < 20:
            raise SystemExit("OSV allowlist entry lacks a Maven PURL or meaningful rationale")
        try:
            expiry = dt.date.fromisoformat(expires)
        except ValueError as exc:
            raise SystemExit("OSV allowlist expiry is not YYYY-MM-DD") from exc
        if expiry < today:
            raise SystemExit(f"OSV allowlist entry expired: {vulnerability_id} for {purl}")
        key = (purl, vulnerability_id)
        if key in allowed:
            raise SystemExit("OSV allowlist contains a duplicate entry")
        allowed[key] = reason
    return allowed


def audit(sbom: Path, allowlist_path: Path) -> None:
    findings = query_osv(load_purls(sbom))
    allowed = load_allowlist(allowlist_path)
    stale = set(allowed) - findings
    if stale:
        raise SystemExit(
            "OSV allowlist contains stale entries: "
            + ", ".join(f"{item[1]} ({item[0]})" for item in sorted(stale))
        )
    unapproved = findings - set(allowed)
    if unapproved:
        raise SystemExit(
            "Release SBOM has unsuppressed OSV findings: "
            + ", ".join(f"{item[1]} ({item[0]})" for item in sorted(unapproved))
        )
    print(
        f"OSV audit passed for {len(load_purls(sbom))} exact Maven components "
        f"with {len(allowed)} active, reviewed exceptions."
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sbom")
    parser.add_argument(
        "--allowlist",
        default="scripts/release/osv-allowlist.json",
    )
    args = parser.parse_args()
    audit(Path(args.sbom), Path(args.allowlist))


if __name__ == "__main__":
    main()
