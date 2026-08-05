# Clench Wallet 0.3.26 Physical-Hardware Gates

This is the authoritative physical-evidence boundary for v0.3.26. The detailed
device, transport, recovery, Android-security, SATSCARD, and TAPSIGNER test rows
remain those defined in
[`physical-hardware-gates-v0.3.24.md`](physical-hardware-gates-v0.3.24.md).
The application security behavior is carried forward from that candidate;
v0.3.26 corrects the release reproducibility proof after two unpublished,
fail-closed workflow attempts and carries a new package version.

## Maintainer release attestation

The maintainer reported running physical-device checks and explicitly
authorized publication. The report did not include the per-row APK digest and
size, Android model/API, signer or card model/firmware, network/transport, or
sanitized outcomes required for an auditable row pass. Therefore every row
marked `NOT RUN` in the detailed matrix remains `NOT RUN`, and this repository
makes no device-specific physical-pass claim.

When auditable v0.3.26 evidence becomes available, copy the corresponding row
definition from the detailed v0.3.24 baseline into this file and record the
exact v0.3.26 APK evidence here. Do not rewrite the historical v0.3.24 or
v0.3.25 matrices.

The following TAPSIGNER rows remain `NOT IMPLEMENTED` and unavailable:

- PIN/CVC change
- Direct single-signature payment signing
- Direct multisig-cosigner signing

## v0.3.26 release-pipeline boundary

The signed v0.3.24 and v0.3.25 source tags produced no public APK releases.
The v0.3.24 workflow rejected an unrequested `.apk.idsig` sidecar. The v0.3.25
workflow rejected unexplained local ZIP alignment-header differences between
the raw independent unsigned APK and the APK after production `apksigner`
processing.

The v0.3.26 candidate must prove that signer input A matches artifact-blind
clean rebuild B byte-for-byte before key access. Artifact-blind clean rebuild C
may run in parallel, but its separate attestation and equality to the approved
raw digest are consumed only in post-sign verification. The verifier must then
reproduce `apksigner`'s deterministic packaging transformation on a copy of C
with a disposable verifier-only key, destroy that key, compare every ZIP entry
with no exclusions, independently verify the established Clench release
signer, and publish only the allowlisted evidence bundle.

## Evidence rule

Future row updates must record the date/tester, exact APK size and SHA-256,
Android model/API, card or signer model/firmware, Bitcoin network/transport,
and a sanitized result. Never record a seed, private key, CVC/PIN, sensitive
PSBT, reusable receive address, or unredacted card identity. Automated tests,
simulators, hosted CI, and maintainer summaries must not be relabeled as
row-specific physical passes.
