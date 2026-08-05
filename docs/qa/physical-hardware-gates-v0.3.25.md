# Clench Wallet 0.3.25 Physical-Hardware Gates

This is the authoritative physical-evidence boundary for v0.3.25. The detailed
device, transport, recovery, Android-security, SATSCARD, and TAPSIGNER test
rows remain those defined in
[`physical-hardware-gates-v0.3.24.md`](physical-hardware-gates-v0.3.24.md).
The application security behavior is unchanged from that candidate; v0.3.25
adds the fail-closed release-pipeline correction described below and carries a
new package version.

## Maintainer release attestation

On 2026-08-04 the maintainer reported completing physical-device checks and
explicitly authorized publication. The report did not include the per-row APK
digest/size, Android model/API, signer or card model/firmware,
network/transport, or sanitized outcomes required for an auditable row pass.
Therefore every row marked `NOT RUN` in the detailed matrix remains `NOT RUN`,
and this repository makes no device-specific physical-pass claim.

When auditable v0.3.25 evidence becomes available, copy the corresponding row
definition from the detailed v0.3.24 baseline into this file and record the
exact v0.3.25 APK evidence here. Do not rewrite the historical v0.3.24 matrix.

The following TAPSIGNER rows remain `NOT IMPLEMENTED` and unavailable:

- PIN/CVC change
- Direct single-signature payment signing
- Direct multisig-cosigner signing

## v0.3.25 release-pipeline gate

The signed v0.3.24 source tag produced no public release. Its protected
workflow stopped before independent reconstruction and publication because
the strict bundle verifier rejected an unrequested `.apk.idsig` sidecar. The
v0.3.25 candidate must prove that the isolated signer explicitly disables v4
sidecar generation, asserts no `.idsig` exists, destroys signing material,
reproduces the APK payload independently, and publishes only the allowlisted
evidence bundle.

## Evidence rule

Future row updates must record the date/tester, exact APK size and SHA-256,
Android model/API, card or signer model/firmware, Bitcoin network/transport,
and a sanitized result. Never record a seed, private key, CVC/PIN, sensitive
PSBT, reusable receive address, or unredacted card identity. Automated tests,
simulators, hosted CI, and maintainer summaries must not be relabeled as
row-specific physical passes.
