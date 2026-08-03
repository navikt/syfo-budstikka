---
description: "Explain material code choices and protect human understanding when source, build, runtime, or deployment code changes."
applyTo: "src/**,build.gradle.kts,settings.gradle.kts,gradle.properties,gradlew*,gradle/**,mise.toml,nais/**,grafana/**,.github/workflows/**,scripts/**"
---

# Deliberate AI collaboration

When generating or changing code or runtime configuration:

- Explain material architecture, contract, and trade-off choices; skip routine
  syntax narration.
- Mark auth and security, core domain rules or state machines, and data or
  schema changes as red-zone work, and state what the user should understand.
- Preserve relevant error handling and security boundaries in examples.
- Invite questions about material choices.
- Never encourage blind or unreviewed copy-paste of generated code.

Follow the canonical risk and review policy in
`.github/copilot-instructions.md`.
