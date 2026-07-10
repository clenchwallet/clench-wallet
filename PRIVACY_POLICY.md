# Clench Wallet — Privacy Policy

*Last updated: May 5, 2026*

## The Short Version

Clench Wallet collects no data. We have no servers, no analytics, no tracking, and no advertising. Your keys stay on your device. Period.

## What We Collect

Nothing. Clench Wallet does not collect, transmit, or store any personal information, usage data, crash reports, or analytics. There are no third-party tracking or analytics SDKs, no telemetry, and no phone-home behavior of any kind.

## Data Stored on Your Device

Clench stores the following data **locally on your device only**:

- **Wallet data** — addresses, transactions, labels, balances
- **Key material** — mnemonics and private keys, encrypted with AES-256-GCM via Android Keystore
- **Settings** — your app preferences (server configuration, display currency, etc.)
- **Application database** — wallet records, labels, saved payees, and settings are stored in a SQLCipher-encrypted Room database
- **BDK wallet state** — public descriptors, revealed addresses, transaction graph, and UTXO sync state are stored in separate BDK SQLite files. BDK does not persist descriptor private keys in those files, but the public wallet graph is not SQLCipher-encrypted.

This data never leaves your device unless you explicitly export it (e.g., exporting transaction labels via BIP-329, or sharing a PSBT file).

## Network Connections

Clench makes the following network connections during normal operation. All of these are routable through Tor when you enable Tor support in settings.

| Connection | Purpose | What It Sees | Optional? |
|---|---|---|---|
| **Electrum server** (default: electrum.blockstream.info) | Wallet sync, transaction broadcast | Your wallet addresses and transactions | Configurable — you can point to your own server |
| **mempool.space** | Fee estimation, block height | Anonymous API requests (no wallet data) | Yes — can be disabled; falls back to Electrum fee estimation |
| **Coinbase / CoinGecko** | BTC price in your local currency | Anonymous API requests (no wallet data) | Yes — can be disabled in settings |

### About Electrum Servers

When Clench syncs your wallet, it sends your wallet addresses to the configured Electrum server. The server operator can see which addresses belong to the same wallet. **This is inherent to how Electrum protocol works**, not specific to Clench.

To maximize privacy:
- Run your own Electrum server and point Clench to it
- Enable Tor to hide your IP address from the server

## Third Parties

We share no data with third parties because we have no data to share. There is:

- No advertising
- No analytics (no Google Analytics, no Firebase, no Mixpanel, nothing)
- No crash reporting services
- No user accounts or registration
- No cloud sync

## Key Material Security

Your mnemonics (seed phrases) and private keys:

- Are generated on-device using cryptographically secure random number generation
- Are encrypted at rest using AES-256-GCM with keys stored in the Android Keystore hardware-backed security module
- **Never leave your device** — not to our servers (we don't have any), not to any third party, not anywhere
- Are never included in Android backups

## Open Source

Clench Wallet is fully open source. You don't have to take our word for any of this — you can audit the code yourself at [github.com/clenchwallet/clench-wallet](https://github.com/clenchwallet/clench-wallet).

## Permissions

Clench requests only the following Android permissions:

- **Internet** — to connect to Electrum servers and optional price/fee APIs
- **Camera** — to scan QR codes (addresses, PSBTs, hardware wallet communication). Only active when you open the scanner.
- **NFC** — to communicate with NFC-based hardware wallets (Coldcard). Only active during signing.
- **Biometrics** — to authenticate wallet access

## Children's Privacy

Clench Wallet does not knowingly collect any information from anyone, including children under 13.

## Changes to This Policy

If we ever change this policy, we'll update it in the repository and increment the app version. Given that our policy is "we collect nothing," changes would be unusual.

## Contact

Questions about this privacy policy? Open an issue on GitHub or email cw@clench.net.
