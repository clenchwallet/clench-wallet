# Fresh-onboarding foreground re-entry — bounded follow-up

## Status

Navigation-only correction prepared against master
`778031f5fba92156bb28e757b627b4c7c3d3a4d7`. Focused emulator confirmation is
**NOT RUN** for the correction; this is not a passing acceptance receipt.
The published v0.3.33 APK and completed strict-16-KB regression remain unchanged.

## Retained observation and source diagnosis

PR #101 retains `scripts/verification/strict-16kb/evidence/development-attempts.zip`:
`run03` and `run04` reach wallet creation, then fail at `created_reopen` after
HOME and explicit MainActivity launch. The UI shows network choice; crash buffers
are empty. Run04 observes the launcher and a stopped existing Activity before
relaunch, so it is not merely evidence of a failed HOME action. No data loss is
established. Cold restart before the same foreground check avoids the observation.

`ClenchNavHost` previously acquired `StartupViewModel` outside `NavHost`, using
the Activity's retained ViewModel store. Fresh onboarding left its startup answer
as `NetworkChoice`. Foreground security cleanup intentionally recreates navigation.
A new loading screen could consume that retained answer before its separate
asynchronous refresh/Compose state update completed. Moving acquisition to the
explicit loading back-stack entry gives that screen a fresh `Loading` state and
one initial evaluation. Leaving loading clears the owner; no duplicate refresh
is needed. The passphrase screen's separate model, picker-resume precedence,
process cleanup, lock policy, wallet persistence and network/signing code are
unchanged.

The owner-lifetime model follows [Android
ViewModel scoping](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-apis).

Host tests exercise the real ViewModel with lifecycle owners and deferred repository
results. They do **not** execute Compose/Hilt or prove actual Android routing. A
focused emulator check must complete the evidence.

## Focused Mac emulator check

Use the existing disposable accelerated emulator and existing external UI helpers.
No physical device, hardware signer, new native dependency, or manual timing test.
Do not alter or reopen #99's completed cold-restart-first runner just to run this
separate regression. Retain a separate bounded scenario and its evidence.

1. Reuse the preserved v0.3.33 failure. If needed for comparison, run one fresh
   onboarding on that APK and return via the ordinary launcher entry
   (`ACTION_MAIN`/`CATEGORY_LAUNCHER`), not only the historical explicit intent.
   Failure to reproduce on a launcher path must be reported, not hidden by
   repeated timing attempts.
2. Build the exact correction in release mode with disposable test signing;
   production signing material is unnecessary. Retain source/APK hashes, build
   mode, emulator image/ABI/page size and commands. Use one official accelerated
   image already available; no new page-size matrix is required for this
   navigation-only delta.
3. Clear only disposable test state. Complete fresh offline onboarding and create
   a throwaway wallet; record wallet identity and derived address. **Before any
   force-stop**, press HOME, wait for the actual launcher/stopped Activity, and
   relaunch. Assert the wallet route and same identity/address, not network choice.
   Record PID/Activity/task identity to establish same-process behavior.
4. Perform the same immediate check after a fresh import, using a separate clean
   disposable setup if needed. Check both the ordinary launcher path and the
   previously used explicit MainActivity launch without changing the candidate.
5. Now force-stop/reopen once and assert the same wallet identity/address. Retain
   first-failure UI/log diagnostics, fresh snapshot receipts, step results and
   exact input identities. If any path fails, stop that scenario and report the
   first failure; do not silently substitute the old cold-restart-first ordering.

Do not claim that a debug or instrumented run is unchanged release-mode acceptance.
Do not expand this follow-up into the previously blocked review areas. Normal
required CI/review, acceptance and signing controls remain. Continue under the
existing authorization once those gates pass; no new publishing confirmation is
needed. A runtime receipt alone does not replace the other existing gates.
