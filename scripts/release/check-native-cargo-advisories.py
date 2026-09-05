#!/usr/bin/env python3
"""Scan pinned upstream Cargo candidates; NOT an inventory of shipped native code."""

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import tomllib

ROOT = Path(__file__).resolve().parents[2]
OWNER = "pkg:maven/org.bitcoindevkit/bdk-android@3.0.0"
SOURCE = "https://github.com/bitcoindevkit/bdk-ffi/blob/cfb3418524d451ba8d1758f0ec27f8443740b422/bdk-ffi/Cargo.lock"
REGISTRY = "registry+https://github.com/rust-lang/crates.io-index"
MAX_LOCK_BYTES = 1024 * 1024


def load_candidates(lock_path, baseline_path):
    with Path(lock_path).open("rb") as handle:
        raw = handle.read(MAX_LOCK_BYTES + 1)
    if len(raw) > MAX_LOCK_BYTES:
        raise ValueError("Upstream lockfile exceeds the size bound")
    baseline = json.loads(Path(baseline_path).read_text())
    owners = [a for a in baseline["artifacts"] if a["owner_purl"] == OWNER]
    if len(owners) != 1:
        raise ValueError("Expected exactly one pinned BDK native owner")
    owner = owners[0]
    digest = hashlib.sha256(raw).hexdigest()
    evidence = [e for e in owner["source_review"]["evidence"] if e["url"] == SOURCE]
    if len(evidence) != 1 or evidence[0]["sha256"] != digest:
        raise ValueError("Upstream lockfile does not match reviewed source hash")
    document = tomllib.loads(raw.decode("utf-8"))
    packages = document.get("package", [])
    if not packages or len(packages) > 4096:
        raise ValueError("Invalid candidate package count")
    candidates = set()
    local_packages = []
    for package in packages:
        name, version, source = package["name"], package["version"], package.get("source")
        if source is None:
            # Only the reviewed root is local. Never silently omit a new source dependency.
            if name != "bdk-ffi" or version != "3.0.0":
                raise ValueError("Unreviewed source-less Cargo dependency")
            local_packages.append({"name": name, "version": version})
            continue
        if source != REGISTRY:
            raise ValueError("Unreviewed Cargo source; explicit source inventory required")
        purl = f"pkg:cargo/{name}@{version}"
        if purl in candidates:
            raise ValueError("Duplicate Cargo candidate")
        candidates.add(purl)
    if len(local_packages) != 1 or not candidates:
        raise ValueError("Expected reviewed root plus registry candidates")
    return owner, digest, sorted(candidates)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lockfile", type=Path, default=ROOT / "docs/security/upstream/bdk-ffi-3.0.0-Cargo.lock")
    parser.add_argument("--baseline", type=Path, default=ROOT / "docs/security/native-dependencies.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    owner, digest, purls = load_candidates(args.lockfile, args.baseline)
    spec = importlib.util.spec_from_file_location("clench_osv", Path(__file__).with_name("check-osv.py"))
    osv = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(osv)
    findings = sorted(osv.query_osv(purls))
    report = {
        "schema_version": 1,
        "checked_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "owner_purl": OWNER,
        "owner_artifact_sha256": owner["artifact_sha256"],
        "source_url": SOURCE,
        "source_lock_sha256": digest,
        "coverage": "Upstream registry candidates, including build/dev/conditional packages; not proof of shipped code or native C coverage.",
        "query_count": len(purls),
        "purls": purls,
        "findings": [{"purl": p, "id": i} for p, i in findings],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Queried {len(purls)} Cargo candidates; {len(findings)} advisory IDs (aliases may overlap).")
    if findings:
        raise SystemExit("Native source candidate advisories require disposition; no clearance issued")


if __name__ == "__main__":
    main()
