<!-- Concern: guides repository changes | Non-concern: feature specifications | IO: none -->
# Working in Pyano

Read [coding standards](docs/standards.md) and [quality gates](docs/quality.md).
Use `annotated-tree --hidden --include-tests .` to navigate file responsibilities.

- Run `./check-source.sh` before finishing changes. Keep annotations and comments
  within its configured bounds; do not widen budgets to silence findings.
- For behavior changes, run relevant Android tests/builds when the required SDK,
  JDK, and native dependencies are available; report missing prerequisites.
- Keep audio callback work allocation-free and nonblocking. Preserve loop,
  recording, MIDI, and foreground-service behavior across lifecycle changes.
- Commit using the documented git-agent-verdict attestation flow. Hook/criteria
  changes need separate inspection and the tool's configuration bootstrap flow.
- Do not commit recordings, SoundFonts, signing material, or local credentials.

See [local sync](local-sync/README.md) for the first MIDI/upload prototype and its
limits. The [cloud plan](docs/cloud-plan.md) sets future direction.
