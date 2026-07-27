#!/usr/bin/env python3
"""Fail closed if release-key isolation or release workflow boundaries regress."""

from __future__ import annotations

import os
import re
import subprocess
from pathlib import Path


SENSITIVE_SUFFIXES = (".jks", ".keystore", ".p12", ".pfx")
RELEASE_SECRET_NAMES = (
    "RELEASE_KEYSTORE_BASE64",
    "RELEASE_KEYSTORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)


def tracked_files() -> list[str]:
    output = subprocess.check_output(["git", "ls-files", "-z"])
    return [item.decode("utf-8") for item in output.split(b"\0") if item]


def job_blocks(workflow: str) -> dict[str, str]:
    jobs_offset = workflow.find("\njobs:\n")
    if jobs_offset < 0:
        raise SystemExit("Release workflow has no jobs block")
    jobs_text = workflow[jobs_offset + len("\njobs:\n") :]
    matches = list(re.finditer(r"(?m)^  ([A-Za-z0-9_-]+):\s*$", jobs_text))
    blocks: dict[str, str] = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(jobs_text)
        blocks[match.group(1)] = jobs_text[match.start() : end]
    return blocks


def main() -> None:
    tracked = tracked_files()
    sensitive = [
        path
        for path in tracked
        if path == "keystore.properties" or path.lower().endswith(SENSITIVE_SUFFIXES)
    ]
    if sensitive:
        raise SystemExit("Signing material is tracked by Git: " + ", ".join(sensitive))

    ignore = Path(".gitignore").read_text(encoding="utf-8")
    for required in (
        "keystore.properties",
        "**/*.jks",
        "**/*.keystore",
        "**/*.p12",
        "**/*.pfx",
    ):
        if required not in ignore:
            raise SystemExit(f".gitignore does not protect {required}")

    if os.environ.get("CLENCH_REQUIRE_NO_LOCAL_SIGNING_MATERIAL") == "1":
        local = [
            path
            for path in Path(".").rglob("*")
            if path.is_file()
            and (
                path.name == "keystore.properties"
                or path.name.lower().endswith(SENSITIVE_SUFFIXES)
            )
            and ".git" not in path.parts
        ]
        if local:
            raise SystemExit(
                "Independent verification checkout contains local signing material: "
                + ", ".join(str(path) for path in local)
            )

    wrapper = Path("gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    if not re.search(r"(?m)^distributionSha256Sum=[0-9a-f]{64}$", wrapper):
        raise SystemExit("Gradle wrapper distribution is not pinned by SHA-256")

    workflow_files = sorted(Path(".github/workflows").glob("*.yml"))
    for workflow_file in workflow_files:
        source = workflow_file.read_text(encoding="utf-8")
        actions = re.findall(r"(?m)^\s*(?:-\s*)?uses:\s*([^\s#]+)", source)
        for action in actions:
            if not re.fullmatch(r"[^@]+@[0-9a-f]{40}", action):
                raise SystemExit(
                    f"Workflow action is not pinned by full commit SHA: "
                    f"{workflow_file}: {action}"
                )
    for required_wrapper_lane in ("android.yml", "codeql.yml", "fuzz.yml", "release.yml"):
        source = Path(".github/workflows", required_wrapper_lane).read_text(
            encoding="utf-8"
        )
        if "gradle/actions/wrapper-validation@" not in source:
            raise SystemExit(
                f"Hosted build lane does not validate the wrapper JAR: "
                f"{required_wrapper_lane}"
            )

    workflow = Path(".github/workflows/release.yml").read_text(encoding="utf-8")
    blocks = job_blocks(workflow)
    required_jobs = {"build_and_sign", "verify_release", "publish"}
    if not required_jobs.issubset(blocks):
        raise SystemExit("Release workflow is missing required isolated jobs")
    if "environment: release-signing" not in blocks["build_and_sign"]:
        raise SystemExit("Signing job is not bound to the protected release environment")
    if workflow.count("gradle/actions/wrapper-validation@") < 2:
        raise SystemExit("Release build and independent verifier do not validate the wrapper JAR")
    for job in ("verify_release", "publish"):
        if "environment: release-signing" in blocks[job]:
            raise SystemExit(f"{job} must not inherit the signing environment")

    for secret in RELEASE_SECRET_NAMES:
        reference = "${{ secrets." + secret + " }}"
        if reference not in blocks["build_and_sign"]:
            raise SystemExit(f"Signing job does not require {secret}")
        for job, block in blocks.items():
            if job != "build_and_sign" and reference in block:
                raise SystemExit(f"{secret} leaked into non-signing job {job}")

    destroy_offset = blocks["build_and_sign"].find("name: Destroy signing material")
    build_offset = blocks["build_and_sign"].find("name: Build signed release")
    prepare_offset = blocks["build_and_sign"].find("name: Prepare and verify release artifacts")
    attest_offset = blocks["build_and_sign"].find("name: Attest signed APK provenance")
    upload_offset = blocks["build_and_sign"].find("name: Upload verified release bundle")
    if not (0 <= build_offset < destroy_offset < prepare_offset < attest_offset < upload_offset):
        raise SystemExit(
            "Signing material is not destroyed immediately after the signed build "
            "and before custom artifact processing"
        )
    if "needs: [build_and_sign, verify_release]" not in blocks["publish"]:
        raise SystemExit("Publication is not gated on the no-secrets verification job")
    if "--deny-self-hosted-runners" not in blocks["verify_release"]:
        raise SystemExit("Attestation verification does not reject self-hosted builders")
    if "--predicate-type https://cyclonedx.org/bom" not in blocks["verify_release"]:
        raise SystemExit("CycloneDX SBOM attestation is not independently verified")
    if "verify-sbom-attestation.py" not in blocks["verify_release"]:
        raise SystemExit("Published SBOM is not compared with the verified attestation")
    if "name: Finalize and reverify the public evidence bundle" not in blocks["verify_release"]:
        raise SystemExit("Independent verification evidence is not checksummed and reverified")
    for job, block in blocks.items():
        if "runs-on: ubuntu-24.04" not in block:
            raise SystemExit(f"{job} is not pinned to the release runner image")
        if re.search(r"(?m)^\s*runs-on:\s*(?:self-hosted|\[.*self-hosted)", block):
            raise SystemExit(f"{job} permits a self-hosted release runner")

    print("Release-key isolation and no-secrets publication controls passed.")


if __name__ == "__main__":
    main()
