# Clench Wallet 0.3.22 Physical-Hardware Gates

This is the authoritative boundary between automated evidence and physical-device evidence for v0.3.22. `NOT RUN` means no claim is made. A maintainer must record date, app commit, Android device/API, hardware model, firmware, network, transport, exact result, and sanitized failure text for every executed row.

No seed phrase, private key, CVC/PIN, PSBT containing sensitive wallet metadata, or reusable receive address belongs in this document.

## Coinkite cards

| Device / gate | Status | Required physical evidence |
| --- | --- | --- |
| SATSCARD tag dispatch and repeated status/read taps | NOT RUN | Android routes the intended card; interrupted/removal taps fail closed and a fresh tap recovers |
| SATSCARD factory certificate and card identity | NOT RUN | Factory chain validates before address/key trust; an unexpected or tampered identity blocks |
| SATSCARD unused-slot setup | NOT RUN | App-provided entropy/chain code completes on the intended slot and network |
| SATSCARD verified deposit address and funding | NOT RUN | Address is certificate/key verified; slot 1 unseal warning is visible before further receiving |
| SATSCARD wrong CVC and auth delay | NOT RUN | CVC is not retained; errors and wait state are accurate; retry does not reuse stale authorization |
| SATSCARD unseal and sweep on Testnet3 | NOT RUN | Irreversible warning, exact slot, network, fee, destination, key match, broadcast, confirmation, and post-sweep state are verified |
| SATSCARD already-unsealed/unused/network mismatch | NOT RUN | Every mismatch blocks without destructive state change |
| TAPSIGNER tag dispatch and status | NOT RUN | Repeated and interrupted taps route correctly and recover from a fresh status command |
| TAPSIGNER factory certificate and identity | NOT RUN | Card identity is verified before xpub or address trust |
| TAPSIGNER setup/key-pick/derive | NOT RUN | Empty-card check, intended derivation path, wallet-provided chain code, and resulting xpub are verified |
| TAPSIGNER backup and PIN/CVC handling | NOT RUN | Encrypted backup is captured without requesting its AES key; PIN/CVC is never stored; wrong PIN and auth delay fail safely |
| TAPSIGNER import/pair and address verification | NOT RUN | Watch-only descriptor and a receive address match the card |
| TAPSIGNER direct PSBT signing | NOT APPLICABLE | Direct authenticated signing is intentionally unsupported; UI must not imply that a screenless card reviewed or signed a transaction |

## Hardware-wallet PSBT round trips

For each applicable device, test a small Testnet3 wallet and a funded transaction through export, device review, signature import, Clench review, explicit broadcast, and confirmation. Test malformed/wrong-wallet/wrong-network and interrupted transports separately.

| Device / transport | Status | Required physical evidence |
| --- | --- | --- |
| Coldcard Q — BBQr | NOT RUN | Multi-frame export/import, shuffled scan tolerance, device address/amount/fee review, signed return |
| Coldcard Q — NFC | NOT RUN | Tag dispatch, complete transfer, removal/interruption recovery, wrong-wallet rejection |
| Coldcard Mk4/Mk5 — microSD | NOT RUN | Unsigned file export, signed file import, exact transaction preservation |
| SeedSigner — QR/UR | NOT RUN | Animated QR round trip and on-device review |
| Keystone — QR/UR | NOT RUN | Animated QR round trip and on-device review |
| Foundation Passport — QR/microSD | NOT RUN | Both supported transports and on-device review |
| Blockstream Jade — QR/USB/Bluetooth as supported | NOT RUN | Supported Clench path, on-device review, disconnect/interruption recovery |

## Multisig and recovery

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| 2-of-3 independent hardware policy | NOT RUN | Three independently sourced keys, descriptor checks on devices, two distinct signing combinations |
| Wrong cosigner / wrong derivation / wrong network | NOT RUN | Import or signing is refused without mutating the pending session |
| Partially signed PSBT interruption | NOT RUN | App restart/cancel clears pending authorization; re-import resumes only from explicitly selected PSBT |
| Final signature and broadcast boundary | NOT RUN | Incomplete PSBT is blocked; complete PSBT is re-reviewed and broadcast only after explicit approval |
| Watch-only restore from descriptor backup | NOT RUN | Fresh device restores policy, receive addresses, and history without private material |
| Phone-signer seed recovery | NOT RUN | Fresh device restores the intended signer and completes a test PSBT without using production funds |
| Corrupt local wallet state recovery | NOT RUN | Original state is quarantined, failed scan restores it, successful scan preserves quarantine until explicit deletion |
| Android Keystore loss/invalidation | NOT RUN | App reports re-import-required state and never silently degrades into an apparently signable wallet |

## Android platform hardware

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| Camera QR across supported Android versions | NOT RUN | Static, UR, and BBQr scans succeed on representative API 26, 35, and 36 devices; low light, glare, motion, rotation, backgrounding, permission denial, and camera removal fail safely |
| NFC disabled/unavailable and competing tags | NOT RUN | Clear non-destructive error; no stale result is attributed to a later or different tag |
| Biometric cancel, lockout, and enrollment change | NOT RUN | Authorization is never inferred from cancellation/lockout; enrollment or secure-lock changes invalidate protected secrets predictably |
| Hardware-backed Keystore confirmation | NOT RUN | `KeyInfo`/device security properties are recorded for a production-class device without exporting keys; software-only fallback is not mislabeled as hardware-backed |
| Process kill / power loss at persistence boundaries | NOT RUN | Kill during PSBT handoff, wallet creation, restore, and corrupt-state recovery cannot reuse stale authorization or lose the only original state copy |
| Removable media and Android file picker | NOT RUN | microSD/USB import-export handles removal, duplicate names, zero-byte/truncated files, read-only media, and provider failure without overwriting unrelated files |
| In-place upgrade from signed 0.3.21 | NOT RUN | Existing watch-only, phone-signer, multisig, labels, settings, and encrypted Room data open correctly after APK upgrade; rollback expectations are recorded |
| Fresh install / uninstall / backup-restore behavior | NOT RUN | Fresh install has no residual wallet state; uninstall/reinstall and Android backup policy do not create an apparently recoverable private wallet without explicit backup material |
| Screen capture, recents, and accessibility exposure | NOT RUN | Seed/CVC/PIN/private-key views remain protected on a representative OEM build and do not persist in recents thumbnails |

## Release rule

Automated simulator passes must never be copied into this file as physical passes. If hardware is unavailable, the release gate may record an explicit, maintainer-authorized deferral, but public release notes must retain that boundary.
