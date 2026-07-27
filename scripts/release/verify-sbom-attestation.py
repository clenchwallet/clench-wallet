#!/usr/bin/env python3
"""Require a verified GitHub attestation to contain the exact published SBOM."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


CYCLONEDX_PREDICATE = "https://cyclonedx.org/bom"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("verification_json")
    parser.add_argument("sbom")
    args = parser.parse_args()

    verification = json.loads(Path(args.verification_json).read_text(encoding="utf-8"))
    expected = json.loads(Path(args.sbom).read_text(encoding="utf-8"))
    if not isinstance(verification, list) or not verification:
        raise SystemExit("GitHub returned no verified SBOM attestations")

    predicates = []
    for item in verification:
        statement = item.get("verificationResult", {}).get("statement", {})
        if statement.get("predicateType") == CYCLONEDX_PREDICATE:
            predicates.append(statement.get("predicate"))
    if expected not in predicates:
        raise SystemExit("Verified GitHub SBOM attestation does not match the published SBOM")
    print("Verified GitHub attestation contains the exact published CycloneDX SBOM.")


if __name__ == "__main__":
    main()
