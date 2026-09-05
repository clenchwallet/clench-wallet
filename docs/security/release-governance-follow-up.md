# Release governance follow-up

The remediation does not merge code, publish a release, or change remote
protection rules. These are separate from source-level fixes.

## Stable website check

The `Website CI` workflow now runs its existing `verify` job on every PR to
`master` and every `master` push. Removing the path filter avoids a required
status remaining pending forever on non-website changes. The eleven existing
website tests and generated-output check remain unchanged.

Read-only ruleset inspection on 2026-09-05 found that protected `master` requires
`build` and `analyze`, but not `verify`. After this workflow reaches protected
master and reports successfully, add its exact GitHub Actions status to the
existing required checks while retaining strict freshness and all existing
checks. Do not replace the ruleset wholesale or remove required checks to make
a PR pass. Until that separate activation, the website job is automatic but
**not a branch-protection requirement**.

## Independent approval

Current master rules require PRs and resolved review threads, but zero approving
human reviews and no last-push approval. The saved release-environment inspection
also records a sole reviewer with self-review permitted. These are not proof of
independent human review, even when automated checks and AI reviews pass.

Requiring another human review or disabling release self-review needs a real,
available second maintainer with appropriate repository/environment access.
Do not fabricate an independent reviewer, count another AI session as a human,
or turn on requirements that the current sole maintainer cannot satisfy.
Before enforcing this, the owner must designate the independent maintainer and
confirm the operational approval/recovery arrangement. Keep the residual risk
explicit until then. This does not prevent moving signing secrets into the
existing protected environment with the original values.
