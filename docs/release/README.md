# Release Trust

Clench release trust is built on eight checks:

1. Source tag review.
2. Rebuild from the tagged source with locked and verified dependencies.
3. APK signature and signer-certificate verification.
4. Published SHA-256 manifests for every release artifact.
5. A deterministic CycloneDX SBOM with Gradle-verified artifact hashes.
6. Sigstore provenance and exact-SBOM attestations bound to the signed tag.
7. A separate no-secrets unsigned rebuild whose APK payload matches the signed artifact.
8. A fail-closed OSV audit of every exact Maven component in the release SBOM, with expiring reviewed exceptions only.

Normal CI builds are not release builds. Debug APKs are CI artifacts only and must not be treated as production wallet releases.

The signed release workflow is dispatched explicitly from protected `master` with a `v*` tag as input. It verifies the annotated tag against the pinned maintainer public key and requires the tag to equal current protected `master` before any signing job can run. The isolated signer receives only the checksummed unsigned APK/evidence—not a source checkout or Gradle build—and requires these GitHub Actions secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Release documentation:

- [Reproducible builds](reproducible-builds.md)
- [Signed-release verification](signed-release-verification.md)
- [Release tag signing](tag-signing.md)
- [Verification laboratory](../verification/README.md)
- [Current release gate](../qa/v0.3.25-release-gate.md)
- [Current physical-hardware gates](../qa/physical-hardware-gates-v0.3.25.md)
- [v0.3.22 release-key isolation review](../security/release-key-isolation-v0.3.22.md)

Security review documentation:

- [v0.3.24 security review (basis for v0.3.25)](../security/security-review-v0.3.24.md)
- [Threat model](../security/threat-model.md)
- [Security hardening](../security/security-hardening.md)
- [Audit path](../security/audit-path.md)
- [Manual test plan](../qa/manual-test-plan.md)
