# Clench Wallet 0.3.22 Testnet Lifecycle

This record covers the safely authorized live Testnet3 exercise performed on
2026-07-27. It contains public transaction evidence only. The disposable
mnemonic, PIN, and all private signing material are intentionally omitted.

## Environment and evidence boundary

- App: `net.clench.wallet.debug`, version 0.3.22 / 322.
- Live regression artifact after the native-operation deadline and wallet
  serialization fixes: SHA-256
  `b654ccf234d9f7541f49f050a00b041fded26a22e0a89151ce3a846486191c30`.
- Platform: official Android API 36 x86_64 AVD running with software TCG because
  KVM was unavailable.
- Network: Testnet3 through the user-configured
  `electrum.blockstream.info:60001` plain-TCP endpoint. The release default was
  not changed.
- Signer: a newly generated disposable 24-word Testnet3 phone signer. No
  production wallet, seed, address, or funds were used.
- Independent transaction/status checks: Blockstream Testnet API.

Software emulation establishes the Android transaction flow and live Bitcoin
network behavior. It does not establish NFC/RF behavior, physical camera
behavior, secure-element behavior, Android hardware-backed Keystore behavior,
or third-party signer firmware compatibility.

## Confirmed lifecycle

### Funding

- Receive address: a fresh native-SegWit Testnet3 address at external index 0.
- Funding transaction:
  `a3af796fdd22c53986b60d33604f97c12a1fd80c0a9a9dfab6de4fde15715267`.
- Amount: 10,400 sats.
- Independent result: confirmed at height 5,083,198.

### Build, immutable review, local signing, broadcast, and confirmation

- Original self-spend:
  `c9b73c443e5dbc96cf53239f530fe3ef0a32bd0926dbcaf4424d9e8550c0bca6`.
- Reviewed destination amount: 3,000 sats to a fresh native-SegWit address at
  external index 1.
- Reviewed fee: 422 sats, 141 vB, 2.99 sat/vB.
- The transaction used an RBF-enabled input sequence and was replaced before
  confirmation.

### RBF replacement

- Replacement transaction:
  `ad032ba936be2c876d7266ea3ba493e536b601578f6f63558b0a105ab7dd9d6e`.
- The replacement spent the same input and preserved the reviewed 3,000-sat
  destination.
- Reviewed replacement fee: 1,405 sats, 141 vB, 9.96 sat/vB.
- A fresh immutable fee-bump review and explicit high-relative-fee
  acknowledgment were required.
- Independent result: confirmed at height 5,083,203.

This establishes a live receive → confirmation → build → immutable review →
phone signature → broadcast → RBF replacement → fresh review → replacement
confirmation path.

## Timing-dependent cancel and CPFP boundary

Three small RBF-enabled self-spends were created to exercise cancel-by-RBF:

| Attempt | Transaction | Fee | Independent result |
| --- | --- | ---: | --- |
| 1 | `3540907c87b06c2d94235164e5bdf97a939f36f97cc86260f9527a0289c350ae` | 141 sats | Confirmed at height 5,083,204 before replacement review completed |
| 2 | `9d983c747bf6f5174a47d6da5442bb763985468fe3111e10f59dcaa7579008df` | 141 sats | Confirmed at height 5,083,206 before the cancel screen was reached |
| 3 | `e0d2d9fa8e7ef935139d5288a56f2f85fad994e8977d59a87a74f70754b6d348` | 141 sats | Confirmed at height 5,083,209 before replacement review completed |

The software-only emulator was substantially slower than Testnet3 block
production. No cancel replacement was broadcast, and no source or authorization
gate was bypassed to force one. Live cancel-by-RBF therefore remains **NOT
COMPLETED**.

CPFP also remains **NOT COMPLETED** live because no unconfirmed owned parent
output remained available. Deterministic fee, output-selection, immutable-review,
and interrupted-signing tests cover these code paths, but do not substitute for
a live timing window.

## Faucet return and live regression result

After installing the debug candidate containing the native-operation deadline
and shared-wallet operation gate, the wallet drained all remaining spendable
test coins back to the faucet:

- Return transaction:
  `2d84d200caaa60625bd12e906062ff688dad6203c5d039fa364e89cf9f3b89e2`.
- Exact reviewed output: 7,427 sats to
  `tb1qlj64u6fqutr0xue85kl55fx0gt4m4urun25p7q`.
- Exact reviewed fee: 1,145 sats, 381 vB, 3.01 sat/vB.
- Input/output shape: 5 inputs, 1 output.
- Relative fee: 15.42% of the sent amount; broadcast remained disabled until
  “I verified the exact fee” was acknowledged.
- Independent result: accepted to the mempool and then confirmed at height
  5,083,214.

The final drain completed while periodic sync remained active. This is a live
regression pass for serializing review/signing against sync on the same native
BDK Wallet/Persister.

## Defects found and fixed during the exercise

1. BDK `fullScan` is a blocking native call. A coroutine timeout did not
   pre-empt a stuck scan and could retain the per-wallet mutex indefinitely.
   Electrum connection, scan, recovery, fee estimation, broadcast, sweep, and
   server-test operations now run behind hard Future deadlines. A timed-out
   transport is closed before cancellation, and a native constructor that
   completes late closes its abandoned connection.
2. SOCKS5 negotiation and TLS-handshake reads previously lacked a socket read
   deadline. They are now bounded.
3. Periodic sync could race transaction build/review and other operations on the
   same native Wallet/Persister. All shared wallet operations, including
   background eviction, passphrase lock/unlock, conversion, recovery, and
   deletion, now use one per-wallet operation mutex.

The final source candidate must still pass the complete automated, clean-build,
reproducibility, and hosted verification gates after these fixes.
