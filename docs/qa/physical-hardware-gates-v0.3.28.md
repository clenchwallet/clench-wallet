# Clench Wallet 0.3.28 Physical-Hardware Gates

This file is the authoritative boundary between automated evidence and
physical-device evidence for v0.3.28. The detailed baseline device, Android,
transport, recovery, and wallet rows remain defined in
[`physical-hardware-gates-v0.3.24.md`](physical-hardware-gates-v0.3.24.md).
The limited real-card TAPSIGNER result recorded for v0.3.27 remains historical
evidence for that identified debug candidate only.

`NOT RUN` means exactly that. Unit tests, deterministic simulators, independent
Sparrow/Drongo fixtures, emulators, and CI are not physical-device passes.

## Ship authorization and evidence boundary

After being told that OneKey Pro, Krux, Specter DIY, camera-format, and real-card
TAPSIGNER multisig acceptance remained outstanding, the maintainer instructed
“merge and release” on 2026-08-31 UTC. This authorizes publication with those
named rows `NOT RUN`; it does not establish a pass or compatibility with a
particular model, firmware, camera, NFC controller, or removable-media
implementation.

The signed release workflow must bind the final package, version, signer, and
source tag to one exact protected-master commit. Any later physical result must
record the source commit, APK SHA-256, APK signer, Android device/API, hardware
model/firmware, network, transport, and sanitized result. Retain only a redacted
confirmation reference where applicable.

## v0.3.28 changed hardware paths

| Surface | Status | Required physical evidence |
| --- | --- | --- |
| OneKey Pro BC-UR v2 QR | NOT RUN | Import the intended account/policy, compare fingerprint/path/script type and first address, complete unsigned and signed `crypto-psbt` QR round trips, review every output on-device, interrupt/recover multipart scans, and confirm broadcast remains separate |
| Krux BC-UR v2 QR | NOT RUN | Complete the same descriptor/address/output/signed-return matrix over animated QR on representative firmware |
| Krux microSD/file | NOT RUN | Export and import the same PSBT through an explicitly user-selected file/removable card without granting Clench a device data connection |
| Specter DIY BC-UR v2 QR | NOT RUN | Complete the same descriptor/address/output/signed-return matrix over animated QR on representative firmware |
| Specter DIY microSD/file | NOT RUN | Export and import the same PSBT through an explicitly user-selected file/removable card and verify transaction equality |
| Legacy UR v1 and `ur:psbt` camera input | NOT RUN | Scan representative Sparrow/device multipart payloads, including reversed frames, interruption, duplicate frames, conflicting streams, and malformed/oversized input |
| Binary/text `ur:bytes` camera input | NOT RUN | Scan binary PSBT and text-wrapped PSBT/raw-transaction payloads and prove byte-preserving normalization and transaction equality |
| Base43 camera input | NOT RUN | Scan representative bounded Base43 PSBT/raw-transaction payloads and reject invalid or oversized text without stale-session reuse |
| Single-key `crypto-output` script preservation | NOT RUN | Import representative native, nested, legacy, and Taproot account exports and prove the declared supported script type is not silently rewritten |

## TAPSIGNER BIP-48 native-P2WSH multisig

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| Real-card 2-of-N good path | NOT RUN | Enroll a disposable Mainnet or Testnet BIP-48 account-zero TAPSIGNER in a standard native-P2WSH `multi` or `sortedmulti` wallet; review, sign, finalize, compare the exact transaction, and broadcast only through a separate explicit action |
| Sign first and sign after another cosigner | NOT RUN | Repeat with no existing partial and with a valid policy-member partial; verify both signer orders finalize the same reviewed transaction |
| Mixed cosigner origins | NOT RUN | Use a policy whose other members have distinct valid origins and prove the card is limited to its own authenticated member/path |
| Receive/change and multi-input | NOT RUN | Sign multiple eligible inputs across receive/change branches and verify every input plus the complete transaction |
| Wrong card/path/network/witness policy | NOT RUN | Present a different card or altered policy/path/network and verify no partial signature is imported |
| Invalid existing partial | NOT RUN | Corrupt or substitute an existing cosigner signature and verify signing fails before mutation |
| Later-input interruption and atomicity | NOT RUN | Remove/cancel the card on a later input and verify no earlier input is modified; retry only from fresh status, PIN, and transaction review |
| Exact signed v0.3.28 APK | NOT RUN | Repeat the applicable matrix with the published signed package and record its digest and established signer certificate |

TAPSIGNER remains screenless. The phone review is the transaction-confirmation
boundary. Taproot, legacy, nested-SegWit, nonstandard P2WSH, other account paths,
non-`SIGHASH_ALL` policies, and PIN change remain unavailable.

## Unchanged devices and transport policy

No new exact-v0.3.28 SATSCARD, SeedSigner, Keystone, Foundation Passport,
Coldcard Q/Mk4/Mk5, or Blockstream Jade physical pass is claimed. Their prior
evidence boundaries remain unchanged; use the baseline rows before making a
new compatibility claim.

Clench must not open a USB or Bluetooth data connection to a signer. QR, an
intentional NFC tap, and a user-selected file/removable card are the only
allowed transfer classes. Recheck the manifest, labels, picker routing, and
every exercised transport before recording a physical pass.

## Post-release recording rule

Physical results obtained after publication may be appended with their exact
evidence. Failures must be triaged against the released tag and must never be
hidden by converting this authorized deferral into a retrospective pass.
Never record a seed, private key, PIN/CVC, sensitive PSBT, reusable address,
xpub, full transaction ID, or unredacted card identity.
