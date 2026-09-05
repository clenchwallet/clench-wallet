# Signing-secret scope remediation

SC-01 remains open. Read-only GitHub metadata inspection on 2026-09-05 found
the four signing entries at repository scope and none in `release-signing`.
No secret values were read, and there is no evidence of compromise. A source-free,
approval-gated signing job does not prevent another authorized workflow writer
from referencing repository-scoped secrets in a different job.

## Maintainer action needed

The original four values must be available on a trusted maintainer machine:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

GitHub returns names and timestamps, not existing values. Never create a workflow
that prints, uploads, or otherwise exports those values to recover them. Never
generate a new signing identity simply to complete this move; installed APK
upgrades depend on the pinned signer.

1. Confirm the existing `release-signing` environment retains its intended
   approval and deployment-branch restrictions. If these cannot be verified,
   stop the migration before removing working repository entries.
2. Using the original values directly from secure maintainer storage, create
   the four same-named secrets in that protected environment. Do not send values
   through chat, shell command-line arguments, logs or repository files.
3. Confirm all four environment entries were created. Metadata alone cannot
   prove the values are correct; the maintainer must verify their source and
   the existing keystore's certificate against the release signer pin locally,
   without signing or publishing a new application merely as a test.
4. Once original-value continuity is established, remove the four repository
   copies. Keeping both copies leaves the broad scope in place.
5. Run the read-only scope verifier from an authorized session:

   ```bash
   python3 -B scripts/release/verify-signing-secret-scope.py
   ```

The verifier uses only names/timestamps and fails closed on missing/partial API
responses, timeouts, absent environment entries, or remaining repository copies.
Its synthetic tests run in CI without GitHub credentials. It does not verify
secret values or environment approval policy, and does not mutate anything.
Record the successful metadata check and maintainer continuity confirmation in
the remediation tracker before calling SC-01 resolved. Do not restore broad
repository copies as an automatic response to a subsequent signing failure.
