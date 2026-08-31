# Clench Wallet 0.3.28 Hardware-Wallet Security Review

This review covers the signer and QR changes merged for v0.3.28: direct
TAPSIGNER BIP-48 native-P2WSH multisig signing, OneKey Pro/Krux/Specter DIY
air-gapped routing, legacy UR v1, `ur:psbt`, binary/text `ur:bytes`, Base43, and
script-preserving single-key `crypto-output` import.

It is a source and automated-evidence review, not a professional external audit
and not a claim that representative physical hardware or the final signed APK
completed the matrix. Physical evidence is tracked separately in
`docs/qa/physical-hardware-gates-v0.3.28.md`.

## Review result

No release-blocking source defect was found within the supported boundary. The
multisig signer is conservative, validates the complete witness policy and
existing signatures before use, authenticates the card member/path, verifies
the returned signature, and applies all signatures atomically. The QR decoder
adds explicit bounds and format separation rather than a permissive generic
text fallback.

Residual risk remains at physical interoperability boundaries: camera behavior,
multipart timing, removable-media conventions, NFC removal timing, deployed
firmware behavior, and a real TAPSIGNER BIP-48 signature were not exercised.
Release authorization explicitly defers those rows without representing them as
passes.

## TAPSIGNER supported policy

The direct signing path accepts only:

- PSBT version 0;
- native-SegWit P2WPKH single-signature inputs below the authenticated BIP-84
  account-zero card key, as retained from v0.3.27; or
- native-SegWit P2WSH standard CHECKMULTISIG inputs below the authenticated
  BIP-48 account-zero card member, using `multi` or `sortedmulti`;
- agreement between the card-reported network flag and the hardened path coin
  type; wallet/PSBT policy binding, rather than a separate expected-network
  argument to `signPsbt`, provides the surrounding wallet context;
- non-hardened receive/change child paths below the authenticated account; and
- ECDSA `SIGHASH_ALL`.

Taproot, legacy, nested-SegWit, nonstandard P2WSH, other accounts, hardened
children below the account, and alternate sighash policies fail closed.

## Transaction and witness binding

For every eligible P2WSH input, Clench checks:

1. the previous output and amount used by BIP-143;
2. that the witness UTXO scriptPubKey commits to the supplied witness script;
3. that the witness script is a canonical supported CHECKMULTISIG policy;
4. the threshold, ordered policy keys, and absence of unexpected script
   operations;
5. that every supplied derivation public key is a witness-policy member;
6. that active-card candidate derivations share one valid account-relative
   receive/change branch and index, and that the active TAPSIGNER is exactly one
   policy member at its authenticated BIP-48 account and requested child path;
   and
7. the transaction-wide version, locktime, inputs, sequences, outputs, and
   sighash policy.

Different cosigners may correctly use different origins. The implementation
does not require unrelated members to share the TAPSIGNER origin and does not
authenticate their fingerprints through the card. It requires supplied
derivation pubkeys to be witness-policy members and applies the authenticated
account-relative path rule to the active card candidates.

## Existing and returned signatures

Before adding a card signature, every existing partial signature for a policy
member is parsed, required to use `SIGHASH_ALL`, checked for valid DER and low-S
form, and verified against the exact input digest and corresponding policy key.
An invalid or foreign partial aborts the operation rather than being preserved
as trusted state.

The NFC response is accepted only when the returned public key matches the
expected card-derived child key and the returned compact signature is valid for
the exact digest. Clench canonicalizes the signature, requires low-S, verifies
ECDSA locally, and injects only DER plus the `SIGHASH_ALL` byte into the PSBT.
The card cannot replace recipients, amounts, fee, witness policy, or other PSBT
fields through its response.

## Atomicity and interruption

Candidate signatures are collected and verified separately from the reviewed
PSBT. They are copied only after every requested input succeeds. A wrong card,
invalid later-input response, NFC removal, cancellation, or exception therefore
leaves earlier inputs unchanged. Retry begins from fresh card status, PIN state,
and transaction review.

After threshold finalization, the existing final-transaction policy compares
the signed result with the reviewed proposal. Hardware signing and signed-
return import never auto-broadcast; broadcast remains a separate explicit user
action.

## Screenless signer boundary

TAPSIGNER cannot display a recipient, amount, fee, change output, or policy. The
complete Clench review is therefore the transaction-confirmation boundary. PIN
entry is transient and the card tap authorizes only the digest Clench prepared,
but a compromised phone UI could still mislead the user. Multisig reduces that
risk only when another independent screen-equipped cosigner verifies the same
transaction before reaching threshold.

## QR and file input boundaries

The added decoder separates supported encodings before PSBT/raw-transaction
normalization:

- legacy UR v1 sessions are bounded and isolated, accept valid out-of-order and
  duplicate frames, and reject conflicting sessions or malformed fragments;
- `ur:psbt` and `ur:bytes` have explicit type handling;
- binary `ur:bytes` remains binary rather than being treated as arbitrary UTF-8;
- binary bytes are recognized only as a structurally valid PSBT or raw
  transaction, while strict control-safe UTF-8 text is handed to the existing
  bounded downstream decoders;
- Base43 input is bounded before decoding and must normalize to an expected
  Bitcoin payload; and
- single-key `crypto-output` retains the declared supported script type.

No decoder success bypasses signed-return transaction validation or explicit
broadcast. Oversized, incomplete, conflicting, or semantically invalid input
fails without reusing stale scanner state.

Krux and Specter DIY file transfer uses the Android user-selected document
boundary. Clench does not enumerate or mount a signer and does not open USB or
Bluetooth data. The user remains responsible for selecting the intended PSBT
file and moving removable media safely.

## Independent evidence

The automated evidence includes:

- a fixed Drongo-derived native-P2WSH vector independently anchoring the exact
  BIP-143 digest and known ECDSA signature, with the same Clench test separately
  exercising synthetic BIP-48 key/path selection and exact PSBT signature
  bytes;
- positive `multi` and `sortedmulti` cases;
- mixed cosigner origins and valid existing partial signatures;
- wrong key/path/card, witness substitution, invalid partial, unsupported
  sighash, and nonstandard-script rejection;
- multi-input success plus later-input failure proving atomicity;
- legacy UR scanner-to-payload integration with reversed frames; and
- positive and hostile QR payload tests, the full JVM suite, lint, CodeQL,
  release controls, and a separate 5,000-case hostile protocol run.

No Sparrow Wallet coordinator or Drongo dependency is shipped or trusted at
runtime. Clench's pre-existing Hummingbird BC-UR library remains a runtime QR
dependency.

## Known limitations and follow-up

- No v0.3.28 physical OneKey Pro, Krux, or Specter DIY round trip is recorded.
- No representative physical camera scan is recorded for the added encodings.
- No real-card TAPSIGNER BIP-48/P2WSH signature or interruption matrix is
  recorded.
- The fixed independent P2WSH oracle stops at the partial signature; threshold
  finalization and final-policy equality are covered by Clench tests rather
  than a separate external finalization fixture.
- TAPSIGNER PIN change remains unavailable.
- Krux does not yet have an outbound BBQr/legacy-format chooser.
- Clench does not yet export a dedicated multisig wallet-registration
  descriptor QR for every signer workflow.
- SafePal and NGRAVE remain deferred pending stable interoperable protocol and
  physical-hardware evidence; Satochip remains deferred pending a screenless
  confirmation security/UX decision.
- Android release credentials are currently stored as repository secrets, even
  though the trusted workflow references them only in the approval-gated,
  no-source signing job. Moving/rotating them to environment scope is a
  defense-in-depth governance follow-up.

## Release conclusion

Within the documented PSBT, script, path, network, sighash, transport, and
explicit-broadcast boundary, the implementation is suitable for release after
the release-preparation PR, exact-master CI/CodeQL, exact-master Android
instrumentation, pinned-key signed tag, and protected Signed Release workflow
all pass. The maintainer-authorized physical deferral must remain public and
must not be presented as verified device compatibility.
