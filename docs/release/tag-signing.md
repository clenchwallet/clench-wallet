# Release Tag Signing

Clench production releases require an annotated, cryptographically signed tag that GitHub marks as verified. Never weaken `.github/workflows/release.yml` to accept a lightweight or unsigned tag.

## Maintainer prerequisites

The maintainer must control a GitHub-registered GPG or SSH signing key. Keep the private key outside the repository and never paste it into an issue, pull request, chat, workflow input, or GitHub Actions secret used for APK signing.

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

`git cat-file -t` must report `tag`, not `commit`. After pushing, GitHub must report the annotated tag object's `verification.verified` field as `true`. The protected release workflow performs the same check and refuses to load APK signing material otherwise.

For a manual workflow retry, dispatch the workflow with the signed tag as both the workflow ref and the `tag` input. Dispatching from a branch is rejected:

```bash
gh workflow run release.yml --ref vX.Y.Z -f tag=vX.Y.Z
```

## Separation of keys

The Git tag signing key proves source-tag authorship. The Android release keystore signs the APK and remains isolated in the protected `release-signing` environment. They are separate trust roots and must not be substituted for one another.
