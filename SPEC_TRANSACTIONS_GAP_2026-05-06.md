# Transactions Gap Spec - 2026-05-06

## Scope

Implement the wallet gap item:

> Transactions: add CPFP/cancel, raw TX broadcast/import, saved payees/address book, stronger address verification.

## Existing Coverage

Clench already has:
- RBF fee bumping for unconfirmed sent transactions.
- A transaction-detail action that spends exact received UTXOs, which can be used for CPFP on incoming transactions.
- Signed PSBT/final transaction return validation for hardware wallet broadcasts.

## Changes

1. CPFP polish
   - Surface spendable outputs from any unconfirmed transaction, including sent transactions with wallet change.
   - Add a CPFP mode that pre-fills a self-address, send-max, selected UTXO(s), and priority fee.

2. Cancel via RBF replacement
   - Add a repository method that attempts to replace an unconfirmed RBF-signal transaction by spending its original inputs back to the wallet at a higher fee.
   - Restrict to hot wallets and unconfirmed transactions.
   - Use precise UI wording: cancellation is a replacement attempt, not a blockchain reversal.

3. Raw transaction import/broadcast
   - Add a raw transaction screen reachable from the wallet menu.
   - Accept pasted or file-imported raw transaction hex/base64.
   - Parse before broadcast and show txid/vsize/RBF status.
   - Require explicit broadcast confirmation and respect offline mode.

4. Saved payees/address book
   - Add a local encrypted Room table for saved payees.
   - Show saved payees on the send screen.
   - Let users save/delete/select payees.
   - Optionally save the current recipient after a successful send.

5. Stronger address verification
   - Replace prefix-only checks with BDK address parsing/network validation.
   - Reject unsupported required BIP-21 parameters.
   - Show script/network verification details before review/broadcast.

## Verification

- Focused JVM unit tests for BIP-21 parsing and raw transaction payload decoding; BDK-backed address/raw transaction validation is covered by compile/build and manual app testing because BDK native bindings are not available in local JVM tests.
- Full `testDebugUnitTest`.
- `assembleDebug`.
- `git diff --check`.
