# Reproducible Builds

Clench uses three separate clean hosted builds and a source-free signing step:

1. `validate_source` runs the trusted workflow from protected `master`, verifies the tag against the pinned SSH key, and requires the tag to equal current protected `master`.
2. `build_unsigned` (A) tests the approved source and produces a checksummed unsigned APK and deterministic SBOM without signing credentials.
3. `build_independent_unsigned` (B) starts on a separate clean hosted runner without receiving A or any expected release artifact. Its build step has no persisted checkout credential or OIDC/artifact token. A separate no-source attestor records B's provenance only after the build completes.
4. `verify_unsigned` proves A and B are byte-identical with core file/hash tools before parsing either APK, exports that digest as an immutable job output, verifies B's attestation, and creates the pre-sign approval.
5. The protected `sign_release` job checks out no source and executes no Gradle or repository script. Immediately before restoring the key it binds both inputs to the immutable approved digest and verifies the pinned `apksigner`; it signs A with detached v4/`.idsig` generation disabled and destroys the temporary keystore immediately.
6. `build_post_sign_unsigned` (C) is another separate clean hosted build that receives no expected release artifact. Its provenance is produced by another no-source attestor. `verify_release` verifies that attestation and requires C to equal the approved raw APK byte-for-byte.
7. The verifier processes a copy of C with the same pinned `apksigner` packaging policy and a disposable RSA-4096 verifier-only key, destroys that key, then compares every ZIP entry with the production-signed APK. No entry is excluded. Publication reverifies the complete allowlisted bundle and attestations immediately before creating the release.

"Separate" here means separate GitHub-hosted jobs and clean Gradle homes with
no expected APK artifact supplied to B or C. It is not a claim that those
builders are hostile, offline, or outside GitHub's trust boundary.

The original signer-input APK and both separately rebuilt unsigned APKs must
have the same whole-file SHA-256 before normalization. After deterministic
normalization, resources, DEX, native libraries, manifest, payload order,
timestamps, ZIP attributes, local headers, compressed bytes, archive comment,
and every other ZIP entry must match exactly. The APK signing blocks contain
different certificates by design and are verified separately. V1/JAR
signature records are forbidden rather than excluded.

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
git clone --no-checkout https://github.com/clenchwallet/clench-wallet.git ../clench-verify
cd ../clench-verify
git checkout --detach vX.Y.Z
APKSIGNER_BUILD_TOOLS_VERSION=PINNED_BUILD_TOOLS_VERSION \
EXPECTED_APKSIGNER_SHA256=PINNED_APKSIGNER_LAUNCHER_SHA256 \
EXPECTED_APKSIGNER_JAR_SHA256=PINNED_APKSIGNER_JAR_SHA256 \
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

Download the complete public release evidence bundle and first run
`sha256sum -c SHA256SUMS`. The published `clench-X.Y.Z-unsigned.apk` is the
exact signer input preserved only for reproducibility evidence. It is unsigned,
is not a production application, and must never be installed. To compare a
fresh local rebuild with that evidence and the published signed APK:

```bash
VERSION=X.Y.Z \
VERSION_CODE=EXPECTED_CODE \
EXPECTED_RELEASE_SIGNER_SHA256=d161d82d633347948079cb5bbae0560c2f85622a51c69f3b4a0d283eefc853ca \
APKSIGNER_BUILD_TOOLS_VERSION=PINNED_BUILD_TOOLS_VERSION \
EXPECTED_APKSIGNER_SHA256=PINNED_APKSIGNER_LAUNCHER_SHA256 \
EXPECTED_APKSIGNER_JAR_SHA256=PINNED_APKSIGNER_JAR_SHA256 \
EXPECTED_AAPT_SHA256=PINNED_AAPT_SHA256 \
scripts/release/verify-independent-apk.sh \
  release-artifacts \
  build/independent/app-release-unsigned.apk \
  release-artifacts/INDEPENDENT-APK-VERIFICATION.json \
  release-artifacts/clench-X.Y.Z-unsigned.apk
```

The JSON report records both raw unsigned whole-file hashes and their exact
identity, the deterministic normalization policy, both comparison APK hashes,
the number of compared entries, zero exclusions, and a digest of the canonical
payload-and-ZIP-metadata inventory. The report validator independently
reconstructs that inventory from the signed APK. The public release bundle
includes `ORIGINAL-UNSIGNED-BUILD-SHA256SUMS`,
`VERIFIED-UNSIGNED-SHA256SUMS`,
`POST-SIGN-UNSIGNED-BUILD-SHA256SUMS`, and `UNSIGNED-APPROVAL.txt`. Together
they bind the exact A/B/C raw APK digest approved for the isolated signer and
the public evidence-only unsigned APK.

Exercise the verifier itself without signing material:

```bash
python3 -B scripts/verification/test-release-tools.py
```

This synthetic hostile suite requires rejection of payload and compression
changes, duplicate/traversal ZIP entries, misleading non-signature
`META-INF/*.MF` files, truncated SBOM inventories, and incomplete provenance.

Signing-key access is neither needed nor permitted for these local
reproducibility checks. Production APK signing occurs only in the protected,
isolated GitHub signing job.

## SBOM and provenance

Reproducibility-specific public assets include (see
[signed-release-verification.md](signed-release-verification.md) for the exact
complete 13-file bundle):

- `clench-X.Y.Z-sbom.cdx.json`: deterministic CycloneDX 1.6 inventory of the locked release runtime, including artifact SHA-256 evidence from Gradle verification metadata.
- `PROVENANCE.intoto.jsonl`: deterministic in-toto Statement v1 / SLSA provenance v1 binding APK and SBOM to the source, tag, toolchain, locks, workflow, and verifier scripts.
- GitHub Sigstore build-provenance and CycloneDX SBOM attestations for the signed APK.
- `INDEPENDENT-APK-VERIFICATION.json`: separate-runner raw and normalized APK comparison evidence.
- `clench-X.Y.Z-unsigned.apk`: the unsigned signer input, published only as non-installable reproducibility evidence.
- `ORIGINAL-UNSIGNED-BUILD-SHA256SUMS`, `VERIFIED-UNSIGNED-SHA256SUMS`, and `POST-SIGN-UNSIGNED-BUILD-SHA256SUMS`: checksum evidence binding the three no-secrets builds to the same raw APK.
- `UNSIGNED-APPROVAL.txt`: the version, source commit, and immutable raw-APK approval digest.

Separate no-source jobs attest B and C after their builds complete. The final
verification job attests every public bundle subject; the signed APK also has
build-provenance and exact CycloneDX SBOM attestations. The publisher verifies
all required subjects immediately before release creation.

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

There is no exclusion list. Do not omit an entry or metadata field to make a
mismatch pass. Explain and deterministically reproduce any permitted packaging
transformation, or find and remove the nondeterministic input, then rebuild
both sides.
