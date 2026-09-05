# 0.3.29 security-remediation review

This is a source/automated-evidence review of PR #80, not a completed external
audit. The baseline is protected master `ab00e8e`; the recovered inventory and
chronological evidence are in `security-remediation-2026-09-05.md`.

## Reviewed corrections

- Authentication weakening flows through a single-use controller and the real
  crypto-bound Android prompt; unchanged/stronger settings need no downgrade
  permission. Aborted, replaced and disposed operations cannot commit a late
  downgrade. Initial defaults require no existing wallets or explicit gates.
- Multisig comparison uses chain code and public key rather than attacker-
  controlled serialization metadata. Existing policies remain loadable and
  receive warnings rather than silent mutation or lockout.
- Phone-signing requests retain the reviewed PSBT and wallet/session identity
  across authentication, inspection and completion. Operation reservations
  prevent overlap. External collection restart is snapshot-bound, single-use,
  unavailable during signing/merge/broadcast or after broadcast, and restores
  the exact original transaction with renewed review.
- Backup identifiers are bounded filename components, checked for duplicates
  before descriptor/database operations. Bounded network reads precede JSON
  parsing; socket lifetimes and decrypted SATSCARD material have exit cleanup.
- SQLCipher 4.17 upgrade fixtures use actual Android JSON, Room, native BDK and
  encrypted database/WAL behavior, not host JSONObject stubs. Failed historical
  fixtures are preserved in the chronological record, not counted as passes.

The final review pass inspected these boundaries again and found no additional
confirmed application defect in this change set. This is not a statement that
the original interrupted audit covered every application path.

## Supply-chain findings and limits

SC-01 is resolved: environment-scoped signing values, no repository copies,
unchanged approval/branch protections, maintainer-reported original-key
continuity. The final signing workflow still has to exercise the migrated
values; remote secret readback is impossible and is not claimed.

SC-02 has a maintained native identity inventory and fail-closed live Rust
candidate/applicability gate. The seven reported IDs are exact source-call-path
dispositions with input/content bindings and expiry, not patched packages or
a blanket exemption. The CameraX source snapshot now pins libyuv as well.

SQLCipher's tagged Android core submodule differs from the source/version
reported by its 4.17 artifact. Runtime database checks do not reconcile vendor
source history. C advisory coverage and independent native binary reproduction
remain incomplete. These limits remain visible in release notes and the gate;
SC-02 is not described as fully resolved. They are distinct from the confirmed
application defects corrected above and from the original Maven-only coverage
gap. No known matched Cargo finding is silently discarded to publish.

There is no independent human reviewer configured. Automated/AI review is not
substituted for one, and protections must not be altered to require a nonexistent
maintainer. Physical device/firmware and signed-APK runtime checks remain NOT RUN.

## Publication requirements

Require exact-candidate and protected-master CI/instrumentation, live dependency
checks, source-tag signature, independent unsigned rebuilds, original APK signer,
complete payload comparison and the public asset/attestation checks. Version
0.3.29 is not published until that protected workflow succeeds. Website changes
must follow actual GitHub publication, not candidate preparation.
