## Agent skills

### Issue tracker

Issues live in GitHub Issues, created and edited via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles as-is: needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Code quality (guardrails)

Full description: `docs/guardrails.md`. Short:

- `./gradlew spotlessApply` - format before hand-editing (Spotless/Palantir for Java, ktlint for build scripts).
- `./gradlew check` - mandatory green run after any change (Checkstyle is part of `check`, plus tests).
- Spotless is authoritative for formatting; the Checkstyle config accepts its output.
- Tool versions: Spotless 8.10.2, Checkstyle 14.3.0, pinned inline in `build.gradle.kts`.
