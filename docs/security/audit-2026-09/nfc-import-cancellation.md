# Multisig NFC import cancellation

The multisig import screen shared its pending PIN array with the NFC worker. Cancellation zeroed that array without closing the worker connection, and callbacks could update a cancelled or superseded draft. This could corrupt an in-flight authenticated request or apply stale import state.

Each import now owns an attempt. A worker claims a private credential copy once; cancellation revokes that attempt and closes its ISO-DEP connection. The worker clears its copy in finally. Cancel/background/disposal clear the input and waiting state; late success/error callbacks cannot alter a newer attempt. Factory identity, network/path, derive proof, child-key proof and xpub validation remain unchanged. There is no automatic command retry. A delivered card command cannot be rolled back by cancellation.

Six session regressions cover credential ownership, duplicate callbacks, late results, late connection arrival, replacement attempts and cleanup failure. The existing45 protocol/14 PSBT tests are retained. Exact-final-head CI, independent review and changed-path physical acceptance remain gates.

This is a separate source-confirmed defect discovered while investigating intermittent Pixel/TAPSIGNER import failures. It is not a demonstrated root-cause fix for those failures. Diagnostic APK messages locate a failure in authenticated derive but did not retain the underlying IOException subtype or delivery state. The presence-delay experiment and diagnostic messages are not part of this production correction. Physical multisig signing remains UNVERIFIED.
