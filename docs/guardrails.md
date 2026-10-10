# Guardrails: code quality checks

Static checks wired into the Gradle build. Quick start for an agent:
`./gradlew check` must be green after any change; run `spotlessApply` before
hand-editing so the formatter does not overwrite manual line breaks.

## Tools

| Tool | Version | Where configured | When it runs |
|------|---------|------------------|--------------|
| Spotless (Palantir for Java, ktlint for build scripts) | plugin 8.10.2, ktlint 1.4.0 | root `build.gradle.kts` (`// === Guardrail: spotless ===`) | manually `spotlessApply` / `spotlessCheck` |
| Checkstyle (OpenJDK style) | 14.3.0 | `config/checkstyle/openjdk_checks.xml` + `suppressions.xml`, root `build.gradle.kts` | in `check` |

## Order of authority

Spotless is authoritative for formatting. The Checkstyle configuration is
written to accept Palantir output: line length 120, import groups
java/javax/org/com with static imports at the bottom, K&R braces, wrapping
before operators and dots. If a Checkstyle formatting rule ever disagrees with
what Spotless produces, the rule in `config/checkstyle/openjdk_checks.xml` is
relaxed - never the code, and never Spotless.

## OpenJDK template deviations

The Checkstyle config is the OpenJDK 14.3.0 template with deliberate
deviations so it accepts Spotless output and the project's existing content:

| Rule | Template | Here | Why |
|------|----------|------|-----|
| `charset` | `US-ASCII` | `UTF-8` | sources contain UTF-8 (em-dashes in comments); US-ASCII crashed the parser |
| `OperatorWrap` | wraps `=` to the next line | assignment tokens removed | Palantir keeps `=` at end of line |
| `NeedBraces` | requires braces on `case`/`default` | tokens removed | Palantir keeps single-statement switch cases unbracketed |
| `Indentation` | enforces 4/8 spaces | removed | Spotless is the indentation authority |
| `ConstantName` | `^[A-Z0-9]+(_[A-Z0-9]+)*$` | also allows `log` | SLF4J idiom |
| `RegexpSinglelineJava` (ASCII-only) | error | removed | Java 21 defaults to UTF-8 (JEP 400); rule guards a build constraint this project does not have |
| `MethodName` | `^[a-z]{2,}[a-zA-Z0-9]*$` | `^[a-z][a-zA-Z0-9]*$` | OpenJDK variant rejects spec-style test names like `aCitationToAShownFragmentIsSupported` |

## Commands

- `./gradlew spotlessApply` - format everything (Java + build scripts) before hand-editing.
- `./gradlew spotlessCheck` - verify formatting without changing files.
- `./gradlew check` - Checkstyle + tests (must be green).

## Reports

- `build/reports/checkstyle/main.html` - Checkstyle violations.