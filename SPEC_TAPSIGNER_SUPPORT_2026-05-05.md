# Tapsigner Support Spec - 2026-05-05

## Scope

Add Tapsigner as an air-gapped NFC signer target without introducing USB, Bluetooth, or cable flows.

This is intentionally split into two PR-sized chunks:

1. Tapsigner device/import support
2. Tapsigner PSBT signing hooks

## Protocol Grounding

Coinkite documents Tapsigner as an ISO-DEP/ISO-7816 NFC device using APDU commands with CBOR bodies. The first command is an ISO applet select for app id `f0436f696e6b697465434152447631`, and normal commands use `CLA=0x00 INS=0xCB`. Tapsigner is expected to serve as a multisig cosigner or hardware-wallet-like device, but authenticated commands require CVC-encrypted Tap Protocol sessions.

Source:
- https://dev.coinkite.cards/docs/protocol.html
- https://dev.coinkite.cards/docs/best-practices.html

## Chunk 1: Device and Import Support

Implement:
- Add `TAPSIGNER` to `HardwareWalletType` as NFC-only.
- Keep Tapsigner out of QR/file/Coldcard NDEF paths.
- Add a small Tap Protocol helper that can:
  - Build the Coinkite applet-select APDU.
  - Build a status APDU.
  - Parse CBOR status responses and verify the ISO status word.
- Add NFC status reads in the import flow:
  - If a Tapsigner is tapped, show firmware/path/backup metadata when available.
  - Do not import xpubs from NFC yet, because `xpub` is a CVC-authenticated command.
  - Leave paste/import-by-descriptor available.
- Add tests for wallet capabilities and Tap Protocol status parsing.

Out of scope for chunk 1:
- CVC entry.
- Key initialization.
- `xpub`, `derive`, `new`, `backup`, or `change` commands.

## Chunk 2: PSBT Signing Hooks

Implement:
- Add Tapsigner to hardware signing device selection.
- Add a Tapsigner-specific signing screen section.
- Allow the user to tap a card and read status from the signing flow.
- Make direct signing explicitly unavailable until the Tap Protocol signing bridge exists.
- Explain why signing is unavailable in-product:
  - Tapsigner has no trusted display.
  - Signing requires CVC-authenticated Tap Protocol.
  - Clench must compute exact PSBT input digests, request signatures, inject signatures, finalize, and validate outputs.
  - Current BDK Android bindings expose PSBT serialization/finalization but not safe arbitrary partial-signature mutation.

Out of scope for chunk 2:
- Fake signed PSBT generation.
- Blind NFC signing.
- Storing CVC.
- Adding crypto dependencies for unaudited ECDH/signature injection in the same PR.

## Acceptance Gates

- Focused unit tests for Tap Protocol helpers and hardware wallet capabilities pass.
- Existing hardware QR tests pass.
- Full debug unit tests pass if time permits.
- `assembleDebug` passes.
- `git diff --check` is clean.
