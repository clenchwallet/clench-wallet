# Clench Wallet 0.3.24 Physical-Hardware Gates

This is the authoritative physical-device evidence sheet for v0.3.24.
`NOT RUN` means no claim is made and is not a pass.

## Maintainer release attestation

On 2026-08-04 the maintainer reported completing physical-device checks and
explicitly authorized publication of v0.3.24. The report did not include the
per-row APK digest/size, Android model/API, signer or card model/firmware,
network/transport, or sanitized outcomes required below. The release therefore
records an authorized evidence deferral: individual rows remain `NOT RUN`, and
this repository makes no device-specific physical-pass claim. The three
TAPSIGNER features marked `NOT IMPLEMENTED` remain unavailable.

For every result, record the date/tester, exact APK size and SHA-256, Android
model/API, card or signer model/firmware, Bitcoin network/transport, and a
sanitized result. Never record a seed, private key, CVC/PIN, sensitive PSBT,
reusable receive address, or unredacted card identity.

## Android security controls

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| Seed creation and verification capture | NOT RUN | Screenshot, screen recording, casting, accessibility capture where applicable, and Recents preview remain blocked across the entire flow |
| Obscured-window/tapjacking defense | NOT RUN | PIN, transaction review, signer return, and broadcast controls reject obscured touches without losing state |
| Biometric/device authentication | NOT RUN | Cancel, lockout, unavailable authenticator, reboot, and enrollment change fail closed and require fresh authorization |
| PIN throttle | NOT RUN | Reboot and wall-clock changes do not bypass retry delay; valid recovery remains possible |
| Keystore security reporting | NOT RUN | Reported StrongBox/TEE/software level matches the test device; unsupported hardware is described accurately |
| Upgrade from signed v0.3.23 | NOT RUN | Existing wallets, labels, settings, and encrypted state open correctly; signer continuity permits upgrade |
| Process kill/corruption recovery | NOT RUN | Kill during seed, signing, persistence, and restore cannot expose secrets or reuse stale authorization |

## Network and import boundaries

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| Electrum TLS | NOT RUN | Default and custom TLS endpoints never fall back to plaintext; certificate and network failures are explicit |
| Onion routing | NOT RUN | Mixed-case and trailing-dot onion hosts route through Tor without local DNS or direct-connect leakage |
| Camera and QR/UR/BBQr | NOT RUN | Oversized, malformed, duplicate, shuffled, interrupted, and resource-exhaustion inputs fail safely across representative devices |
| NFC dispatch and interruption | NOT RUN | Wrong tag/type/card, competing tags, disabled NFC, relay/interruption, and stale intents fail closed |
| File/removable-media import | NOT RUN | Zero/truncated/oversized/duplicate files, removal, read-only media, and provider failure do not overwrite unrelated state |

## SATSCARD

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| Identity/certificate and current slot | NOT RUN | Factory chain, product identity, slot state, verified address, balance, history, and confirmations match trusted sources |
| Setup and first-slot warning | NOT RUN | Fresh entropy/chain code and intended network complete safely; printed-QR warning appears before slot 1 unseal |
| CVC and interruption handling | NOT RUN | Wrong CVC, authentication delay, card substitution, replay, and interrupted taps fail closed without retained CVC |
| Irreversible Testnet unseal/sweep | NOT RUN | Exact card/slot/network/destination/amount/fee/key match, explicit broadcast, confirmation, and post-sweep state are verified |

## TAPSIGNER

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| Identity, certificate, and key-possession proof | NOT RUN | Factory identity, card nonce, path, public key, xpub, chain code, fingerprint, and address are checked; the selected account key signs a fresh encrypted app challenge |
| Setup and import/pair | NOT RUN | Fresh chain code, master/account xpub, fingerprint, network, descriptor/policy, and receive address match the intended card |
| Substitution/interruption and relay boundary | NOT RUN | Attacker-key substitution, replay, wrong card/slot/key/nonce, and interruption are rejected; test error-205 retry. Record that path/metadata steering can still cause recovery/availability failure and verify fingerprint/address out of band |
| Backup and CVC handling | NOT RUN | Encrypted backup is saved without requesting the printed AES key; CVC buffers are transient and wrong-CVC delays are accurate |
| PIN/CVC change | NOT IMPLEMENTED | Required before a full Coinkite-compatible setup claim |
| Direct single-sig payment signing | NOT IMPLEMENTED | Imported TAPSIGNER wallet remains watch-only |
| Direct multisig cosigner signing | NOT IMPLEMENTED | Policy import exists; direct card signing is not yet available |
| Encrypted-backup recovery | NOT RUN | Documented external recovery succeeds without exposing production material |

## External hardware-wallet signing

Use a small Testnet wallet. Verify recipients, amounts, change, fee, network,
policy, version, locktime, and sequence. Exercise valid signatures and hostile
weak-sighash returns; only ECDSA `ALL` and Taproot `DEFAULT`/`ALL` may pass.

| Device / transport | Status | Required physical evidence |
| --- | --- | --- |
| Coldcard Q — BBQr | NOT RUN | Multi-frame round trip, shuffled/interrupted scans, on-device review, valid return, weak-sighash rejection |
| Coldcard Q — NFC | NOT RUN | Complete and interrupted transfers, wrong-wallet/card/session rejection, safe valid return |
| Coldcard Mk4/Mk5 — NFC | NOT RUN | Intentional tap, substitution/interruption recovery, exact transaction preservation |
| Coldcard Mk4/Mk5 — microSD/file | NOT RUN | User-selected unsigned/signed files preserve the approved transaction and reject weak policies |
| SeedSigner — QR/UR | NOT RUN | Animated QR round trip, on-device review, safe valid return, hostile return rejection |
| Keystone — QR/UR or file | NOT RUN | Supported round trips and on-device review, including hostile return rejection |
| Foundation Passport — QR or file | NOT RUN | Supported round trips and on-device review, including hostile return rejection |
| Blockstream Jade — QR | NOT RUN | QR-only round trip, review, interrupted scan recovery, and hostile return rejection |

## Multisig and recovery

| Gate | Status | Required physical evidence |
| --- | --- | --- |
| 2-of-3 independent hardware policy | NOT RUN | Independently sourced keys, descriptor verification, two valid signer combinations, incomplete and weak-sighash rejection |
| Wrong cosigner/path/network/session | NOT RUN | Every mismatch blocks without mutating the pending authorization |
| Wallet backup/restore and private-descriptor rejection | NOT RUN | Mainnet/testnet private descriptors never enter watch-only or secret-free backups; valid recovery retains public state |

## Evidence rule

Automated simulator, emulator, hosted CI, and clean-room rebuild passes must
never be copied here as physical passes. Any failure must be triaged against
the exact candidate APK through the normal security and release process.
