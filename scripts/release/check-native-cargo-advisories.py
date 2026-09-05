#!/usr/bin/env python3
"""Scan pinned upstream Cargo candidates; NOT an inventory of shipped native code."""

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import tomllib
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OWNER = "pkg:maven/org.bitcoindevkit/bdk-android@3.0.0"
SOURCE = "https://github.com/bitcoindevkit/bdk-ffi/blob/cfb3418524d451ba8d1758f0ec27f8443740b422/bdk-ffi/Cargo.lock"
REGISTRY = "registry+https://github.com/rust-lang/crates.io-index"
MAX_LOCK_BYTES = 1024 * 1024


def canonical_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def application_hash(root):
    """Bind a call-path review to all production inputs, including newly added files."""
    root = Path(root)
    source_root = root / "app/src"
    paths = sorted(p for p in source_root.rglob("*")
                   if p.relative_to(source_root).parts[0] not in ("test", "androidTest"))
    paths += [root / p for p in ("app/build.gradle.kts", "app/gradle.lockfile", "app/proguard-rules.pro")]
    entries = []
    for path in paths:
        if path.is_symlink():
            raise ValueError("Symlink in reviewed application inputs")
        if path.is_file():
            entries.append([path.relative_to(root).as_posix(), hashlib.sha256(path.read_bytes()).hexdigest()])
    if len(entries) < 4:
        raise ValueError("Missing application review inputs")
    return canonical_hash(sorted(entries))


def advisory_document(advisory_id):
    # IDs come from the reviewed document, never an arbitrary URL.
    if not advisory_id or any(c not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-" for c in advisory_id):
        raise ValueError("Invalid advisory ID")
    request = urllib.request.Request("https://api.osv.dev/v1/vulns/" + advisory_id,
                                     headers={"User-Agent": "ClenchWallet-native-review/1"})
    with urllib.request.urlopen(request, timeout=45) as response:
        raw = response.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024:
        raise ValueError("Advisory document exceeds bound")
    document = json.loads(raw)
    if document.get("id") != advisory_id:
        raise ValueError("Advisory identity mismatch")
    return document


def disposition_results(document, owner, lock_digest, findings, root, *, today=None,
                        fetch_advisory=advisory_document):
    """No package-wide suppression: exact inputs, exact IDs, live content and short expiry."""
    today = today or dt.datetime.now(dt.timezone.utc).date()
    if document.get("schema_version") != 1:
        raise ValueError("Invalid native disposition schema")
    if (document.get("owner_purl") != OWNER or
            document.get("owner_artifact_sha256") != owner["artifact_sha256"] or
            document.get("source_lock_sha256") != lock_digest or
            document.get("application_sha256") != application_hash(root)):
        raise ValueError("Native disposition input binding changed; re-review required")
    reviewed = dt.date.fromisoformat(document["reviewed_on"])
    expires = dt.date.fromisoformat(document["expires_on"])
    if not reviewed <= today <= expires or not 0 < (expires - reviewed).days <= 30:
        raise ValueError("Native disposition expired or invalid review interval")
    evidence = document.get("evidence", [])
    if not evidence:
        raise ValueError("Native disposition has no evidence")
    for item in evidence:
        path = Path(item["path"])
        if path.is_absolute() or ".." in path.parts or not path.as_posix().startswith("docs/security/"):
            raise ValueError("Invalid native evidence path")
        target = Path(root) / path
        if target.is_symlink() or hashlib.sha256(target.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError("Native disposition evidence changed")
    results = []
    seen = set()
    entries = document.get("entries", [])
    if not isinstance(entries, list):
        raise ValueError("Invalid native disposition entries")
    for entry in entries:
        key = (entry["purl"], entry["id"])
        if key in seen or not key[0].startswith("pkg:cargo/"):
            raise ValueError("Duplicate or invalid native disposition")
        seen.add(key)
        if key not in findings:
            raise ValueError("Stale native disposition; advisory no longer reported")
        if entry.get("status") != "not_affected_reviewed_call_path" or len(entry.get("reason", "")) < 80:
            raise ValueError("Missing explicit native applicability rationale")
        if canonical_hash(fetch_advisory(key[1])) != entry["advisory_sha256"]:
            raise ValueError("Advisory content changed; applicability re-review required")
        results.append(entry)
    return results, sorted(set(findings) - seen)


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
    parser.add_argument("--dispositions", type=Path, default=ROOT / "docs/security/native-cargo-dispositions.json")
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
    # Preserve raw findings even when review validation or a network request fails.
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Queried {len(purls)} Cargo candidates; {len(findings)} advisory IDs (aliases may overlap).")
    reviewed, unresolved = disposition_results(json.loads(args.dispositions.read_text()), owner,
                                               digest, findings, ROOT)
    report["reviewed_dispositions"] = reviewed
    report["unresolved_findings"] = [{"purl": p, "id": i} for p, i in unresolved]
    report["disposition_limitations"] = "Source-call-path applicability review; not patched versions, binary reproducibility or C/native clearance."
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    if unresolved:
        raise SystemExit("Unreviewed native advisories block release")
    print(f"Native applicability gate passed: {len(reviewed)} exact reviewed IDs, no unresolved IDs. Not a native clearance.")


if __name__ == "__main__":
    main()
