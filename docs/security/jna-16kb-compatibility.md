# JNA Android 16 KB compatibility repair

Scope: replace JNA 5.14.0 with the complete upstream 5.19.1 Android AAR. BDK
3.0.0-clench.1, SQLCipher 4.17.0, wallet logic, signing policy and schema are unchanged.

## Established production baseline

Published v0.3.32, source `7b577182064b3b61768e926aba5431e0d8948a79`, APK SHA-256
`1604f314347422852c05ed862149e2e90c775821869727b3fdec9ec42c3e28bf`, fails on
the official Android 15 ARM64 16 KB Google APIs image revision 5, fingerprint
`google/sdk_gphone16k_arm64/emu64a16k:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
Emulator 36.6.11 uses Hypervisor.Framework acceleration. `getconf PAGE_SIZE`
returns 16384; compatibility fallback is explicitly off (`bionic.linker.16kb.app_compat.enabled=false`,
`pm.16kb.app_compat.disabled=true`).

Both offline `Mnemonic.fromEntropy` generation and `Mnemonic.fromString` import
crash in JNA `JNI_OnLoad+136`, SIGSEGV/SEGV_ACCERR. The packaged JNA ELF's RELRO
ends at 0x36000; fault offset 0x36590 lies in writable data mapped read-only after
16 KB protection rounding. APK ZIP alignment alone does not detect this error.
Launch and empty encrypted Room database reopen pass. Persisted wallet acceptance
is blocked at native initialization. Full first/second tombstones and UI receipts
are retained in the baseline evidence directory `clench-16kb-baseline-20260923`.

## Selected dependency and source review

Upstream 5.17.0 completes the Android 16 KB fix; 5.18.x fixes subsequent Structure
locking/race issues, and stable 5.19.1 restores older Android support after 5.19.0.
References: [upstream changes](https://github.com/java-native-access/jna/blob/1a91122853f6ab6f1fb2a4a284a6cf2ed8af0a4d/CHANGES.md),
[16 KB initialization defect](https://github.com/java-native-access/jna/issues/1647).

The explicitly selected `net.java.dev.jna:jna:5.19.1@aar` replaces the transitive
5.14.0 artifact; it does not mix a new native library with old JNA classes.
Gradle regenerated only JNA's lock entry (including its explicit compile scopes).
Strict SHA-256 metadata pins the AAR and POM. Maven Central publishes SHA-1
sidecars, which match; the AAR independently matches `dist/jna.aar` at immutable
upstream commit `1a91122853f6ab6f1fb2a4a284a6cf2ed8af0a4d` byte-for-byte.
Build-tool-only JNA 5.6.0 is unchanged and is not the shipped runtime JNA.

The native inventory preserves all other owners and payloads. JNA's vendored
libffi still declares 3.4.4, with an AArch64 CFI-label placement patch
`a7e40c6f6aac189b944ff0a5639a71d03b261abf`; its tree is now
`b74ce7de3113e5081b612260bcef0c1c05790d69`, not the unmodified upstream tree.
The Android Makefile passes both max-page-size and common-page-size 16384.
Dated OSV queries for JNA 5.19.1 and the libffi base commit returned no matches.
This does not establish full C advisory coverage or independently reproduce
upstream's native binaries. Those existing assurance limits remain explicit.

## Acceptance gates

Strict release build, JVM/lint, native inventory, deterministic SBOM, live Maven
and Cargo gates, APK ZIP plus internal ELF checks, and disposable release-mode
16 KB / focused 4 KB runtime checks are required. Runtime acceptance must include
offline creation/import, receive-address derivation, encrypted DB persistence,
background reopen and fresh-process wallet reopen. A fallback-enabled pass is
not native 16 KB acceptance. Candidate runtime results are pending; this document
does not yet claim a successful repair. Existing independent source review and
protected release gates remain required before publication. Unchanged hardware
paths do not require repeated acceptance for this scoped dependency update.
