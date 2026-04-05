# Security Hardening Notes

## Overview
This document describes the security hardening implemented in this release, addressing the risks identified in the security audit.

## Changes Made

### [S-1] Destructive Migration Prevention — `fallbackToDestructiveMigration()` Removed

**File:** `app/src/main/java/net/clench/wallet/di/AppModule.kt`

**Problem:** Room's `fallbackToDestructiveMigration()` was enabled in release builds, causing the database to be wiped and recreated if any migration was missing or failed. This could silently delete all wallet metadata and secrets on first migration failure.

**Fix:** Removed `fallbackToDestructiveMigration()` entirely. With the Room version used by this repo, the compatible fail-closed approach is to rely on explicit migrations only. If a future schema upgrade is added without a corresponding migration, Room throws instead of destructively resetting.

**Tradeoff:** If a future schema upgrade is added without a corresponding migration, builds will fail closed at runtime rather than auto-recover. This is intentional — it surfaces the missing migration as an error rather than silently wiping data.

---

### [S-2] Database Verification Failure — Fail-Closed in Release

**File:** `app/src/main/java/net/clench/wallet/di/AppModule.kt`

**Problem:** When SQLCipher database verification failed (e.g., due to a corrupted or incompatible key), the code would delete the database files unconditionally — even in release builds. This could cause irreversible wallet data loss.

**Fix:** Database deletion on verification failure is now restricted to debug builds only. In release, verification failure throws an exception instead of deleting data, surfacing a recovery-required state to the user.

**Tradeoff:** A corrupted encrypted database in release will now cause the app to fail rather than attempt self-healing. This is the correct behavior for a high-assurance Bitcoin wallet — silent self-healing could mask underlying key management problems.

---

### [S-3] Orphan Wallet Recovery — Auto-Recovery Disabled

**File:** `app/src/main/java/net/clench/wallet/ClenchApplication.kt`

**Problem:** `recoverOrphanedWallets()` attempted to reconstruct wallet records on startup. In the original code, that could misclassify passphrase-backed wallets and create misleading state. In a Bitcoin wallet, silent startup recovery is too risky.

**Fix:** Automatic orphan-wallet recovery is now disabled. The helper remains detection-only and does not insert wallet records into Room.

**Tradeoff:** Users no longer get silent startup reconstruction of orphaned wallet entries. That is intentional — recovery should be explicit and user-driven rather than inferred by the app.

---

### [S-4] Release Logging — Sensitive Data Gating

**Files:**
- `app/src/main/java/net/clench/wallet/data/repository/BdkBitcoinRepository.kt`
- `app/src/main/java/net/clench/wallet/ClenchApplication.kt`

**Problem:** Debug logs in release builds exposed sensitive wallet metadata including:
- Wallet IDs, names, and network counts
- Transaction IDs and tx counts (reveals wallet activity)
- Electrum server connection details (host reconnaissance)
- Derived addresses
- Balance information
- UTXO counts

**Fix:** Added `logSensitive` flag gated on `FLAG_DEBUGGABLE`. Sensitive logs are now wrapped in `if (logSensitive) { ... }` blocks. Release builds (non-debuggable) suppress all sensitive logging.

**Gated areas:**
- `BdkBitcoinRepository`: wallet list, sync connection details, txid fragments, balance/tx counts, addresses, UTXO counts, import details
- `ClenchApplication`: startup passphrase wallet wipe (wallet IDs), orphan wallet detection

**Tradeoff:** Operational logs that do not expose sensitive data (e.g., "sync complete", "wallet loaded OK") remain enabled in release for basic troubleshooting. Only data that could aid an attacker or reveal wallet activity is gated.

---

## Non-Completed Items

1. **Passphrase Warning Copy:** The import flow warning is much stronger now, but it still should explicitly say that Clench never stores the passphrase and that this is not a password-reset feature.

2. **Passphrase Wallet Creation During Normal Flow:** Verified that CreateWalletScreen does NOT expose passphrase creation. This is correct — passphrase creation only exists in the import (Advanced) path. No change needed.

3. **Secure String Handling:** JVM Strings are immutable and cannot be securely zeroed. The code notes this as a known JVM limitation. A production hardening would require a native library for off-heap key material handling. Not addressed in this cycle.

## Verification Checklist

- [x] `fallbackToDestructiveMigration()` removed so Room fails closed on missing migrations
- [x] DB verification failure no longer deletes database in release
- [x] Orphan recovery safe-passphrase detection prevents misclassification
- [x] Sensitive logging gated behind `FLAG_DEBUGGABLE` check
- [x] Passphrase is never stored (verified by code inspection)
- [x] Standard wallet creation does not expose passphrase creation (verified)
- [x] Import still supports passphrase via Advanced flow (verified)

## Risk Summary

| Risk | Status | Notes |
|------|--------|-------|
| Destructive migration on schema upgrade | Mitigated | Fail-closed in release |
| Database deletion on corruption | Mitigated | Fail-closed in release |
| Passphrase wallet misclassification | Mitigated | Safe detection, skip recovery |
| Sensitive data in release logs | Mitigated | Gated behind debug flag |
| Passphrase storage | Eliminated | Not stored; mnemonic only |
| Silent passphrase wallet creation | Eliminated | Advanced path only |
