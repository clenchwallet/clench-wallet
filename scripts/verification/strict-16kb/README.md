# Maintained strict 16 KB regression — issue #99

This companion check addresses the known packaged JNA ELF/RELRO regression.
It **does not execute Android and cannot close issue #99 on its own**. It is
not a dependency change, a new release gate or new hardware-signing acceptance.

## Static companion

Run with Python 3, without additional packages:

```sh
python3 -B scripts/verification/test-jna-16kb.py
python3 -B scripts/verification/check-jna-16kb.py \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --output build/reports/strict-16kb/static.json
```

`--aar path/to/jna.aar` can independently examine an input AAR, but does not
check APK packaging. APK mode requires uncompressed, 16-KB-ZIP-aligned native
members (the existing Clench packaging policy), plus both shipped 64-bit JNA
libraries. For those two JNA libraries it verifies little-endian ELF64 machine
identity, LOAD alignment/congruence, a retained GNU_RELRO segment, and its
16-KB-aligned end within a writable LOAD. It deliberately does not extrapolate
JNA's internal ELF check to unrelated libraries or 32-bit runtime support.
Archive and payload hashes and inspected headers are included in its receipt.

The historical 5.14.0 AAR fails specifically because ARM64 RELRO ends inside
a 16-KB page; the repaired 5.19.1 AAR passes. This is a static negative control,
not a newly executed reproduction of the historic Android crash. The Python
self-tests cover both architectures, boundary failure, absent RELRO, missing
ABI, invalid/truncated metadata, duplicate ZIP entries, and ZIP misalignment.

## Runtime completion still required

Use the established Mac automation/evidence from `clench-16kb-baseline-20260923`
and `clench-jna-16kb-repair-20260923` to build a maintained, bounded runner.
Prefer external automation of the unchanged release-mode APK; retain any test
instrumentation as explicitly separate coverage. No production credentials or
hardware-wallet operations are involved. The prepared runner must:

1. Select an explicit disposable accelerated official Android 15-or-newer
   16-KB emulator. Record image package/revision, ABI, acceleration, fingerprint
   and `getconf PAGE_SIZE=16384`.
2. Force and verify `bionic.linker.16kb.app_compat.enabled=false` and
   `pm.16kb.app_compat.disabled=true` before starting the target process.
   Recheck after any emulator restart. A fallback-enabled run cannot pass.
3. Exercise offline generation/import, real JNA/BDK receive-address derivation,
   encrypted-database creation, close/reopen and force-stopped process restart.
   Compare the actual wallet identity/address across restart, not only launch.
4. Use a release-mode artifact. Bind source, APK and fixture hashes. Keep test
   signing separate from production signing; reusing a verified public APK is
   also acceptable. Do not silently substitute a debug build.
5. Retain bounded first-failure native/linker/crash evidence and a machine-readable
   result. Fail on incomplete execution, timeout, wrong environment or missing
   persistence evidence. No automatic repetition that masks the first failure.
6. Document the trigger, owner, timeout and retention. Keep this nonblocking
   unless a separate review deliberately changes release policy. A practical
   initial trigger is an explicit maintained command before relevant native
   dependency updates, with CI artifact retention when supported.
7. Demonstrate old-versus-repaired discrimination, reusing the original raw
   execution receipts when adequate. Do not recreate successful hardware or
   routine unchanged-path emulator acceptance just for bookkeeping.

Combine runtime receipts with the static companion and verify exact-final-source
or source-equivalence evidence before marking issue #99 complete. There is no
runtime pass in this initial server-side contribution.

References: [issue #99](https://github.com/clenchwallet/clench-wallet/issues/99),
[Android page-size guidance](https://developer.android.com/guide/practices/page-sizes),
and [the shipped repair's evidence](../../../docs/security/jna-16kb-compatibility.md).
