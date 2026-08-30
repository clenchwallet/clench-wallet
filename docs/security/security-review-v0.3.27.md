# Clench Wallet 0.3.27 TAPSIGNER Security Review

This review covers the direct TAPSIGNER payment-signing path promoted for
v0.3.27 and the authenticated account-import chain on which it depends. It is
a source and evidence review, not an independent professional audit or a claim
that every TAPSIGNER, Android version, NFC controller, or transaction shape has
been physically tested.

Within the deliberately narrow supported boundary below, no known
fund-redirection or automatic-broadcast path remains. The physical evidence is
useful compatibility evidence from an identified debug candidate; it is not an
exact-artifact physical pass for the final signed v0.3.27 APK.

## Supported boundary

Direct payment signing is enabled only for an imported, single-signature
TAPSIGNER account with all of the following properties:

- BIP-84 native SegWit (`P2WPKH`);
- PSBT v0;
- ECDSA `SIGHASH_ALL`;
- the exact standard account-zero path `m/84'/0'/0'` on mainnet or
  `m/84'/1'/0'` on testnet, followed by at most two unhardened input-path
  components;
- every PSBT input eligible for, and owned by, the active TAPSIGNER account;
- complete payment review in Clench before NFC signing; and
- a separate user action after finalization before broadcast.

The implementation can iterate over multiple eligible inputs while the card
stays in one NFC field session. Only a one-input payment has been validated on
physical hardware. Taproot, legacy, nested SegWit, P2WSH, and direct
multisig-cosigner TAPSIGNER signing are rejected or unavailable. A custom
BIP-84 account index is also outside the direct-payment boundary. Clench does
not implement TAPSIGNER PIN change.

## Threat and trust boundary

The reviewed path treats NFC/APDU/CBOR responses, returned extended keys,
returned PSBT data, signer callbacks, and native-parser inputs as hostile. It
defends against malformed responses, replay or nonce substitution, a changed
card or path during one operation, account-key substitution, stale signing
callbacks, weak sighash flags, returned-PSBT metadata substitution, and a
final transaction that differs from the reviewed unsigned transaction.

The following remain trust assumptions:

- the embedded Coinkite factory root, the card secure element, and the deployed
  card firmware behave as expected;
- Android, Clench's process, and the input method used for the PIN are not
  compromised; and
- the user controls the local NFC tap and reviews the complete payment shown by
  Clench.

TAPSIGNER is screenless. It cannot independently display the recipient,
amount, change, or fee, so the phone is both coordinator and transaction
display. A compromised phone or malicious input method is outside what the
card can detect. The UI therefore gives an explicit screenless-signer warning
and shows the complete transaction again before accepting the PIN and tap.

## Account and card verification controls

1. **Bounded decoding and typed status.** The Tap Protocol parser accepts the
   well-formed indefinite-length CBOR emitted by real cards while retaining
   response-size, nesting-depth, item-count, duplicate-key, type, and
   single-root limits. Failed APDU status words, malformed fields, an unset
   card, a tamper warning, an unsupported network, or an unexpected path fail
   closed.

2. **Factory identity.** Before an authenticated import or payment operation,
   Clench obtains a fresh 16-byte check nonce, verifies the card's signature
   over the card nonce and check nonce, and recovers the certificate chain to
   the embedded Coinkite factory root. Subsequent status checks require the
   same certified card public key, derivation path, and expected response
   nonce within the operation.

3. **Pre-command nonce discipline.** The derive attestation is verified with
   the `card_nonce` captured before the derive command and the fresh request
   nonce. The nonce returned by the derive response is treated as the nonce for
   the next command, never substituted into the transcript being verified.

4. **Deployed-card derive profile.** For a non-empty hardened account path,
   Clench verifies

   `SHA256("OPENDIME" || previous_card_nonce || request_nonce || master_chain_code)`

   under the master public key. The master key and chain code come from the
   authenticated master xpub, and the master key must equal the derive
   response's `master_pubkey`. Key, chain-code, previous-nonce, and
   request-nonce substitutions are rejected.

5. **Child-`0/0` proof and account binding.** The production derive signature
   authenticates the master tuple, not every returned derived field. Clench
   therefore derives child `0/0` locally from the returned account public key
   and chain code, sends a fresh encrypted 32-byte challenge to that subpath,
   and requires a slot-zero signature and matching child public key. A later
   account xpub must contain that proof-bound account public key and chain
   code.

6. **Canonical xpub and native-parser preflight.** Clench validates card/app
   network agreement plus the returned xpub version, depth, and final child,
   then constructs a canonical xpub/tpub from the proven account tuple and
   expected path. Before wallet creation, the exact Android BDK library parses
   the origin-wrapped key and both receive and change descriptors, performs
   descriptor sanity checks, and returns its normalized receive descriptor to
   the repository. A parser or handoff incompatibility fails before import.

The master fingerprint used in origin metadata is computed from the master
public key. The deployed-card derive proof is a self-attestation and does not
cryptographically bind every master-origin metadata field to the factory card
identity. The child proof and account-xpub binding prevent substitution of the
spend key and chain code, but the direct, controlled NFC tap remains the trust
boundary for origin labeling and availability.

## Payment authorization and signature controls

1. **Reviewed-session reservation.** NFC signing cannot begin until the
   unsigned transaction has been inspected and acknowledged, including any
   high-fee acknowledgement. The signing token binds the wallet, session
   generation, and exact PSBT snapshot. Duplicate, late, or stale callbacks
   are discarded and cannot consume or authorize a newer session.

2. **Exact account-path gate.** Before a payment digest is prepared, the card
   must report exactly `m/84'/0'/0'` for mainnet or `m/84'/1'/0'` for testnet.
   The same gate runs again after an authentication delay, after any bounded
   nonce-refresh retry, and in the final status check. BIP-48, a custom BIP-84
   account index, and a BIP-84 path for the opposite network fail closed even
   if their PSBT derivation metadata would otherwise be internally consistent.

3. **Per-input eligibility.** `TapsignerPsbtSigning` parses a bounded base64
   PSBT v0 and rejects already signed or finalized inputs. Every input must
   include a witness UTXO and exactly one compatible BIP32 derivation below the
   active account path. The derivation public key must hash to that input's
   native-P2WPKH witness program. Hardened or overlong relative paths,
   conflicting subpaths, unsupported scripts, and any sighash other than
   `SIGHASH_ALL` fail before a digest is sent to the card.

4. **Exact digest.** Clench computes the BIP-143 `SIGHASH_ALL` digest from the
   complete unsigned transaction, witness-UTXO amount, P2WPKH script code,
   sequences, prevouts, outputs, version, and locktime. Each eligible input is
   sent in a fresh authenticated sign command using the current card nonce and
   its validated relative subpath.

5. **Returned signature.** The response must use slot zero and return a
   compressed public key from the PSBT's eligible wallet-policy candidates.
   Clench verifies the compact secp256k1 ECDSA signature against the exact
   locally computed digest. Before DER encoding and insertion, the signature
   is checked again and high-S signatures are rejected. The appended sighash
   byte is fixed to `SIGHASH_ALL`.

6. **Card continuity and bounded retry.** The card remains in the RF field
   while inputs are signed. A card authentication-delay response permits only
   the bounded protocol-specific nonce refresh; arbitrary failures are not
   blindly retried. A final status read must retain the same certified card
   public key, account path, and latest response nonce.

7. **Signature-only merge.** The local bridge inserts only the verified partial
   signature into its parsed copy of the original PSBT. The repository then
   starts again from Clench's canonical current PSBT and imports only allowed
   signature/finalization fields. Signer-supplied UTXOs, scripts, derivations,
   global xpubs, proprietary data, and unknown metadata do not survive the
   merge. Conflicting signature material and weak sighash metadata fail closed.

8. **Final transaction policy.** After BDK merge and finalization, Clench
   compares transaction version, locktime, input outpoints, sequences, and
   every output amount and script against the reviewed unsigned PSBT. It also
   rechecks finalized signature sighash policy. Only then does the UI enter a
   distinct ready-to-broadcast state. Broadcast is never part of the NFC
   callback: the user must press **Broadcast Transaction**, and the repository
   reauthorizes the still-current wallet/session at the network boundary.

## PIN and IME handling

All TAPSIGNER PIN fields are masked and use a numeric keypad by default. An
explicit letters-and-symbols fallback preserves compatibility with older cards
that may retain a legacy printable-ASCII PIN. Inputs are limited to 6–32
printable, non-space ASCII characters. IME Done, navigation, signer changes,
and NFC actions clear focus and request keyboard dismissal.

At signing, the visible Compose value is cleared before NFC begins. The
protocol receives a mutable `CharArray`; cancellation, replacement, failure,
and success paths overwrite it, and protocol-local command, nonce, challenge,
signature, key, and PSBT working buffers are cleared where the runtime permits.
This reduces lifetime but is not a formal JVM/Android memory-erasure guarantee:
the earlier immutable UI `String`, keyboard internals, OS memory, or crash
artifacts cannot be proven erased. Users should use a trusted Android keyboard.
The keyboard-lifecycle changes were not physically rechecked after the funded
payment run.

## Known Coinkite specification/firmware mismatch

The published Tap Protocol description and emulator describe a derive
signature made by the newly derived key over the newly derived chain code.
Deployed cards observed by this project, consistent with
[upstream issue #56](https://github.com/coinkite/coinkite-tap-proto/issues/56),
instead sign the master chain code with the master key. The two profiles happen
to coincide at `m` but differ for the hardened account paths Clench imports.

For a non-empty path, v0.3.27 accepts only the deployed-card profile. It does
not try arbitrary key/chain-code combinations and does not skip verification.
If future firmware switches to the documented profile, Clench will fail closed
until that behavior is independently bound and reviewed. The encrypted
child-`0/0` proof remains mandatory because neither profile alone provides the
complete account-tuple guarantee required by import.

## Verification evidence

Automated coverage includes:

- production-profile derive vectors with distinct master/derived keys and
  chain codes, plus wrong nonce, key, chain-code, cross-profile, and
  indefinite-CBOR rejection/acceptance cases;
- the BIP32 public-child vector, fresh challenge proof, replay/wrong-session,
  slot, card, path, nonce, account-xpub, network, and xpub-header cases,
  including acceptance of the exact mainnet/testnet account-zero paths and
  rejection of BIP-48, custom-account, and opposite-network paths;
- the official BIP-143 native-P2WPKH `SIGHASH_ALL` vector and a PSBT
  prepare/signature-injection round trip;
- hostile APDU/CBOR size, recursion, root, duplicate-key, type, interruption,
  and semantic-bound tests;
- reviewed-PSBT reservation, stale callback, duplicate callback, and broadcast
  reauthorization tests;
- PIN boundary, printable-ASCII, numeric-keypad, and legacy-keyboard tests; and
- Android instrumentation that passes canonical mainnet and testnet account
  keys through the actual BDK public-key, receive-descriptor, and
  change-descriptor parsers and rejects malformed origin/network cases.

On 2026-08-30, the maintainer also completed one real-card Mainnet lifecycle on
a Pixel 8 Pro using debug candidate commit `7a5918a`, package
`net.clench.wallet.debug`, version `0.3.26-tapsigner-test` (`326`), APK size
64,515,556 bytes, SHA-256
`f2b5a2ec0d152798e36bd6b04806ff04db8565dc8ecd7064c307348e270d951a`.
The imported account was BIP-84 `m/84'/0'/0'`. The payment had one input and
one output, sent 10,067 sats, paid a 110-sat fee, had no change or unexpected
outputs, required the maintainer's phone-side Broadcast press, and was later
observed with two confirmations. No PIN/CVC or reusable wallet material was
retained.

That result validates compatibility of the identified debug candidate. The
final production APK has not undergone the same physical flow. The later
keyboard build at commit `29aab2a` was covered by automated tests and lint but
was not physically retested.

## Residual risks and release limits

- The final signed v0.3.27 APK still requires exact-artifact install/import,
  sign, broadcast, and upgrade evidence. The recorded card firmware and
  Android/API version were not retained, limiting reproducibility.
- Multiple-input signing is implemented and automated but not physically
  validated. Wrong-PIN/auth-delay, NFC interruption, card replacement, and the
  broader phone/card firmware matrix also remain physically untested.
- The screenless signer cannot protect against a compromised Clench/Android
  display, malicious keyboard, or coerced/relayed signing through a compromised
  phone. Users must control the tap and review the transaction in Clench.
- The deployed-card behavior diverges from the public protocol documentation.
  A firmware change may cause a safe compatibility failure and requires new
  real-card evidence before broadening accepted profiles.
- Android BDK preflight proves parser acceptance and descriptor consistency;
  it does not independently attest card firmware, key generation entropy, or
  secure-element implementation.
- TAPSIGNER PIN change and direct multisig-cosigner payment signing are not
  implemented. Public-policy import or wallet creation must not be described
  as direct multisig signing support.
- Taproot, legacy, nested-SegWit, and P2WSH inputs remain outside the supported
  direct-payment boundary and must continue to fail before card signing.
- Direct payment signing rejects custom BIP-84 account indices; a structurally
  valid BIP-84 path is not sufficient unless it is the standard
  network-specific account-zero path.

The exact physical-evidence state is maintained in
`docs/qa/physical-hardware-gates-v0.3.27.md`; release checks are maintained in
`docs/qa/v0.3.27-release-gate.md`.
