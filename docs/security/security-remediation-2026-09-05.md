# Security remediation — 2026-09-05

Baseline: `ab00e8e56c006dc3dc872fc7ab2efc6db6b3cff3` (v0.3.28 plus website update).
Working branch: `codex/security-remediation-20260905`.
Status: IN PROGRESS. No release, production deployment, or compatibility claim.

Latest verified runtime target: `5cd9f63cc07e78c10661eb53c71c7f5023e2d96b`.
Hosted run [33967221107](https://github.com/clenchwallet/clench-wallet/actions/runs/33967221107)
passed all 22 named Android regressions, including five real authentication UI
cases and three real JSON/Room backup-identifier cases. Actual XML records zero
failures/errors/skips. Both pinned cross-version upgrade gates passed in that
run. The Mac's strict build passed at `06c24c6`; Mac runtime remains NOT RUN due
to the documented host initializer failure. Neither result establishes physical
hardware or OEM-wide compatibility. Earlier pending/failed entries below are
chronological evidence, not the current test status.

## Scope and completion criteria

| Item | Correction / acceptance | Status |
| --- | --- | --- |
| A-01 authentication setting downgrade | Fresh crypto-bound authentication to disable an enabled gate; single-use controller callback; cancel/replacement/disposal protection; initial setup cannot weaken existing settings or wallets | Implemented; JVM and hosted persistence tests passed; all five actual authentication UI cases passed at 5cd9f63 within the 22-case suite |
| WS-01 multisig key aliases | Shared chain-code/public-key identity for creation and descriptor validation; reject lineage/version/origin/branch aliases while retaining distinct key material | New-policy protection implemented; legacy Wallet Info and descriptor-backup warnings added without rewriting or blocking existing wallets; JVM regressions passed |
| SC-01 signer credential scope | Four secrets in protected release-signing environment, no repository copies, continuity preserved; verify metadata without retrieving values | Resolved 2026-09-05: independent scope/protection checks passed; maintainer reports local credential/certificate continuity and uploads before deletion. No remote-value readback or signing run claimed; see signing-secret-scope.md |
| SC-02 native assurance | Inventory embedded native libraries, bind upstream source/lockfiles and advisory dispositions to shipped AAR hashes; do not equate Maven OSV success with native coverage | Five native-bearing artifacts inventoried with CI drift gate; live Cargo pre-sign release gate added; current matches and non-Cargo source/advisory dispositions remain open |
| Phone signer sessions | Reserve exact reviewed PSBT before authentication; single-use completion; ignore stale inspect/sign/broadcast completions and prevent overlapping operations | Implemented; JVM regressions passed |
| Legacy multipart QR | Bounded accumulator rejecting differing duplicate frames and count changes; no claim of cryptographic stream identity | Implemented; JVM regressions passed |
| SATSCARD cleanup | Wipe decrypted private-key buffer if validation throws before ownership handoff | Implemented; existing protocol JVM suite passed |
| Electrum input and socket lifetime | Cap line and cumulative response sizes before parsing; stream verbose responses without retaining their JSON; close socket on every exit | Defensive correction implemented from source evidence; finite regressions passed; no OOM attack performed |
| State-backup wallet identifiers | Preflight filename-safe, bounded nonblank IDs and reject duplicate IDs before parsing descriptors or writing records | Implemented; all three real-JSON/Room cases passed at 5cd9f63, including preserved labels/UTXO associations. No arbitrary-file access exploit established |
| External invalid partial signatures | Establish actual finalization/recovery behavior; provide explicit original-transaction restart without weakening field-conflict checks | Retention/non-readiness/recovery confirmed on Android at 6005bfe; snapshot-bound restart implemented with four JVM regressions; no fund-loss claim |
| App-UID/Keystore boundary and clipboard lifetime | Document explicit threat-model limits; no promise of protection from a compromised process or perfect JVM zeroization | Documented against current key and clipboard implementation; no storage migration or runtime policy change |
| Release governance | Assess stable required website check and independent maintainer review without breaking sole-maintainer operations | Website check now always reports; required-check activation awaits protected-master workflow availability; independent-review enforcement requires a designated second maintainer; no protection changes made |
| Runtime/hardware | Android emulator checks on corrected commit, with no real wallets/funds; physical-device requirements remain NOT RUN | Hosted 22-case gate passed at 5cd9f63; earlier failed fixture runs remain recorded below; Mac runtime and physical hardware remain NOT RUN |

## Rules

Use disposable synthetic fixtures; never access real seeds or exercise real signing credentials to demonstrate an issue. Stop and discuss any safeguard that prevents sharing. No restriction encountered during remediation so far. A scoped correction is not a completed whole-product audit.

The signing-secret move is an access-scope migration, not key rotation. Never generate a replacement APK signer casually. Adding environment copies without removing repository copies does not resolve SC-01. Remote secret values cannot be retrieved from GitHub.

Preserve all existing funded wallet descriptors. Identity validation cannot prove that distinct keys belong to independent people/devices. Do not silently rewrite or lock users out of a funded policy.

## Backup identifier follow-up

Tracing native database entry points found that state backup IDs were accepted
unchanged and later interpolated into database filenames. New imports now
preflight nonblank IDs as 1–128 ASCII letters, digits, underscores or hyphens.
Duplicate nonblank IDs are rejected because label/UTXO references would otherwise
be ambiguous. Missing/blank IDs retain fresh UUID behavior. Existing stored
wallets and descriptors are not rewritten or removed; no traversal or
arbitrary-file access experiment was performed, and no such exploit is claimed.

The initial JVM fixture attempts failed in Android's unimplemented host
`JSONObject.put` stub, before reaching app validation. Those failures are not
passes or native parser results. The cases were moved to actual Android JSON and
in-memory Room with isolated preferences: unsafe IDs, duplicate IDs, and valid
legacy/UUID/missing-ID imports with transaction-label and UTXO associations.
They are compiled and selected separately from the previous 19-case runtime
evidence; execution must be confirmed from the new 22-case XML report.

The corrected local no-build-cache run executed all 475 JVM cases across 74
suites with zero failures/errors/skips. Debug APK, Android test APK and lint
completed successfully; lint success is not a zero-warning claim. Release-control
verification, hostile release-tool tests and the five-artifact native identity
check passed. The new Android cases remain pending hosted execution.

At `aa455fb98d134ff2176aa62ac6752c337e0a5815`, hosted run `33965900411`
reported 22 cases: 21 passed, one failed, zero errors/skips. Both negative
identifier cases and all prior 19 cases passed. The positive import fixture
failed BDK's public-key parser because its change key had one mistyped hex digit.
The intended public point 2G was independently recomputed; the fixture changed
`...85a778...` to `...85c778...`. No production descriptor validation was weakened.
The corrected positive fixture requires a new hosted result; the failed run is
not relabeled as a pass. Both cross-version upgrade steps in that run succeeded.

At `5cd9f63cc07e78c10661eb53c71c7f5023e2d96b`, run `33967221107` passed
all 22 named cases with zero failures/errors/skips. The actual XML includes
successful unsafe-ID, duplicate-ID and positive identifier/record-association
cases. The positive case uses real JSON, Room and BDK descriptor parsing; this is
not a mock replacement for the failed fixture. Android CI, CodeQL and Website CI
also completed successfully for that exact commit. No further Mac emulator retry
is needed for this software matrix; the Mac-specific NOT RUN record remains.


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

## External partial-signature runtime probe

`ExternalPartialSignatureRecoveryTest` exercises the production repository and
actual BDK library with a fictional P2WSH 2-of-3 transaction and public fixture
keys. It forbids DAO/entropy/mnemonic access, never creates a funded wallet, and
never calls broadcast. It checks that an unusable partial cannot become ready,
that conflicting material is not silently replaced if retained, and that valid
fixture signatures can finalize when starting from the original PSBT. The test
logs whether the unusable partial was rejected or retained, rather than
asserting source speculation as a required product behavior.

Strict Android test-APK compilation passed; actual execution is pending in the
expanded sixteen-case hosted result gate. No application correction for this
provisional issue is claimed from compilation alone.

At `08efb525f21d16b45cb6ba09c359914d500fb98e`, hosted run `33954243239`
passed all sixteen exact cases with zero failures/errors/skips, including the
native recovery test. That proves its no-false-readiness and successful
original-transaction recovery assertions. The diagnostic branch marker was not
retained by the old success-only artifact configuration, so rejection versus
retention remains unestablished from this result. Always-retained filtered
fixture logcat and actual result XML are now added; no real wallet/key is used.

Two additional real UI cases are compiled and queued for hosted execution:
backgrounding a pending credential prompt and recreating the application must
leave both gates enabled; removing the disposable emulator credential must
disable downgrade controls and preserve both gates, with the public fixture
credential restored in finally. The expanded eighteen-case gate requires their
actual execution, not just compilation. Fresh/revisited onboarding UI and
a deliberately late positive callback remain separate outstanding checks.

## Confirmed return-recovery finding and correction

Hosted run `33954981865` at `6005bfe44a0812fd2b62c07c2839f816424d2390`
produced eighteen named passes, zero failures/errors/skips. Saved fixture
logcat explicitly reports `unusable_partial_rejected=false; retained=true`.
This confirms a recoverable collection/session obstruction, not accepted
on-chain spending: the incorrect partial stays non-ready, conflicts with a
corrected same-key field, and valid signatures finalize from the unchanged
original. The four real Android authentication UI tests also passed, including
backgrounding and temporarily unavailable device authentication.

The correction adds an explicit **Discard signatures and restart** confirmation.
It restores the exact original PSBT, drops accumulated returns, assigns a new
session identity and requires new output/fee review. The confirmation token is
bound to the session and current payload; stale/reused confirmations cannot
discard newer state. Restart is refused while merge, signing or broadcast is
active, and after broadcast. Only that session's document-picker stage is
invalidated. The action does not overwrite conflicting signatures, rebuild the
transaction, or imply that a broadcast can be undone. Collection status now
describes a non-ready signer return without claiming signature-math validation.

Four JVM regressions cover exact-original recovery with fresh review, in-flight
merge refusal, late inspection isolation, and generation-specific picker
cleanup; the first also checks replay of a used confirmation token. The full
actual JVM run passed **475 tests**, zero failures/errors/skips; debug assembly
and lint passed (87 warnings, 2 hints). Strict Android test-APK assembly passed.
These software results do not establish physical signer compatibility.

One additional actual onboarding UI case is queued in the nineteen-case hosted
gate: without a credential, a new offline setup must remain navigable with
unset optional gates initialized off; revisiting the same setup must preserve
explicitly enabled gates. No wallet, seed generation, connection test or public
broadcast is involved. Stale positive callback rejection already has JVM
controller coverage; the real OS background test establishes its observed
cancellation path, not every possible OEM callback schedule.

## Nineteen-case hosted follow-up

At `0960d4eefe7a91a9659e299e921639cf69b63c95`, run `33956185754`
executed all nineteen cases: eighteen passed, one failed, zero errors/skips.
The new fresh/revisited offline onboarding case passed. The send-gate case
timed out after credential entry waiting for the setting to change; it did
not demonstrate an unauthorized downgrade. Saved XML and per-test logcat are
retained. The cause is not established from the timeout alone.

The fixture now waits for accessibility/navigation idle between leaving and
reopening destinations, avoiding interaction with a departing Compose screen.
This tests a synchronization hypothesis; it is not a claimed diagnosis or
a weakened authentication check. Strict test-APK compilation passed. Actual
hosted execution is required before reporting the expanded gate green.

## Live native advisory pre-sign enforcement

The `build_unsigned` release job now repeats the strict native inventory and
runs the source-bound live Cargo check before producing signing inputs. Its
dependency chain prevents signing after a native-gate failure. Reports are
retained even on failure; no release was dispatched. A fresh local query
returned the same seven advisory IDs across four groups and exited 1, as
required; no suppression or native clearance is claimed. Six Cargo parser
tests, ten native inventory tests, release-control validation and hostile
release-tool self-tests passed. Four added workflow mutations prove removal,
skipping, continue-on-error and shell suppression of this gate are rejected.
SC-02 remains open for candidate dispositions, non-Cargo source/advisory gaps
and the deliberate SQLCipher update acceptance plan.

At `9af8872826cd1d838fbc8df5bceb1891f6919af5`, run `33957195380`
again executed nineteen cases with eighteen passes and the same send-gate
post-credential timeout. Navigation synchronization alone did not resolve it.
The fixture's geometric switch matching is now replaced with exact accessible
labels on the production switches, preserving their role and checked state.
Failure evidence now records both synthetic gate preferences and control
bounds/check states. These changes investigate control selection; no claim
of root cause or successful correction is made before actual execution.

At `3a96b6da8bcbd33d1ecc56cf9d49e1d66cd98609`, hosted run
`33958237914` reports nineteen executed cases, fifteen passes and four fixture
failures locating authentication controls. Saved accessibility output places
the explicit label on a child and checkable state on its ancestor. The selector
now follows that exact ancestry instead of requiring both properties on one
node. Both preferences remained enabled in the saved failure evidence. Strict
test-APK compilation passed; this addresses a selector defect, not yet the
earlier post-authentication timeout.

Hosted run `33959152097` at `4aa563306d95a3d6055d5bd6fb74df1fa1dfc90e`
produced nineteen passes, zero failures/errors/skips. Actual XML confirms both
gate success/cancel cases, backgrounding, unavailable authentication and fresh/
revisited onboarding. Exact label ancestry now selects the real switches; the
prior failures remain evidence, not relabeled passes.

## SQLCipher 4.17 candidate and cross-version gate

The candidate uses SQLCipher 4.17.0 while retaining AndroidX SQLite 2.6.2 and
compileSdk 36. AAR/POM/module pins matched Maven Central published SHA-256;
strict resolution passed and only the SQLCipher lock coordinate changed. The
native baseline records the new archive/payload hashes and explicitly retains
the Android-tag/submodule discrepancy described in native-assurance.md.

The full local JVM task executed 475 tests (not FROM-CACHE), with zero
failures/errors/skips. Debug/test APK and lint passed. The final test-only
SQLCipher overlay compiled to actual classes/dex in the test APK; it has been
removed from app/src and remains solely under scripts/verification for isolated
build overlays. An earlier init-script attempt returned UP-TO-DATE and was not
accepted as fixture compilation proof. Four acceptance-parser regressions,
six Cargo checks, ten native inventory cases and release-control hostile
self-tests passed.

Hosted instrumentation now requires a separate exact-commit 4.15 writer / 4.17
reader APK-upgrade gate before the existing nineteen-case suite. It verifies
encrypted Room data, committed WAL recovery, nonmutation on wrong-key/corrupt
input, and a new-process reopen, with a disposable shared debug signer. These
three runtime phases remain NOT RUN until actual hosted results are inspected.
No existing release, signing secrets, physical device or real wallet is changed.

## Publication preparation follow-up

The maintainer requested continuation through publishing on 2026-09-05. SC-01 is resolved with independently checked scope/protections and maintainer-reported local key continuity. CameraX libyuv source is now pinned through matching Android superproject cda3ca13a9e9be059fe674a8173cae532cfa42c6. The live Rust gate queried 198 candidates, retained seven raw IDs and passed exact source-call-path dispositions; 14 guard regressions and release-control/hostile-tool tests passed. Version 0.3.29/329, release notes and candidate review/gates are prepared; final-candidate build/runtime results are pending. SQLCipher source-tag discrepancy and native C/binary-proof limits remain explicit, not fully resolved SC-02. No signing/tag/release or website deployment has occurred.
