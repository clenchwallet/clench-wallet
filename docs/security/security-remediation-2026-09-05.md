# Security remediation — 2026-09-05

Baseline: `ab00e8e56c006dc3dc872fc7ab2efc6db6b3cff3` (v0.3.28 plus website update).
Working branch: `codex/security-remediation-20260905`.
Status: IN PROGRESS. No release, production deployment, or compatibility claim.

## Scope and completion criteria

| Item | Correction / acceptance | Status |
| --- | --- | --- |
| A-01 authentication setting downgrade | Fresh crypto-bound authentication to disable an enabled gate; single-use controller callback; cancel/replacement/disposal protection; initial setup cannot weaken existing settings or wallets | Implemented; JVM and hosted Android persistence tests passed; both seed/send real credential UI cases passed at 9abb768; remaining lifecycle/no-auth/onboarding UI matrix pending |
| WS-01 multisig key aliases | Shared chain-code/public-key identity for creation and descriptor validation; reject lineage/version/origin/branch aliases while retaining distinct key material | New-policy protection implemented; legacy Wallet Info and descriptor-backup warnings added without rewriting or blocking existing wallets; JVM regressions passed |
| SC-01 signer credential scope | Four secrets in protected release-signing environment, no repository copies, continuity preserved; verify metadata without retrieving values | Current scope reconfirmed; maintainer must supply existing values securely |
| SC-02 native assurance | Inventory embedded native libraries, bind upstream source/lockfiles and advisory dispositions to shipped AAR hashes; do not equate Maven OSV success with native coverage | Five native-bearing artifacts inventoried with CI drift gate; partial source bindings recorded; transitive source/advisory review remains open |
| Phone signer sessions | Reserve exact reviewed PSBT before authentication; single-use completion; ignore stale inspect/sign/broadcast completions and prevent overlapping operations | Implemented; JVM regressions passed |
| Legacy multipart QR | Bounded accumulator rejecting differing duplicate frames and count changes; no claim of cryptographic stream identity | Implemented; JVM regressions passed |
| SATSCARD cleanup | Wipe decrypted private-key buffer if validation throws before ownership handoff | Implemented; existing protocol JVM suite passed |
| Electrum input and socket lifetime | Cap line and cumulative response sizes before parsing; stream verbose responses without retaining their JSON; close socket on every exit | Defensive correction implemented from source evidence; finite regressions passed; no OOM attack performed |
| External invalid partial signatures | Establish actual finalization/recovery behavior before changing acceptance policy | Pending; not a confirmed fund-loss issue |
| App-UID/Keystore boundary and clipboard lifetime | Document explicit threat-model limits; no promise of protection from a compromised process or perfect JVM zeroization | Documented against current key and clipboard implementation; no storage migration or runtime policy change |
| Release governance | Assess stable required website check and independent maintainer review without breaking sole-maintainer operations | Website check now always reports; required-check activation awaits protected-master workflow availability; independent-review enforcement requires a designated second maintainer; no protection changes made |
| Runtime/hardware | Android emulator checks on corrected commit, with no real wallets/funds; physical-device requirements remain NOT RUN | Hosted 13-case instrumentation passed; Mac retry handoff at 06c24c6 supplied; UI/hardware checks remain NOT RUN |

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

## Mac runtime blocker recovery

The owner reports that API 36 ARM64 emulation crashed before boot with SIGILL and the strict macOS build rejected a missing AAPT2 checksum at `1d8b0a2`. All requested Mac runtime checks remain NOT RUN. The supplied summary does not establish the emulator crash cause; the crash report has not been independently inspected here.

The missing artifact is `com.android.tools.build:aapt2:9.3.1-15703166:osx`. The archive was downloaded separately from Google's documented Maven location, checked for ZIP integrity, and its SHA-256 matched Google's separately published `.jar.sha256` sidecar:

- Artifact: https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/9.3.1-15703166/aapt2-9.3.1-15703166-osx.jar
- Published digest: https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/9.3.1-15703166/aapt2-9.3.1-15703166-osx.jar.sha256
- SHA-256: `1e35bc2ce18c3aae840be2a29659ce50d6043e907a44d98ee1cf375d044fa29c`
- Trust boundary: Google Maven HTTPS plus its published digest; this is not an independently signed provenance claim. No macOS executable was run on Linux. The optional SHA-1 sidecar request failed; SHA-256 verification succeeded.

Only this missing artifact pin is added; versions, locks, existing checksums and strict verification are unchanged.

Hosted Android instrumentation run `33938776111` passed on `1d8b0a2`, but its explicit nine-test selector excluded `AuthenticationGatePersistenceTest`. The selector and result gate now require the four new tests as well (thirteen exact cases). This is persistence/controller coverage, not proof of actual biometric-prompt UI or lifecycle behavior. Await the updated hosted results and the Mac runtime evidence.

## Follow-up evidence

- At `06c24c6e68f4fd04dd83dbf6017a2539799990b8`, hosted Android CI, CodeQL
  and instrumentation passed. Run `33945433756` reports exactly 13 required
  cases with zero failures/errors/skips, including all four
  `AuthenticationGatePersistenceTest` methods. The actual result gate output
  was inspected; this is not an inference from a green workflow label.
- The Mac retry is pinned to `06c24c6`; later documentation/tooling changes do
  not require replacing that runtime test target. No Mac retry artifacts have
  been received here. AAPT2 checksum correction is not an emulator-crash fix.
- Native resolution was rerun with strict verification; five native-bearing
  archives match [the baseline](native-dependencies.json). Ten finite inventory
  regressions passed. Source/advisory gaps remain explicit in
  [native assurance](native-assurance.md).
- Five synthetic signing-scope checks passed. The live read-only verifier
  correctly failed: all four repository entries remain and all four protected
  environment entries are missing. This is an unresolved operational finding,
  not a failed application regression. See [maintainer migration](signing-secret-scope.md).
- Release controls and release-tool self-tests passed for the tooling changes.
  No signing values were read, moved or rotated; no release was initiated.
- Four legacy multisig-display regressions cover equivalent key material with
  different metadata, warning propagation into backup metadata, genuinely
  different chain codes, and persistence after renaming signers. No stored
  descriptor or signer ID is rewritten and old-wallet loading is not gated by
  the new import validator. The complete JVM suite subsequently ran 471 cases,
  with zero failures/errors/skips. Debug assembly and lint also passed; lint
  reports 87 warnings and 2 hints, not zero issues.
- The eleven website tests and generated-output check passed after removing
  the workflow path filter. Remote branch protection still requires only
  `build` and `analyze`; [activation and reviewer prerequisites](release-governance-follow-up.md)
  are recorded rather than reported as completed enforcement.

## Hosted UI validation replacing the blocked Mac execution

At `99b8b8805383482bb0489fd1e4fb3bd9127f2e36`, Android CI, thirteen-case
instrumentation, CodeQL and the always-reporting Website CI all passed.

The owner subsequently supplied a strict Mac build PASS at `06c24c6` and detailed
host CPU/cache initialization evidence. The app never ran there; see
[the Mac evidence summary](../qa/mac-runtime-2026-09-05.md). No sandbox bypass,
graphics workaround or guest-image replacement is authorized by that diagnosis.

`AuthenticationGateUiTest` now compiles against the actual production activity
and screens, using real system credential prompts and no mock success callback.
The hosted selector/result gate expands to fifteen exact cases, adding separate
seed and send UI cases. The disposable-emulator guard and known test credential
are required; no physical device or real wallet material is used. These new UI
cases remain NOT RUN until their hosted results are inspected. They do not yet
cover the complete background/late-success, unavailable-authenticator and initial
onboarding UI matrix; those acceptance items remain open.
