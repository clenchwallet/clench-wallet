# Reproducible Builds

Clench uses two independent no-secrets builds and a source-free signing step:

1. `validate_source` runs the trusted workflow from protected `master`, verifies the tag against the pinned SSH key, and requires the tag to equal current protected `master`.
2. `build_unsigned` tests the approved source and produces a checksummed unsigned APK and deterministic SBOM without signing credentials.
3. The protected `sign_release` job checks out no source and executes no Gradle or repository script. It verifies the unsigned inputs and pinned `apksigner`, signs the prebuilt APK, and destroys the temporary keystore immediately.
4. A separate `verify_release` runner checks out the immutable commit already approved by the signed-tag gate, with no signing material, strictly rebuilds the unsigned APK, and compares every ZIP payload entry with the signed APK.

Only the APK signing block and actual v1 `META-INF/MANIFEST.MF` plus
certificate/signature records may differ. Resources, DEX, native libraries,
manifest, relative payload order, timestamps, ZIP attributes, local headers,
compressed bytes, archive comment, and every other entry must match exactly.

## Pinned inputs

- Runner image: Ubuntu 24.04.
- JDK: Temurin 21.
- Android SDK: compile SDK 36.
- Android Gradle Plugin and plugins: `gradle/libs.versions.toml`.
- Gradle distribution and SHA-256: `gradle/wrapper/gradle-wrapper.properties`.
- Gradle wrapper JAR: GitHub-hosted wrapper validation in every build/fuzz/release lane.
- Release dependency graph: `app/gradle.lockfile`.
- Settings/plugin dependency graph: `settings-gradle.lockfile`.
- Artifact integrity: `gradle/verification-metadata.xml`.
- Release source: an annotated tag signed by the public key pinned in `.github/release-signers.allowed` and resolving exactly to protected `master`.

The release jobs use `--dependency-verification=strict`. Dependency locks and verification metadata must never be regenerated implicitly during a release.

The build and independent verifier also query the official OSV batch API for every exact Maven PURL in the deterministic SBOM. Network/API failure, malformed responses, new findings, expired exceptions, and stale exceptions all block release. Any temporary exception must identify the exact PURL and advisory, contain a meaningful reachability rationale, and expire in `scripts/release/osv-allowlist.json`.

## Independent no-secrets rebuild

Use a fresh standalone clone of the exact signed tag. It must contain neither
`keystore.properties` nor any keystore:

```bash
git fetch --tags origin
git clone --no-checkout https://github.com/clench-wallet/clench-wallet.git ../clench-verify
cd ../clench-verify
git checkout --detach vX.Y.Z
scripts/release/rebuild-unsigned.sh
```

The script fails on a linked Git worktree, dirty checkout, tracked signing
material, or any local signing material. Android Gradle Plugin does not resolve
version-control metadata from the `.git` indirection file used by linked
worktrees; accepting one would replace the source revision in the APK with
`NO_VALID_GIT_FOUND` and destroy reproducibility. The script therefore requires
a standalone `.git` directory and verifies the exact source revision embedded
in the APK. It discovers AGP's single release APK without assuming whether the
filename includes `-unsigned`, copies it to a stable verification path, and
requires `apksigner verify` to fail for that file.

To compare it with the published signed APK:

```bash
VERSION=X.Y.Z \
VERSION_CODE=EXPECTED_CODE \
EXPECTED_RELEASE_SIGNER_SHA256=d161d82d633347948079cb5bbae0560c2f85622a51c69f3b4a0d283eefc853ca \
scripts/release/verify-independent-apk.sh \
  release-artifacts \
  app/build/outputs/apk/release/app-release.apk \
  release-artifacts/INDEPENDENT-APK-VERIFICATION.json
```

The JSON report records both whole-file hashes, the number of compared entries,
and a digest of the canonical payload-and-ZIP-metadata inventory. The report
validator independently reconstructs that inventory from the signed APK.

Exercise the verifier itself without signing material:

```bash
python3 -B scripts/verification/test-release-tools.py
```

This synthetic hostile suite requires rejection of payload and compression
changes, duplicate/traversal ZIP entries, misleading non-signature
`META-INF/*.MF` files, truncated SBOM inventories, and incomplete provenance.

## Maintainer signed rebuild

`scripts/release/reproducible-build.sh` is for an authorized maintainer who possesses the established release key. It refuses dirty source and requires an untracked `keystore.properties`. Never use it on an independent verifier or expose its key material to CI jobs other than the protected signing job.

Signing-key access is not needed to establish payload reproducibility.

## SBOM and provenance

Each release publishes:

- `clench-X.Y.Z-sbom.cdx.json`: deterministic CycloneDX 1.6 inventory of the locked release runtime, including artifact SHA-256 evidence from Gradle verification metadata.
- `PROVENANCE.intoto.jsonl`: deterministic in-toto Statement v1 / SLSA provenance v1 binding APK and SBOM to the source, tag, toolchain, locks, workflow, and verifier scripts.
- GitHub Sigstore build-provenance and CycloneDX SBOM attestations for the signed APK.
- `INDEPENDENT-APK-VERIFICATION.json`: separate-runner unsigned payload comparison evidence.

Generate the SBOM twice and compare it byte-for-byte:

```bash
COMMIT=$(git rev-parse HEAD)
scripts/release/generate-sbom.py --commit "$COMMIT" --output /tmp/clench-sbom-1.json
scripts/release/generate-sbom.py --commit "$COMMIT" --output /tmp/clench-sbom-2.json
cmp /tmp/clench-sbom-1.json /tmp/clench-sbom-2.json
```

## Mismatch handling

Any payload mismatch blocks publication. Record:

- Source tag and commit.
- Signed and unsigned APK hashes.
- Runner image, JDK, Gradle distribution and checksum.
- Android SDK/build-tools versions.
- Dependency lock and verification-metadata hashes.
- First missing, unexpected, or changed APK entries.

Do not expand the exclusion list to make a mismatch pass. Find and remove the nondeterministic input, then rebuild both sides.
