# Security remediation — 2026-09-05

Baseline: `ab00e8e56c006dc3dc872fc7ab2efc6db6b3cff3` (v0.3.28 plus website update).
Working branch: `codex/security-remediation-20260905`.
Status: IN PROGRESS. No release, production deployment, or compatibility claim.

## Scope and completion criteria

| Item | Correction / acceptance | Status |
| --- | --- | --- |
| A-01 authentication setting downgrade | Fresh crypto-bound authentication to disable an enabled gate; single-use controller callback; cancel/replacement/disposal protection; initial setup cannot weaken existing settings or wallets | Implemented; JVM verification passed; Android runtime pending |
| WS-01 multisig key aliases | Shared chain-code/public-key identity for creation and descriptor validation; reject lineage/version/origin/branch aliases while retaining distinct key material | Implemented; JVM regressions passed; existing-wallet diagnostic still to assess |
| SC-01 signer credential scope | Four secrets in protected release-signing environment, no repository copies, continuity preserved; verify metadata without retrieving values | Current scope reconfirmed; maintainer must supply existing values securely |
| SC-02 native assurance | Inventory embedded native libraries, bind upstream source/lockfiles and advisory dispositions to shipped AAR hashes; do not equate Maven OSV success with native coverage | Pending |
| Phone signer sessions | Reserve exact reviewed PSBT before authentication; single-use completion; ignore stale inspect/sign/broadcast completions and prevent overlapping operations | Implemented; JVM regressions passed |
| Legacy multipart QR | Bounded accumulator rejecting differing duplicate frames and count changes; no claim of cryptographic stream identity | Implemented; JVM regressions passed |
| SATSCARD cleanup | Wipe decrypted private-key buffer if validation throws before ownership handoff | Implemented; existing protocol JVM suite passed |
| Electrum input and socket lifetime | Cap line and cumulative response sizes before parsing; stream verbose responses without retaining their JSON; close socket on every exit | Defensive correction implemented from source evidence; finite regressions passed; no OOM attack performed |
| External invalid partial signatures | Establish actual finalization/recovery behavior before changing acceptance policy | Pending; not a confirmed fund-loss issue |
| App-UID/Keystore boundary and clipboard lifetime | Document explicit threat-model limits; no promise of protection from a compromised process or perfect JVM zeroization | Documentation pending |
| Release governance | Assess stable required website check and independent maintainer review without breaking sole-maintainer operations | Pending; no protection changes made |
| Runtime/hardware | Android emulator checks on corrected commit, with no real wallets/funds; physical-device requirements remain NOT RUN | Mac handoff to follow |

## Rules

Use disposable synthetic fixtures; never access real seeds or exercise real signing credentials to demonstrate an issue. Stop and discuss any safeguard that prevents sharing. No restriction encountered during remediation so far. A scoped correction is not a completed whole-product audit.

The signing-secret move is an access-scope migration, not key rotation. Never generate a replacement APK signer casually. Adding environment copies without removing repository copies does not resolve SC-01. Remote secret values cannot be retrieved from GitHub.

Preserve all existing funded wallet descriptors. Identity validation cannot prove that distinct keys belong to independent people/devices. Do not silently rewrite or lock users out of a funded policy.

## Verification record

- Initial offline Gradle attempt failed resolving the Android plugin before compilation.
- Subsequent no-build-cache focused authentication/multisig JVM run passed.
- Expanded first run hit unavailable offline Room test metadata. Online resolution retained strict dependency verification.
- One new network regression initially failed to locate a Kotlin-mangled private method; fixed the test lookup.
- Final `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint` run with `--no-build-cache`: BUILD SUCCESSFUL; 467 tests / 74 suites, zero failures/errors/skips. Unit task executed (not FROM-CACHE).
- Lint: zero errors, 85 warnings and 2 hints. Do not call it zero-issue lint.
- Release-control static verification passed; existing signed release/tag and app version untouched.
- Android `AuthenticationGatePersistenceTest` compiled into the test APK; execution still NOT RUN.
- This server exposes no /dev/kvm and has no connected Android device. A Mac emulator can establish Android runtime behavior, not physical NFC/camera or OEM-wide compatibility.
