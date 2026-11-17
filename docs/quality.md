<!-- Concern: documents source and commit gates | Non-concern: Android build prerequisites | IO: none -->
# Source and commit gates

This setup follows `ai-session-manager`: tracked configuration and hooks,
strict source checks, and a read-only standards review. Install the tools:

```bash
cargo install annotated-tree --version 0.7.0 --locked
cargo install comment-crusher --version 0.3.0 --locked
cargo install git-agent-verdict --version '^2.4' --locked
```

Enable in each clone (global Git settings are unchanged):

```bash
git config --local core.hooksPath .githooks
git config --local agent-verdict.runner codex
```

`./check-source.sh` runs strict annotations and comment-crusher with warnings as
errors. `pre-commit` runs the same script. Missing tools fail the check.
`commit-msg` requires verdict 2.4+ and a read-only review of
[standards.md](standards.md); no model is pinned in the shared hook.

Stage only intended changes, then use:

```bash
git agent-verdict attest --repo "$PWD" --intent "Describe the staged change"
git agent-verdict await --repo "$PWD"
git agent-verdict commit --repo "$PWD"
```

The Codex runner needs working local authentication. Fix every MAJOR and MODERATE
finding; MAJOR also requires re-review. Changing the staged snapshot invalidates
its attestation. The review does not automatically stage or commit changes.

## Annotation and comment policy

Annotations state Concern, Non-concern, and IO in at most 160 characters. Kotlin,
C/C++, XML, CMake, and properties are configured explicitly in addition to tool
defaults. XML declarations stay first; the annotation follows the declaration.
Directory `.annotation` files describe boundaries. Unsupported binary/data files
may use `<filename>.annotation` sidecars when a map entry is useful.

Comments, docstrings, and headers are limited to one line and 160 characters;
headers count toward the 35% ratio, with the tool's 200-code-character floor.
Documents are limited to 90 lines. Only the generated upstream `gradlew` and
`gradlew.bat` launchers are excluded, preserving their legal and maintenance text.
No authored-source exclusions or path-specific budget allowances are configured.

## Hook and criteria bootstrap

git-agent-verdict refuses to attest a staged change to its own `commit-msg`
hook or criteria file. Review those configuration changes independently and
keep them in a separate configuration commit, with no ordinary feature changes.
Run the mechanical checks first. The tool's documented bootstrap uses
`git commit --no-verify` for that configuration-only commit; ordinary changes
must use attestation. Setup itself does not create commits or a reviewed baseline.

No remote CI workflow is enabled by this local setup. See [Android build](build.md)
for application checks; the source gates do not replace tests or builds.
