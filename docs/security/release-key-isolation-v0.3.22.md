# Release-Key Isolation Review — v0.3.22

Reviewed live and in source on 2026-07-27. No signing key, password, token, secret value, seed, or private-key material is recorded here.

## Enforced source controls

| Control | Status | Evidence |
| --- | --- | --- |
| Signing material excluded from Git | PASS | `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, and `*.pfx` are ignored; `verify-release-controls.py` rejects tracked signing material. |
| Signing secrets scoped to one job | PASS | Only `build_and_sign` references the four release signing secrets and the `release-signing` environment. |
| Tests before key load | PASS | Unit/property suites and lint run before the workflow checks or restores signing secrets. |
| Hosted supply-chain entry points pinned | PASS | Every third-party action is pinned by a full commit SHA; every Gradle/CodeQL/fuzz/release lane validates the wrapper JAR and the wrapper pins the Gradle distribution SHA-256. |
| Key material destroyed immediately after signing | PASS | `Destroy signing material` is an `always()` step directly after the Gradle signed build and before version parsing, custom artifact processing, provenance, SBOM attestation, or upload. |
| Independent verifier has no signing environment | PASS | `verify_release` uses a separate runner, requires no local signing files, produces an unsigned APK, and verifies that it is unsigned. |
| Publisher has no signing environment | PASS | `publish` receives only the independently verified artifact bundle and repository contents permission. |
| No self-hosted release runner | PASS | All release jobs are pinned to GitHub-hosted `ubuntu-24.04`; attestation verification denies self-hosted builders. |
| Established signer continuity | PASS | Bundle and independent verifiers require one signer and certificate SHA-256 `d161d82d633347948079cb5bbae0560c2f85622a51c69f3b4a0d283eefc853ca`. |

## Live repository controls

| Control | Status | Evidence / residual |
| --- | --- | --- |
| Protected release environment | PASS | `release-signing` exists and requires reviewer `clenchwallet`. |
| Environment self-review prevention | RESIDUAL | GitHub reports `prevent_self_review=false`. |
| Administrator environment bypass | RESIDUAL | GitHub reports `can_admins_bypass=true`. |
| Protected `master` | PASS WITH SINGLE-MAINTAINER LIMIT | Active rules prevent deletion/non-fast-forward, require linear PR history, resolved review threads, and passing `build` plus `analyze`; no approving review is required. |
| Protected `v*` tags | PASS | Active tag rules prevent deletion and non-fast-forward updates; the workflow separately requires an annotated GitHub-verified cryptographic tag signature. |

## Local machine observation

The maintainer workstation contains ignored, mode-0600 signing configuration/key files. They were not read beyond path/mode/reference checks, were not copied, and are not used by the candidate's independent build. Their presence is expected for an authorized maintainer rebuild but intentionally causes `CLENCH_REQUIRE_NO_LOCAL_SIGNING_MATERIAL=1` to fail in that checkout.

## Governance gate

The release key is technically isolated from tests, verification, publication, and source control. Approval governance is not independent because the project currently has one configured environment reviewer who may self-review, and administrators may bypass.

Do not silently enable `prevent_self_review` or require a PR approval until a second trusted maintainer is configured; doing so could make releases impossible. For v0.3.22, require an explicit recorded maintainer ship authorization after all technical gates. A future two-person release process should add a distinct trusted environment reviewer, enable self-review prevention, disable administrator bypass where operationally safe, and require one non-author PR approval.
