# Mac runtime attempt — 2026-09-05

Source: owner-supplied Codex results and detailed `EMULATOR_EVIDENCE.md` content.
The original `.ips` files/binaries remain on the Mac and were not independently
read on this server. Do not treat this summary as a reproduced host diagnosis.

At `06c24c6e68f4fd04dd83dbf6017a2539799990b8`, the owner reports:

- Strict debug and Android-test APK build **PASS**, no remaining missing
  verification artifacts; requested application paths unchanged from `1d8b0a2`.
- Runtime instrumentation and authentication UI **NOT RUN**; no emulator retry
  was attempted after evidence inspection.
- Prior evidence preserved; results in `.audit-runtime/results-retry/`, bundle
  `.audit-runtime/results-retry.tar.gz` on the Mac.

## Host evidence and limits

Reported host: macOS 26.5.2 (25F84), ARM64, Mac17,9; emulator 36.6.11 build15507667;
API36 Google APIs ARM64 image revision7. Both crashed processes were ARM64 and
not Rosetta-translated.

The GUI/version probe aborted in Qt CPU-feature detection with a message about
NEON. That message does not establish that the hardware lacks NEON. The headless
boot raised SIGILL in host `init_cache_info +52`, before guest/app startup.
Reported matching-binary disassembly places `mrs x9, CTR_EL0` at that offset,
on a fallback from the `hw.cachelinesize` sysctl query. The recorded return
register is consistent with a failed query; the crash does not retain errno.
Current cache-line and CPU-brand queries inside the Mac Codex sandbox returned
`Operation not permitted`.

These observations support investigating host-query restrictions as a
contributor. They do not conclusively isolate the sandbox as the root cause.
There is no reported renderer-initialization failure or guest-image error.
Switching graphics backends or API images is therefore not an evidence-backed
fix for these crashes. The deprecated `swiftshader_indirect` launch flag is a
separate command-quality issue, not an established crash cause.

Codex's [official sandbox documentation](https://learn.chatgpt.com/docs/agent-approvals-security)
describes macOS Seatbelt enforcement, but does not establish the cause of this
specific crash. This is a host execution restriction/failure, not a model
cybersecurity-content refusal. No sandbox bypass or macOS security change is
being performed. Keep the Mac evidence for a possible upstream compatibility
report; no further unchanged boot attempt is needed for the remediation.

## Validation path

Continue the remaining UI validation on the existing disposable Linux-hosted
Android emulator, where the thirteen persistence/migration tests already passed.
The new UI tests use production `MainActivity`, Settings/Security navigation and
the real Android credential prompt; no authentication callback is mocked.
They use an explicit disposable-emulator guard and a public fixture credential,
and do not require real wallet funds or keys. Compilation is not execution.

The initial two UI cases cover seed/send cancellation, revisiting the screen,
successful OS authentication, changing only the selected gate, and activity
recreation. They do not yet prove the full pending-background/late-success,
no-authenticator or initial-onboarding UI matrix. Keep those remaining items
open until their own runtime evidence exists.
