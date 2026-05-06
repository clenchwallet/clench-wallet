# Privacy And Node UX Spec - 2026-05-06

## Scope

Implement wallet gap item #5:

> Privacy/node UX: Tor/Electrum diagnostics, server health, cert-pinning UI, opt-in fee/price lookups.

## Existing Coverage

Clench already has:
- Tor SOCKS5 routing support for Electrum and HTTP lookups.
- Per-server Tor routing and `.onion` auto-routing.
- TLS certificate pin storage and QR/paste import.
- BTC price display behind an explicit opt-in.
- Custom Electrum server settings.

## Changes

1. Electrum diagnostics
   - Add a server health check that works for public and custom Electrum server selections.
   - Show connection mode, target server, Tor route, TLS pin state, server version, and tip height when available.
   - Use the selected Electrum route for transaction and UTXO tip-height lookups instead of defaulting to mempool.space.
   - Respect offline mode by refusing active network diagnostics while offline.

2. Tor diagnostics
   - Surface SOCKS5 proxy host/port and the effective route in health check output.
   - Keep `.onion` routing automatic and explicit in the UI.

3. Cert-pinning UI polish
   - Keep QR/paste import.
   - Show clearer validation failures when pasted/scanned cert data is not a base64 DER cert or `electrums://host:port?cert=...` payload.

4. Opt-in fee/price lookups
   - Keep BTC price lookup opt-in.
   - Add a separate external fee lookup opt-in.
   - Electrum fee estimation remains allowed because the user-selected Electrum server is already the wallet sync server.
   - If Electrum fee estimation fails and external fee lookups are disabled, use static fallback fee defaults instead of calling mempool.space.

## Verification

- `./gradlew testDebugUnitTest assembleDebug`
- `git diff --check`
