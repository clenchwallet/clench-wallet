# Multisig NFC import cancellation

The multisig import screen shared its pending PIN array with the NFC worker. Cancellation zeroed that array without closing the worker connection, and callbacks could update a cancelled or superseded draft. This could corrupt an in-flight authenticated request or apply stale import state.

Each import now owns an attempt. A worker claims a private credential copy once; cancellation revokes that attempt and closes its ISO-DEP connection. The worker clears its copy in finally. Cancel/background/disposal clear the input and waiting state; late success/error callbacks cannot alter a newer attempt. Factory identity, network/path, derive proof, child-key proof and xpub validation remain unchanged. There is no automatic command retry. A delivered card command cannot be rolled back by cancellation.

Six session regressions cover credential ownership, duplicate callbacks, late results, late connection arrival, replacement attempts and cleanup failure. The existing45 protocol/14 PSBT tests are retained. Exact-final-head CI, independent review and changed-path physical acceptance remain gates.

This is a separate source-confirmed defect discovered while investigating intermittent Pixel/TAPSIGNER import failures. It is not a demonstrated root-cause fix for those failures. Diagnostic APK messages locate a failure in authenticated derive but did not retain the underlying IOException subtype or delivery state. The presence-delay experiment and diagnostic messages are not part of this production correction. Physical multisig signing remains UNVERIFIED.

## Independent review correction

Review of 968c9e3 found that a current NFC attempt could still target an obsolete row after same-screen draft edits. The ViewModel now owns the session and synchronously revokes/closes it before signer, metadata, device, configuration, QR-route or wizard-step mutations. Late success uses the ViewModel completion guard; status/error callbacks share the same revoked session. Main-thread UI cleanup only disables a revoked reader, preserving an intervening fresh reader. Phone-signer completion and initial saved-signer application also revoke before replacing draft data. No authenticated retry is introduced.

Nine actual ViewModel/session integration regressions cover removal before/at the target, pasted/device replacement, Back/preset reset, ABA edits, leaving the import step, QR entry, stale success/error rejection and fresh recovery. Together with six session and59 existing protocol/PSBT tests:74 passing, zero failures/errors/skips. These are JVM regressions, not physical signing/cancellation evidence. The original review failure remains recorded; independent delta review is required.
