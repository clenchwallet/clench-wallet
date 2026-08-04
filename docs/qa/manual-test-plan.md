# Clench Wallet Manual Test Plan

For the v0.3.24 candidate, record device-specific results in
[`physical-hardware-gates-v0.3.24.md`](physical-hardware-gates-v0.3.24.md)
and the ship decision in
[`v0.3.24-release-gate.md`](v0.3.24-release-gate.md). Automated simulator
results must not be recorded as physical-device passes.

## Goal
Validate security-sensitive release behavior around migrations, passphrase import, recovery posture, logging, signing, broadcast, and network privacy without weakening the intended wallet model.

## Scope
Use this checklist for release candidates and for changes that touch wallet state, key material, signing, broadcast, recovery, networking, release signing, or dependency metadata.

---

## A. Normal Wallet Regression Tests

### A1. Existing normal wallet opens normally
**Precondition:** device/emulator has an existing non-passphrase wallet

**Steps**
1. Launch app
2. Unlock app if app lock enabled
3. Open existing wallet
4. Check balance, tx history, receive addresses

**Expected**
- Wallet opens without migration wipe/reset behavior
- Balance/history still present
- No unexpected re-import requirement
- No phantom "Recovered Wallet" entries

### A2. Create a new standard wallet
**Steps**
1. Create a new wallet
2. Confirm there is no passphrase option in standard creation flow
3. Back up seed and finish setup

**Expected**
- Wallet creation works
- No passphrase creation option shown
- Backup flow still works

### A3. Standard wallet restart behavior
**Steps**
1. Open standard wallet
2. Force close app
3. Relaunch app
4. Reopen wallet

**Expected**
- Wallet metadata persists normally
- No special passphrase-style wipe behavior affects standard wallets

---

## B. Passphrase Import Flow Tests

### B1. Advanced import path is present and understandable
**Steps**
1. Go to import wallet screen
2. Choose seed phrase import
3. Expand Advanced passphrase section

**Expected**
- Passphrase option is not in the main/simple path by default
- Advanced section is easy to find for a knowledgeable user
- Warning text is clearly more severe than standard messaging

### B2. Correct passphrase import
**Precondition:** known seed + known real passphrase

**Steps**
1. Import seed phrase
2. Enter correct passphrase in Advanced section
3. Confirm import dialog
4. Finish import
5. Compare fingerprint/identicon to known-good expectation

**Expected**
- Import succeeds
- Confirmation dialog appears before import
- Wallet fingerprint/identicon matches expected passphrase-backed wallet
- App does not imply the passphrase is stored

### B3. Decoy / wrong passphrase behavior
**Precondition:** same seed, different decoy passphrase

**Steps**
1. Import same seed with alternate passphrase
2. Confirm import dialog
3. Compare resulting fingerprint/identicon and balance

**Expected**
- Import succeeds without "wrong passphrase" error
- A different valid wallet state appears
- No contamination from the real passphrase wallet
- This behavior matches intended duress/decoy model

### B4. Import cancel path
**Steps**
1. Enter seed + passphrase
2. Tap Import
3. Cancel at confirmation dialog

**Expected**
- No import occurs
- User returns safely to import screen
- Typed values remain or clear only if intentionally designed that way

---

## C. Passphrase Session / Locking Tests

### C1. Locked-state privacy
**Precondition:** passphrase wallet exists

**Steps**
1. Unlock / import passphrase wallet
2. View tx history / UTXOs
3. Lock app or background app long enough to trigger lock behavior
4. Reopen app without re-entering passphrase

**Expected**
- Sensitive passphrase wallet activity is not visible before intended re-entry/unlock path
- No stale tx history leaks into locked state

### C2. Re-entry behavior
**Steps**
1. Re-enter correct passphrase after lock/restart
2. Recheck fingerprint/identicon, balance, tx history

**Expected**
- Correct wallet is restored after intended unlock flow
- No evidence that passphrase was stored

---

## D. Migration / Startup Safety Tests

### D1. Existing DB opens without destructive reset
**Precondition:** install over existing app data if available

**Steps**
1. Launch upgraded build with existing DB
2. Observe startup behavior
3. Open wallets

**Expected**
- No silent reset/wipe
- If DB is readable, app proceeds normally
- No unexpected recovery insertion behavior

### D2. Corrupt / incompatible DB behavior (if safely reproducible)
**Steps**
1. Simulate or use a build/device state with unreadable encrypted DB
2. Launch app

**Expected**
- Release behavior fails closed
- App does NOT silently delete DB files
- Any failure is explicit rather than destructive

**Note:** This may require a dedicated test setup. Do not risk real funds for this test.

---

## E. Logging / Privacy Spot Checks

### E1. Release logging spot check
**Steps**
1. Run release-like build
2. Exercise wallet open, sync, address view, tx history, import flow
3. Inspect logs

**Expected**
- No wallet IDs, txid fragments, addresses, balance summaries, or Electrum host details in release logs
- Basic operational logs may remain, but sensitive metadata should be suppressed

### E2. Debug logging spot check
**Steps**
1. Run debug build
2. Exercise same flows

**Expected**
- Debug logs may still exist for development use
- Behavior difference between debug and release is intentional

---

## F. Orphan / Recovery Posture Tests

### F1. No phantom recovered wallets
**Steps**
1. Launch app after upgrade and after a few restarts
2. Inspect wallet list

**Expected**
- No auto-created "Recovered Wallet" entries
- No silently reconstructed wallet rows

### F2. Manual recovery expectation is clear
**Steps**
1. If recovery-required state can be simulated, observe behavior and notes

**Expected**
- App no longer pretends to safely reconstruct uncertain wallet state
- Recovery is treated as explicit/manual, not magical

---

## G. Multisig Recovery Drill Tests

### G1. Descriptor / BSMS round trip
**Precondition:** imported or created multisig wallet with at least 2 cosigners

**Steps**
1. Open Wallet Info for the multisig wallet
2. Confirm policy, script type, M-of-N, descriptor, and all keystores are visible
3. Copy the descriptor and BSMS descriptor record
4. Import the descriptor or BSMS record into a clean Clench install/profile
5. Import the same record into another descriptor-aware wallet if available
6. Compare the first receive address across wallets

**Expected**
- The restored wallet has the same M-of-N policy
- Every signer fingerprint/path/xpub matches the source wallet
- The first receive address matches before any funds are sent
- No seed phrase, passphrase, xprv, or private descriptor appears in the exported data

### G2. Signer health review
**Steps**
1. Open Wallet Info for the multisig wallet
2. Expand every keystore
3. Compare master fingerprint and derivation path against each physical signer

**Expected**
- Each signer has a public key, master fingerprint, derivation path, and ranged branch
- Any missing fingerprint/path warning is treated as a recovery risk before funding

### G3. Replacement signer procedure
**Steps**
1. Simulate a signer replacement need
2. Create a new multisig wallet with the replacement signer set
3. Export and restore the new descriptor/BSMS record
4. Compare receive addresses
5. Move a small test amount before moving meaningful funds

**Expected**
- Existing wallet policy is not mutated in place
- Replacement is handled as a new wallet and fund migration
- User verifies new addresses before moving funds

### G4. Small PSBT drill
**Steps**
1. Fund the multisig wallet with a small amount
2. Build a test spend
3. Sign with the required threshold of devices
4. Verify transaction outputs on each signer before broadcast

**Expected**
- Clench produces a PSBT that signers accept
- Signed return import does not auto-broadcast without explicit confirmation
- Broadcast only happens after final output verification

---

## H. Tapsigner NFC Tests

### H1. Import flow NFC status
**Steps**
1. Open Connect Hardware Wallet
2. Choose Tapsigner
3. Tap NFC
4. Hold a Tapsigner to the phone

**Expected**
- Clench uses ISO-DEP Tap Protocol status, not Coldcard NDEF
- Firmware/path/backup status appears when the card responds
- Clench does not claim xpub import is complete from NFC
- User can still paste an xpub or descriptor exported from a trusted Tapsigner coordinator

### H2. PSBT signing flow guardrail
**Steps**
1. Select Tapsigner as the signing device for a watch-only wallet
2. Build a PSBT
3. Open the Tapsigner signing screen
4. Read Tapsigner NFC status

**Expected**
- Clench displays the Tapsigner-specific screenless-signer warning
- NFC status read succeeds or shows a clear NFC error
- No QR, file, Coldcard NDEF, or fake signed-PSBT flow is offered
- Direct signing stays blocked until CVC-authenticated Tap Protocol signing is implemented

### H3. SATSCARD active-slot sweep
**Steps**
1. Open Sweep External Seed from a wallet
2. Tap Read Status and hold a SATSCARD to the phone
3. Enter the SATSCARD CVC
4. Confirm the irreversible unseal warning
5. Tap Unseal and Sweep and hold the SATSCARD to the phone until Clench starts building the sweep
6. Repeat status read with a Tapsigner card if available

**Expected**
- Clench uses ISO-DEP Tap Protocol status, not Coldcard NDEF
- SATSCARD firmware/address/slot status appears when the card responds
- User-facing SATSCARD slot labels are shown as 1 through 10, not protocol indexes 0 through 9
- Funding/sweep screens warn that after slot 1 is unsealed, the printed QR should no longer be trusted for receiving
- Sweep requires CVC entry and explicit confirmation that unseal is irreversible
- When send authentication is enabled, authentication succeeds before NFC unseal mode is armed
- Clench rejects card/wallet network mismatches before unseal
- Clench rejects counterfeit/invalid SATSCARD certificate checks before sending the CVC-authenticated unseal command
- Clench verifies the unsealed private key matches the verified SATSCARD slot public key and payment address before signing
- Wrong CVC, unused slot, already-unsealed slot, tamper warning, or NFC errors are shown clearly
- On a valid sealed active slot with confirmed funds, Clench unseals, builds and signs a native SegWit drain transaction, then shows the exact destination, amount, fee, fee rate, vsize, and txid
- The prepared sweep never auto-broadcasts; the user must explicitly broadcast the reviewed transaction, with a second acknowledgement for unusually high fees
- A Tapsigner in this flow is rejected with a clear "only supports SATSCARD" message

---

## I. Transaction Tooling Tests

### I0. Immutable send review and stale-proposal invalidation
**Steps**
1. Build a small testnet send
2. Compare every recipient, amount, change output, exact fee, fee rate, vsize, and txid on the review screen
3. Tap Edit transaction, change the address, amount, fee rate, recipients, and selected UTXOs one at a time
4. Verify each edit discards the previously signed transaction
5. Try fees above 5%, above 50%, above 1,000 sat/vB, and above 1,000,000 sats

**Expected**
- Broadcast is possible only for the immutable signed proposal currently displayed
- Every draft edit requires rebuilding and reviewing a new transaction
- Fees above 5% require explicit acknowledgement; hard-limit violations are rejected
- No estimated 140-vbyte fee is shown as if it were the final fee

### I1. CPFP child transaction
**Precondition:** wallet has a spendable output from an unconfirmed transaction

**Steps**
1. Open the unconfirmed transaction detail screen
2. Tap Create CPFP Child Transaction
3. Confirm the send screen is in CPFP mode
4. Verify the selected UTXO is preselected, send-max is enabled, and the destination is a wallet address
5. Build and broadcast with a high enough child fee

**Expected**
- Clench spends the actual unspent output from the parent transaction
- The child transaction is shown for explicit confirmation before broadcast
- Offline mode disables broadcast actions

### I2. RBF cancel replacement
**Precondition:** wallet has an unconfirmed outgoing transaction that signals RBF

**Steps**
1. Open the outgoing transaction detail screen
2. Tap Cancel Transaction (RBF)
3. Read the warning
4. Enter a replacement fee rate and confirm

**Expected**
- Clench creates a replacement transaction back to the wallet
- The exact replacement inputs, outputs, fee, fee rate, vsize, and txid are reviewed before a separate broadcast action
- When send authentication is enabled, authentication occurs before replacement signing
- The app clearly states cancel is not guaranteed because the original may confirm first
- Non-RBF, confirmed, watch-only, and offline cases do not offer a misleading cancel action

### I3. Raw transaction import and broadcast
**Steps**
1. Open the wallet menu
2. Tap Broadcast Raw Transaction
3. Paste a signed raw transaction hex or import it from a file
4. Tap Preview
5. Verify TXID, vsize, total size, and RBF signal
6. Broadcast only after the preview matches expectation

**Expected**
- Invalid hex/base64 is rejected before broadcast
- Offline mode blocks broadcast
- Accepted broadcasts show the returned TXID

### I4. Saved payees and address verification
**Steps**
1. Open Send
2. Paste a BIP-21 URI with amount and label
3. Confirm the amount, label, network/script verification, and any payjoin warning
4. Save the payee
5. Select the saved payee on a later send
6. Try a mainnet address on testnet and a testnet address on mainnet

**Expected**
- BDK validates address checksum/network instead of relying on prefixes
- Network mismatch errors name the expected and supplied network
- Saved payees are scoped to the wallet and can be deleted
- Unsupported required BIP-21 parameters are rejected

---

## J. Recovery Wizard Tests

### J1. Recovery wizard entry points
**Steps**
1. Open the welcome screen
2. Open Recovery Wizard
3. Open a wallet, use the overflow menu, and open Recovery Wizard
4. Open Settings and use Recovery Wizard

**Expected**
- The same wizard is reachable from all three entry points
- Back returns to the previous screen without creating wallets or changing settings

### J2. Clench state backup import
**Precondition:** valid `clench-state-backup` JSON file

**Steps**
1. Open Recovery Wizard
2. Import the state backup file
3. Open Wallet List
4. Inspect restored wallets

**Expected**
- Seed phrases, passphrases, private descriptors, PINs, and biometric secrets are not imported
- Network, Electrum, Tor, lock, biometric, price lookup, and external fee lookup settings are not silently changed
- Hot wallets restore as watch-only until the matching seed phrase is restored separately
- Duplicate descriptors are skipped rather than duplicated
- Labels, UTXO notes, and non-secret settings are restored where present

### J3. Seed phrase restore path
**Steps**
1. Open Recovery Wizard
2. Choose Restore Seed Phrase
3. Enter a known seed phrase and optional passphrase
4. Compare fingerprint and first receive address against the original wallet

**Expected**
- Existing import warnings still appear for passphrase wallets
- A different passphrase produces a different valid wallet, not a wrong-password error
- User can verify fingerprint/address before trusting the wallet

### J4. Descriptor and multisig config restore path
**Steps**
1. Open Recovery Wizard
2. Choose Import Descriptor or Config
3. Import a Sparrow descriptor, Nunchuk-style config, or BSMS descriptor record
4. Open Wallet Info

**Expected**
- Wallet restores as watch-only
- Multisig policy, script type, descriptor, threshold, and cosigners are visible
- Descriptor backup warning states this is metadata, not spend authority
- First receive address can be compared against the source wallet

### J5. Cross-wallet guidance
**Steps**
1. Open Recovery Wizard
2. Read Sparrow, Nunchuk, and BlueWallet notes

**Expected**
- Sparrow guidance prioritizes output descriptors with key origins
- Nunchuk guidance prioritizes full wallet config/descriptor exports over isolated xpubs
- BlueWallet guidance calls out script/passphrase matching and watch-only xpub limits

---

## K. Privacy And Node UX Tests

### K1. Electrum server health check
**Steps**
1. Open Settings → Electrum Server
2. Run Server Health on the default public server
3. Switch to a custom server and run Server Health before saving
4. Enable offline mode and try again

**Expected**
- Health check reports target, connection mode, route, TLS pin state, server version if available, and tip height if available
- Public and custom server selections can both be checked
- Offline mode blocks active diagnostics
- Errors distinguish connection, Tor proxy, TLS, and certificate-pinning failures when possible
- Transaction and UTXO confirmation counts use the selected Electrum route for tip height

### K2. Tor routing diagnostics
**Steps**
1. Enable Route through Tor with a known SOCKS5 host/port
2. Enable Connect over Tor for a selected Electrum server
3. Run Server Health
4. Try a `.onion` server if available

**Expected**
- Server Health shows SOCKS5 host/port for Tor-routed clearnet
- `.onion` servers force Tor routing
- If Orbot/SOCKS5 is unavailable, the UI names the Tor proxy failure

### K3. Certificate pinning UI
**Steps**
1. Open custom SSL Electrum settings
2. Paste an invalid certificate string and tap Pin Cert
3. Paste or scan a valid base64 DER certificate or `electrums://host:port?cert=...` payload
4. Run Server Health

**Expected**
- Invalid cert text shows a clear validation error
- Valid cert text shows Certificate pinned
- Health check identifies TLS pin state
- Pin mismatch is surfaced as a certificate-pinning failure

### K4. External fee and price lookup opt-ins
**Steps**
1. Disable USD Balance and External Fee Estimates
2. Open Home and Send
3. Disable or misconfigure Electrum fee estimation in a test setup
4. Enable External Fee Estimates and repeat
5. Enable USD Balance and switch balance display to USD

**Expected**
- Price APIs are not queried unless USD Balance is enabled
- Fee fallback APIs are not queried unless External Fee Estimates is enabled
- Electrum fee estimation still uses the selected Electrum server
- With external fee lookup disabled and Electrum fee estimation unavailable, Clench uses static manual-safe defaults

---

## L. Release Trust Tests

### L1. Debug CI does not publish releases
**Steps**
1. Review `.github/workflows/android.yml`
2. Trigger or inspect a normal PR/master CI run

**Expected**
- CI builds, tests, and lints debug artifacts only
- `contents` permission is read-only
- No GitHub Release is created from a normal `master` push
- Debug APK artifacts have short retention and are not described as production releases

### L2. Signed release workflow
**Steps**
1. Review `.github/workflows/release.yml`
2. Confirm it runs only for `v*` tags or explicit maintainer dispatch
3. Confirm the tag is annotated and GitHub-verified, and matches `versionName`
4. Confirm the protected `release-signing` environment gates the signing job

**Expected**
- Release artifacts are built with the release keystore
- Every action reference is pinned to an immutable full commit SHA
- Tests and lint run before signing material is loaded
- Signing material is destroyed before attestation, artifact upload, and the separate publication job
- `SHA256SUMS`, `SHA256SUMS.txt`, and `RELEASE-MANIFEST.txt` are published with the APK
- APK signature verification is run before release creation
- Build provenance is attested and immutable releases prevent asset replacement

### L3. Reproducible-build helper
**Steps**
1. Run `bash -n scripts/release/reproducible-build.sh`
2. Run the script from a dirty worktree
3. Run the script without `keystore.properties`

**Expected**
- Shell syntax is valid
- Dirty worktrees are refused unless `CLENCH_ALLOW_DIRTY_REPRO=1`
- Missing release signing config is refused clearly
- Successful release rebuilds record commit, version, Gradle wrapper URL, dependency-verification hash, APK path, and APK SHA-256

### L4. Verification, threat model, and audit path docs
**Steps**
1. Review `docs/release/signed-release-verification.md`
2. Review `docs/release/reproducible-builds.md`
3. Review `docs/security/threat-model.md`
4. Review `docs/security/audit-path.md`

**Expected**
- Users can verify tag, checksum, APK signature, signer certificate digest, package id, version name, and version code
- Rebuild prerequisites and limits are explicit
- Threat model covers keys, wallet data, network privacy, hardware-wallet boundaries, and release artifacts
- Audit path names release blockers and high-risk code areas

---

## Suggested Test Priority
If time is limited, do these first:
1. A1 Existing normal wallet opens normally
2. B2 Correct passphrase import
3. B3 Decoy/wrong passphrase behavior
4. C1 Locked-state privacy
5. D1 Existing DB opens without destructive reset
6. E1 Release logging spot check
7. G1 Descriptor / BSMS round trip
8. G4 Small PSBT drill
9. H1 Tapsigner NFC status
10. I1 CPFP child transaction
11. I2 RBF cancel replacement
12. I3 Raw transaction import and broadcast
13. I4 Saved payees and address verification
14. J1 Recovery wizard entry points
15. J2 Clench state backup import
16. J4 Descriptor and multisig config restore path
17. K1 Electrum server health check
18. K3 Certificate pinning UI
19. K4 External fee and price lookup opt-ins
20. L1 Debug CI does not publish releases
21. L2 Signed release workflow
22. L4 Verification, threat model, and audit path docs

---

## Ship Gate
I would consider this hardening pass safer to continue with only if:
- standard wallets survive upgrade cleanly
- passphrase import still behaves exactly as intended
- decoy passphrase behavior remains intact
- locked state does not leak passphrase-wallet activity
- release logs do not expose wallet metadata
- no phantom recovered wallets appear
- transaction replacement, raw broadcast, payee, and address-verification paths pass manual testing before real funds are used
- recovery wizard restores only the intended data and users can verify addresses/fingerprints/policy before trusting recovered wallets
- Electrum diagnostics, Tor routing, certificate pinning, and external lookup opt-ins behave as configured
- production releases are tag-only signed artifacts with checksum/signature verification docs and no debug APK release publication
