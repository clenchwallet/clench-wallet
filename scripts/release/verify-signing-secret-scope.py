#!/usr/bin/env python3
"""Read-only maintainer check: validate signing-secret metadata, never secret values."""
import argparse
import json
import subprocess

REQUIRED = frozenset({"RELEASE_KEYSTORE_BASE64", "RELEASE_KEYSTORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"})


def names(document):
    entries = document.get("secrets")
    if not isinstance(entries, list) or any(not isinstance(e, dict) or not isinstance(e.get("name"), str) for e in entries):
        raise ValueError("Invalid secrets metadata")
    total = document.get("total_count")
    if total != len(entries):
        raise ValueError("Incomplete secrets metadata; refusing a partial scope check")
    return {e["name"] for e in entries}


def validate(repository, environment):
    repo_names, env_names = names(repository), names(environment)
    errors = []
    if REQUIRED & repo_names:
        errors.append("Signing credentials still exist at repository scope: " + ", ".join(sorted(REQUIRED & repo_names)))
    if REQUIRED - env_names:
        errors.append("Protected environment is missing: " + ", ".join(sorted(REQUIRED - env_names)))
    return errors


def metadata(endpoint):
    # API returns names/timestamps only; never ask a workflow to expose the values.
    result = subprocess.run(["gh", "api", endpoint + "?per_page=100"], capture_output=True, text=True, check=True, timeout=45)
    return json.loads(result.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="clenchwallet/clench-wallet")
    args = parser.parse_args()
    if args.repo != "clenchwallet/clench-wallet":
        raise SystemExit("This release check is scoped to clenchwallet/clench-wallet")
    try:
        problems = validate(metadata(f"repos/{args.repo}/actions/secrets"),
                            metadata(f"repos/{args.repo}/environments/release-signing/secrets"))
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError, ValueError) as exc:
        raise SystemExit("Could not verify complete signing-secret metadata; scope check failed closed") from exc
    if problems:
        raise SystemExit("\n".join(problems))
    print("Signing-secret scope verified: required environment entries present; repository copies absent. Values and environment approval policy were not tested.")


if __name__ == "__main__":
    main()
