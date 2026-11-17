<!-- Concern: defines staged-code review criteria | Non-concern: product planning | IO: (staged diff) -> verdict -->
# Coding standards

Review the staged change and surrounding code needed to assess it. Report
concrete defects introduced by the change, without unrelated refactors or
speculative requirements.

## Correctness and ownership

- Implement the requested behavior while preserving supported callers and flows.
- Validate untrusted inputs at boundaries and report actionable failures.
- Keep state consistent across cancellation, reconnects, navigation, and concurrent
  requests. Clean up files, streams, coroutines, and native resources on failure.
- Preserve one shared engine across activity instances and the intended
  foreground-service lifecycle. Keep device discovery separate from MIDI parsing.
- Never block or allocate on the real-time audio callback. Preserve event ordering,
  NoteOff delivery, and the documented native synchronization boundaries.
- Preserve original recordings and associated assets across rename/delete/export
  operations; partial failures must remain visible and recoverable.
- Keep secrets, signing material, personal recordings, and SoundFonts out of Git.

## Maintainability and verification

- Prefer clear names, existing helpers, and the simplest implementation that
  handles the actual requirement. Avoid unrelated edits and redundant abstractions.
- Add meaningful tests for behavior changes, especially timing, persistence, input
  parsing, and security boundaries. Do not require implementation-mirroring tests
  or new tests for reversible, low-impact documentation/presentation edits.
- Run relevant tests/builds and source checks. Report unavailable Android/native
  prerequisites explicitly; do not present unrun checks as passing.
- Maintain Concern / Non-concern / IO annotations within 160 characters. Include
  hidden files and tests; explicitly configure languages missing from tool defaults.
- Keep each comment/docstring/header to one line and 160 characters, comment ratio
  at most 35%, and documents at most 90 lines. Generated upstream Gradle launchers
  are excluded to preserve their license/maintenance text; authored source is not.
- Move substantial operational explanations into focused docs rather than deleting
  important invariants or widening limits. Keep documentation accurate.

## Verdict

Pass when these criteria are met. Fail for specific actionable violations supported
by the staged diff; identify location and practical impact.
