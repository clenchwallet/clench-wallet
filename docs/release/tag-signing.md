# Release Tag Signing

Clench production releases require an annotated tag signed by the maintainer key pinned in `.github/release-signers.allowed`. The tag must resolve to the exact current commit of protected `master`. Never weaken `.github/workflows/release.yml` to accept a lightweight tag, another signing key, or an off-branch commit.

## Maintainer prerequisites

The maintainer must control the pinned SSH signing key. Keep the private key outside the repository and never paste it into an issue, pull request, chat, workflow input, or GitHub Actions secret used for APK signing. Rotating this key requires a separately reviewed protected-master change to the allowed-signers file before creating a release tag.

For SSH signing:

1. Create or select a dedicated signing key on the maintainer workstation.
2. Add only its public key to the maintainer's GitHub **SSH and GPG keys → Signing keys** settings.
3. Configure Git to use SSH signing and the public-key path:

```bash
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/clench_release_signing.pub
git config --global tag.gpgSign true
```

Use an encrypted private key loaded through the operating-system key agent. Do not create an unencrypted private key for automation convenience.

## Pre-tag checks

Before creating `vX.Y.Z`:

```bash
git switch master
git pull --ff-only origin master
git status --short
./gradlew --no-daemon --dependency-verification=strict clean testDebugUnitTest lintDebug assembleRelease
```

Confirm the release gate in `docs/qa/` has no `BLOCKED`, `FAILED`, or required `PENDING` entries. Confirm `versionName`, `versionCode`, release notes, and the trusted APK signer digest.

## Create and push the tag

```bash
git tag -s -a vX.Y.Z -m "Release Clench Wallet X.Y.Z"
git cat-file -t vX.Y.Z
git cat-file -p vX.Y.Z
git push origin vX.Y.Z
```

`git cat-file -t` must report `tag`, not `commit`. The protected release workflow independently verifies the SSH signature against the pinned public key and requires the tag to equal current protected `master`; GitHub's generic "verified" label is not sufficient.

The tag push does not execute release workflow code. Dispatch the trusted workflow definition from protected `master` and pass the signed tag only as data:

```bash
gh workflow run release.yml --ref master -f tag=vX.Y.Z
```

The workflow builds the unsigned APK without secrets. The protected signer downloads only that checksummed APK and evidence, runs a pinned `apksigner` without checking out source or invoking Gradle, then destroys the temporary keystore before verification and upload.

## Separation of keys

The Git tag signing key proves source-tag authorship. The Android release
keystore signs the APK; its runtime use remains isolated in the protected,
source-free `release-signing` job. At v0.3.28 the four values are stored as
repository-scoped GitHub secrets and referenced only by that gated job; moving
or rotating them into environment scope remains a defense-in-depth follow-up.
The two keys are separate trust roots and must not be substituted for one
another.
