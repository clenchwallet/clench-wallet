# Clench Wallet 0.3.23 Physical-Hardware Gates

This is the authoritative physical-device evidence sheet for v0.3.23.
`NOT RUN` means no claim is made. The maintainer authorized publication before
these tests and will execute them against the published APK.

For every result, record:

- test date and tester;
- APK version, byte size, and SHA-256;
- Android model and API level;
- card/signer model and firmware;
- Bitcoin network and transport;
- exact result and sanitized failure text.

Never record a seed phrase, private key, CVC/PIN, sensitive PSBT, reusable
receive address, or unredacted card identity here.

## Transport and permission checks

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| Fresh install permissions | NOT RUN | Android does not offer Bluetooth permissions; camera and NFC are requested only when their explicit flows need them |
| USB/Bluetooth policy | NOT RUN | No supported signer flow opens USB or Bluetooth data; QR, intentional NFC tap, and user-selected file/removable-card flows remain usable |
| NFC disabled/unavailable and competing tags | NOT RUN | Clear non-destructive error; no stale result is attributed to a later or different tag |
| Interrupted NFC tap | NOT RUN | Removal fails closed, clears pending authorization, and a fresh explicit tap can recover |

## SATSCARD

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| Tag dispatch, product identity, and repeated status | NOT RUN | Android routes the intended card; repeated and interrupted taps do not confuse card/session identity |
| Factory certificate and current sealed slot | NOT RUN | Factory chain validates before address/key trust; tampered or unexpected identity and unsealed state block acceptance |
| Verified address, balance, history, and confirmations | NOT RUN | Current active-slot address is cryptographically verified and the blockchain state matches a trusted source |
| Unused-slot setup | NOT RUN | App-provided entropy/chain code completes on the intended slot and network; resulting address is verified |
| First-slot printed-QR warning | NOT RUN | Warning is visible before slot 1 unseal and explains that the printed QR must not be reused afterward |
| Wrong CVC and authentication delay | NOT RUN | CVC is not retained; errors and wait state are accurate; retry does not reuse stale authorization |
| Irreversible unseal and Testnet sweep | NOT RUN | Exact slot, network, destination, amount, fee/rate/vsize, revealed-key match, explicit broadcast, confirmation, and post-sweep state are verified |
| Already-unsealed, unused, wrong-network, and interrupted cases | NOT RUN | Every mismatch or interruption blocks without unintended destructive state change |

## TAPSIGNER

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| Tag dispatch, status, factory certificate, and card identity | NOT RUN | Repeated/interrupted taps recover safely; identity is verified before xpub/address trust |
| Fresh-card setup and chain-code verification | NOT RUN | Clench supplies fresh entropy, the card generates its key, intended BIP84/BIP48 path is set, and substituted master-xpub chain code is rejected |
| Import/pair and address verification | NOT RUN | Fingerprint, xpub, network, path, descriptor/policy, and a receive address match the intended card |
| Encrypted backup and PIN/CVC handling | NOT RUN | Backup is saved without requesting the printed AES key; PIN/CVC is not retained; wrong PIN and authentication delay fail safely |
| PIN/CVC change | NOT IMPLEMENTED | Required before a Coinkite-compatible setup claim; no physical pass is possible in v0.3.23 |
| Direct single-sig payment signing | NOT IMPLEMENTED | TAPSIGNER wallet remains watch-only in Clench v0.3.23 |
| Direct multisig cosigner signing | NOT IMPLEMENTED | Policy import exists, but the card signature cannot be obtained/applied in v0.3.23 |
| Recovery from encrypted backup | NOT RUN | Exercise documented external recovery without exposing backup AES key or production material |

## Hardware-wallet PSBT round trips

Use a small Testnet wallet. Review recipients, amounts, change, fee, network,
and policy on every screen-equipped signer; re-import the return into Clench,
review again, explicitly broadcast, and confirm.

| Device / Clench transport | Status | Required physical evidence |
| --- | --- | --- |
| Coldcard Q — BBQr | NOT RUN | Multi-frame export/import, shuffled scans, on-device review, signed return |
| Coldcard Q — NFC | NOT RUN | Complete transfer, removal/interruption recovery, wrong-wallet rejection |
| Coldcard Mk4/Mk5 — NFC | NOT RUN | Intentional tap, interruption recovery, exact transaction preservation |
| Coldcard Mk4/Mk5 — microSD/file | NOT RUN | User-selected unsigned export and signed import preserve the reviewed transaction |
| SeedSigner — QR/UR | NOT RUN | Animated QR round trip and on-device review |
| Keystone — QR/UR or file | NOT RUN | Supported return paths and on-device review |
| Foundation Passport — QR or microSD/file | NOT RUN | Supported return paths and on-device review |
| Blockstream Jade — QR | NOT RUN | Animated QR round trip, on-device review, interrupted-scan recovery; no USB/Bluetooth data path |

## Multisig, recovery, and Android platform

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| 2-of-3 independent hardware policy | NOT RUN | Three independently sourced keys, descriptor verification, and two distinct valid signing combinations |
| Wrong cosigner/path/network and incomplete PSBT | NOT RUN | Every mismatch/incomplete return blocks without mutating pending authorization |
| Process kill during PSBT handoff/persistence | NOT RUN | Restart cannot reuse stale authorization or lose the only original state copy |
| Camera QR across representative devices | NOT RUN | Static, UR, and BBQr scans plus denial, glare, motion, rotation, and background interruption |
| Hardware-backed Keystore and biometric behavior | NOT RUN | Hardware properties recorded; cancel, lockout, and enrollment change never infer authorization |
| In-place upgrade from signed v0.3.22 | NOT RUN | Existing wallets, labels, settings, and encrypted data open correctly; signer certificate continuity permits upgrade |
| Removable media/file picker | NOT RUN | Removal, duplicates, zero/truncated file, read-only media, and provider failure do not overwrite unrelated files |

## Evidence rule

Automated simulator, emulator, hosted CI, and clean-room rebuild passes must
never be copied here as physical passes. Failures found after publication must
be triaged against the exact release APK and handled with the normal
security/release process.
