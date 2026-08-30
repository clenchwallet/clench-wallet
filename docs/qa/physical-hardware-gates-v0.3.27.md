# Clench Wallet 0.3.27 Physical-Hardware Gates

This file is the authoritative physical-evidence boundary for v0.3.27. The
general device, transport, recovery, Android-security, SATSCARD, and external
hardware-wallet row definitions remain in
[`physical-hardware-gates-v0.3.24.md`](physical-hardware-gates-v0.3.24.md).
Rows not explicitly supported by the evidence below remain `NOT RUN` for the
exact v0.3.27 production APK.

## Recorded TAPSIGNER compatibility result

| Field | Evidence |
| --- | --- |
| Date/tester | 2026-08-30 UTC; maintainer-operated black-box test |
| App candidate | Commit `7a5918a`; package `net.clench.wallet.debug`; version `0.3.26-tapsigner-test` / code `326` |
| Candidate APK | 64,515,556 bytes; SHA-256 `f2b5a2ec0d152798e36bd6b04806ff04db8565dc8ecd7064c307348e270d951a` |
| Android device | Pixel 8 Pro; Android/API version was not retained in the repository evidence |
| Card | Real TAPSIGNER; card firmware and a redacted identity fingerprint were not retained |
| Wallet | Mainnet, single-signature BIP-84 native SegWit, account `m/84'/0'/0'` |
| Import | Bounded indefinite CBOR status passed; authenticated deployed-card derive passed; encrypted child-`0/0` proof and local Android BDK xpub/descriptor preflight passed; wallet creation succeeded |
| Payment | One input, one output; 10,067 sats to the reviewed user-controlled address; 110-sat fee; no change or unexpected outputs |
| Broadcast | Clench reached the separate ready-to-broadcast state; the maintainer pressed Broadcast on the phone; the transaction was later observed with two confirmations |
| Secret handling | No PIN/CVC, xpub, address, raw NFC response, private key, seed, or reusable wallet material was captured in the retained report |

Verdict: `COMPATIBILITY PASS — IDENTIFIED DEBUG CANDIDATE`. The result proves
that the identified candidate completed one real-card, single-input,
single-output Mainnet lifecycle without automatic broadcast. It does not prove
the final signed v0.3.27 APK, other phones or firmware, multiple inputs, hostile
authentication cases, or unsupported script and policy types.

## Later keyboard candidate

The numeric-first TAPSIGNER PIN field, explicit legacy letters-and-symbols
fallback, IME Done behavior, and keyboard dismissal were added after the funded
payment run in commit `29aab2a`. Its debug APK was 64,548,932 bytes with SHA-256
`de01af2559a0b8e89e2c3999005a75917b33cb54c4b057cdc06e49ce53426f92`,
and its 407 JVM tests and lint completed successfully. The maintainer did not
physically recheck the Pixel keyboard behavior or repeat the funded payment on
that artifact. Record those rows as `NOT RUN`, not as inferred passes.

## v0.3.27 TAPSIGNER matrix

| Row | Status | Required evidence or known result |
| --- | --- | --- |
| Status, authenticated derive, child-key proof, xpub import | COMPATIBILITY PASS / DEBUG CANDIDATE | Passed on the identified `7a5918a` APK; exact signed v0.3.27 rerun remains `NOT RUN` |
| One-input BIP-84 `SIGHASH_ALL` payment | COMPATIBILITY PASS / DEBUG CANDIDATE | Complete reviewed transaction matched after signing and confirmation; user performed the separate broadcast action |
| Numeric keypad, legacy PIN fallback, and IME dismissal | NOT RUN PHYSICALLY | Later `29aab2a` implementation has automated coverage only |
| Multiple eligible P2WPKH inputs in one NFC session | NOT RUN PHYSICALLY | Implementation and deterministic tests exist; repeat with a disposable exact-production wallet |
| Wrong PIN and bounded authentication delay | NOT RUN PHYSICALLY | Never deliberately exhaust a card; record one controlled failure and recovery without retaining the PIN |
| Wrong card, wrong network/path, replaced key/xpub, and stale session | NOT RUN PHYSICALLY | Automated fail-closed coverage exists; exact-card/device behavior remains to be recorded |
| NFC interruption/removal and clean retry | NOT RUN PHYSICALLY | Verify the authorization and PIN are cleared and a retry starts a fresh session |
| Final signed v0.3.27 install/upgrade/import/sign/broadcast | NOT RUN | Record the signed APK size/hash, signer continuity, Android/API, card firmware, network/path, redacted result, and confirmation |
| Taproot, legacy, nested-SegWit, or P2WSH inputs | NOT SUPPORTED | Clench must reject before sending a digest to the card |
| Direct multisig-cosigner TAPSIGNER signing | NOT IMPLEMENTED | Multisig public-policy import does not imply payment-signing support |
| TAPSIGNER PIN change | NOT IMPLEMENTED | Use another compatible tool if the card PIN must be changed |

## Other hardware boundaries

No new exact-v0.3.27 SATSCARD, Coldcard, SeedSigner, Keystone, Passport, Jade,
camera, Android lifecycle/security, or recovery-drill evidence was supplied.
Their detailed rows therefore retain the prior `NOT RUN` state. Automated
tests, Android instrumentation, emulators, and the TAPSIGNER result above must
not be used to mark those unrelated rows as physical passes.

## Evidence rule

Future updates must record date/tester, exact signed APK size and SHA-256,
Android model/API, card or signer model/firmware, Bitcoin network/transport,
and a sanitized result. Never record a seed, private key, CVC/PIN, sensitive
PSBT, reusable receive address, xpub, full transaction ID, or unredacted card
identity.
