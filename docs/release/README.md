# Release Trust

Clench release trust is built on four checks:

1. Source tag review.
2. Rebuild from the tagged source with locked and verified dependencies.
3. APK signature and signer-certificate verification.
4. Published SHA-256 manifests for every release artifact.

Normal CI builds are not release builds. Debug APKs are CI artifacts only and must not be treated as production wallet releases.

The signed release workflow runs only for `v*` tags or explicit maintainer dispatch. It requires these GitHub Actions secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Release documentation:

- [Reproducible builds](reproducible-builds.md)
- [Signed-release verification](signed-release-verification.md)

Security review documentation:

- [Threat model](../security/threat-model.md)
- [Audit path](../security/audit-path.md)
