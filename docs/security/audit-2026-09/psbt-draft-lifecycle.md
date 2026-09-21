# Unsigned PSBT completion ownership

A delayed createPsbt result could navigate with an obsolete proposal after recipient/amount edits, including editing away and back. Two callbacks before coroutine scheduling could also start duplicate requests. The baseline focused suite reproduced3 failures out of4 tests; the unchanged positive control passed.

Reserve loading synchronously and bind completion to a request ID, draft revision and full proposal fingerprint. Obsolete wallet requests cannot navigate or release a newer request's loading state. Failure/cancellation cleanup preserves explicit retries. The repository/native signing, review, broadcast, freeze and wallet-identity boundaries are unchanged; this does not claim observed unauthorized signing or fund loss.

Focused regressions cover edited/ABA drafts, positive completion, duplicates, wallet supersession, failure/retry and a callback starting another request. The first expanded failure-path run hit an unmocked Android logging method; retain that harness failure separately from the corrected run. Independent review, exact-head CI and changed-path physical acceptance remain required.
