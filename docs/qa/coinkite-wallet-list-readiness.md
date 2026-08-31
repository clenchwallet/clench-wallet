# Coinkite Compatible-Wallet Listing Readiness

Assessment date: 2026-08-31 UTC
Assessed source: v0.3.28 release candidate

This document records whether Clench can truthfully ask to appear on the
SATSCARD and TAPSIGNER compatible-wallet pages. It is not a vendor endorsement,
and automated tests are not a substitute for a physical Coinkite card.

Status terms:

- `IMPLEMENTED / AUTOMATED` — the behavior exists and has automated coverage.
- `DEBUG-CANDIDATE PHYSICAL EVIDENCE` — a named debug APK completed the stated
  real-card path, but the exact signed production APK has not repeated it.
- `PHYSICAL EVIDENCE REQUIRED` — code exists, but the current production app has
  not completed the required real-card matrix.
- `MISSING` — the required behavior does not exist and blocks a compatibility
  claim.

## Authoritative vendor references

- [SATSCARD compatible-wallet page](https://satscard.com/start)
- [SATSCARD verification guide](https://satscard.com/guides/verify-and-accept)
- [SATSCARD security model](https://satscard.com/security)
- [SATSCARD FAQ](https://satscard.com/faq)
- [TAPSIGNER compatible-wallet page](https://tapsigner.com/start)
- [TAPSIGNER wallet matrix](https://tapsigner.com/wallets)
- [TAPSIGNER integration checklist](https://tapsigner.com/developers)
- [TAPSIGNER FAQ](https://tapsigner.com/faq)
- [Coinkite Tap Protocol](https://dev.coinkite.cards/docs/protocol.html)
- [Coinkite integration best practices](https://dev.coinkite.cards/docs/best-practices.html)

The TAPSIGNER developer page provides a formal integration checklist. Coinkite
does not currently publish an equivalent SATSCARD listing checklist, so the
SATSCARD matrix also follows its verification guide, security documentation,
FAQ, and protocol documentation. Coinkite must still review any listing request.

## SATSCARD

Current decision: **not ready to request a listing**. The implementation is
functionally close, but it has no recorded end-to-end result from a real
SATSCARD running the current production app.

| Requirement | Current status | Clench evidence or remaining gate |
| --- | --- | --- |
| Detect SATSCARD and distinguish it from TAPSIGNER | IMPLEMENTED / AUTOMATED | Typed status parsing and hostile simulator coverage in `TapsignerTapProtocol.kt`, `TapsignerTapProtocolTest.kt`, and `CoinkiteProtocolHostileTest.kt` |
| Verify the factory certificate and card identity before trusting an address or key | IMPLEMENTED / AUTOMATED | `CoinkiteTapCardVerifier.verifyCertificateChain`; malformed chains and identities fail closed |
| Inspect the active slot, sealed state, address, balance, transactions, and confirmations before acceptance | IMPLEMENTED / AUTOMATED | Verified slot/address reads and Electrum-backed balance/history UI; physical acceptance flow remains unverified |
| Set up an unused slot with wallet-provided entropy and verify its deposit address | IMPLEMENTED / AUTOMATED | Authenticated `new` flow, verified read response, network and slot checks |
| Warn that the printed slot-1 QR must not be reused after slot 1 is unsealed | IMPLEMENTED / AUTOMATED | Sweep UI shows the warning before irreversible unseal |
| Keep CVC transient and handle wrong CVC and `auth_delay` safely | IMPLEMENTED / AUTOMATED | CVC uses wipeable `CharArray`; authentication delay is bounded and explicit; real wrong-CVC timing remains unverified |
| Unseal only after an explicit irreversible-action warning, verify the revealed key, and sweep with full fee/output review | IMPLEMENTED / AUTOMATED | Authenticated unseal, key/address verification, native-SegWit drain construction, high-fee acknowledgement, explicit broadcast |
| Recover after NFC removal/interruption without stale authorization | IMPLEMENTED / AUTOMATED | Single-use NFC state and interruption tests; physical RF/removal behavior remains unverified |
| Complete setup, funding, read/verify, unseal, sweep, confirmation, and post-sweep inspection on a real card | PHYSICAL EVIDENCE REQUIRED | Execute every SATSCARD row defined in `physical-hardware-gates-v0.3.24.md`, then record the exact v0.3.28 APK evidence in `physical-hardware-gates-v0.3.28.md` |
| Publish a stable Android version that contains the tested behavior | PHYSICAL EVIDENCE REQUIRED | Record the tested APK hash/version and wait for that version to be available through the distribution channel named in the listing request |

Before contacting Coinkite, record:

1. App version, APK SHA-256, Android model/API, SATSCARD firmware/card identity
   fingerprint, Bitcoin network, and test date.
2. Successful setup/fund/read/unseal/sweep/confirm/post-sweep results.
3. Wrong CVC plus authentication delay, interrupted tap, wrong network,
   already-unsealed slot, and unused-slot results.
4. Sanitized screenshots or a short video that shows address verification,
   irreversible unseal warning, complete sweep review, and confirmation.
5. The exact supported scope: Android, NFC, native-SegWit SATSCARD sweep; no
   claim that SATSCARD signs arbitrary PSBTs.

## TAPSIGNER

Current decision: **not eligible for a compatible signing-wallet listing**.
Clench can set up, verify, import, back up, and sign a supported
single-signature BIP-84 payment with TAPSIGNER. A named debug candidate
completed one real-card Mainnet lifecycle. v0.3.28 also implements narrowly
scoped BIP-48 account-zero native-P2WSH multisig cosigning with automated and
independent-vector coverage, but no real-card multisig result is recorded.
Clench still cannot change the initial PIN/CVC, and the exact signed v0.3.28 APK
has not completed the full vendor matrix.

| TAPSIGNER checklist requirement | Current status | Clench evidence or remaining gate |
| --- | --- | --- |
| Verify the factory certificate and card identity before trusting xpubs | IMPLEMENTED / AUTOMATED | Certificate-chain verification runs before xpub import |
| Supply a fresh 32-byte chain code at setup and confirm the master xpub preserves it | IMPLEMENTED / AUTOMATED | Secure random chain code plus constant-time comparison against serialized master xpub |
| Let the card generate its private key, choose the intended hardened account path, and preserve network/path/fingerprint/xpub | IMPLEMENTED / AUTOMATED | Authenticated `new`, `derive`, and `xpub` flows for BIP84 single-sig and BIP48 multisig account import |
| Collect a new PIN and change the factory/default PIN during setup | MISSING | No authenticated `change` command or setup UI exists |
| Keep PIN/CVC transient and honor authentication delay | IMPLEMENTED / AUTOMATED | Wipeable `CharArray`, cleared UI/pending state, bounded `wait` flow, numeric-first keypad, and an explicit fallback for legacy alphanumeric PINs |
| Save the encrypted backup without requesting or storing the printed AES key | IMPLEMENTED / AUTOMATED | Authenticated backup command saves only the encrypted `.aes` payload |
| Restore/recover from backup and document loss/recovery behavior | PHYSICAL EVIDENCE REQUIRED | Backup export exists; a documented recovery exercise with a separate card/tool has not been run |
| Sign each required input with a relative non-hardened subpath | IMPLEMENTED / AUTOMATED | PSBT-v0 native-SegWit P2WPKH inputs below authenticated BIP-84 account zero or standard native-P2WSH CHECKMULTISIG inputs for the card member below authenticated BIP-48 account zero are hashed with BIP-143, signed through authenticated Tap Protocol `sign`, and accepted only after witness policy, returned key, low-S ECDSA `SIGHASH_ALL`, ownership, and existing policy-member signature verification |
| Show the complete transaction before the tap, disclose the screenless trust model, and revalidate the finalized transaction | IMPLEMENTED / AUTOMATED | The full Clench review remains visible before PIN/tap; signature-only PSBT merge and the normal final transaction policy run before a separate broadcast action |
| Single-sig TAPSIGNER payment | DEBUG-CANDIDATE PHYSICAL EVIDENCE | Commit `7a5918a` completed a one-input/one-output Mainnet BIP-84 payment, explicit phone-side broadcast, and confirmation on a Pixel 8 Pro; exact APK/hash and limitations are recorded in `physical-hardware-gates-v0.3.27.md` |
| Multisig TAPSIGNER cosigner payment | IMPLEMENTED / AUTOMATED; PHYSICAL EVIDENCE REQUIRED | Standard PSBT-v0 BIP-48 account-zero native-P2WSH `multi`/`sortedmulti` is implemented with an independent Sparrow/Drongo digest/signature vector, hostile policy/key/path cases, existing-partial verification, and atomic multi-input merge; the real-card matrix remains `NOT RUN` |
| Wrong card, wrong PIN, wrong network/path, corrupted backup, interrupted tap, and multi-input physical matrix | PHYSICAL EVIDENCE REQUIRED | Automated fail-closed coverage exists; the recorded physical evidence is one good-path single-input debug-candidate transaction, not this hostile matrix |

The minimum safe TAPSIGNER implementation milestone is:

1. Implement authenticated PIN/CVC change during setup.
2. Run the exact signed production APK with BIP-84 and BIP-48 multiple-input
   cases and every
   wrong-card, wrong-PIN, delay, network/path, interruption, and recovery case
   listed above. Record card firmware and Android/API without retaining wallet
   material.
3. Repeat the complete good-path review/sign/explicit-broadcast lifecycle on
   that exact production artifact.
4. Publish the tested version and submit the vendor evidence package with
   platform, app version, policies, NFC requirements, and last-verified date.

## Transport and privacy policy

Clench does not communicate with signing devices over USB or Bluetooth.
Supported transfers are QR, an intentional NFC tap, or a user-selected file or
removable card. A signer may use USB for power without creating a USB data
session with Clench.

The Android manifest must not request Bluetooth wallet permissions or advertise
Bluetooth/USB signer features. Release-control verification enforces this
policy, and hardware-wallet labels must not present USB, Bluetooth, BLE, or
Virtual Disk as Clench signer transports.

## Listing rule

Do not contact Coinkite for placement until the applicable matrix has no
`MISSING` rows and all required physical evidence is recorded against the exact
published APK. A SATSCARD listing request may proceed independently of
TAPSIGNER. Until that point, public wording may accurately describe the narrow
single-signature BIP-84 and standard BIP-48 native-P2WSH implementation with its
physical-evidence caveat, but it must not imply vendor listing approval, PIN
change, or a completed production-card matrix.
