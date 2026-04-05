# Clench Wallet Manual Test Plan

## Goal
Validate the security hardening changes around migration behavior, passphrase import flow, recovery posture, and release logging without weakening the intended passphrase model.

## Scope
Covers commit `0e9c4c5` and adjacent hardening work.

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

## Suggested Test Priority
If time is limited, do these first:
1. A1 Existing normal wallet opens normally
2. B2 Correct passphrase import
3. B3 Decoy/wrong passphrase behavior
4. C1 Locked-state privacy
5. D1 Existing DB opens without destructive reset
6. E1 Release logging spot check

---

## Ship Gate
I would consider this hardening pass safer to continue with only if:
- standard wallets survive upgrade cleanly
- passphrase import still behaves exactly as intended
- decoy passphrase behavior remains intact
- locked state does not leak passphrase-wallet activity
- release logs do not expose wallet metadata
- no phantom recovered wallets appear
