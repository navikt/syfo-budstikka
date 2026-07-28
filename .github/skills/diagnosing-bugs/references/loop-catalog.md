# Feedback-loop catalog

Use the first viable loop, then tighten it. Prefer a failing focused test:
`./gradlew test --tests <test>` or Ktor `testApplication`. If that is unavailable,
use a curl/HTTP script, replayed captured event, throwaway harness, property/fuzz
loop, bisection harness, differential loop, or finally the human-in-the-loop
template `scripts/hitl-loop.template.sh`.

A loop must exercise the actual path and assert the exact symptom. Preserve its
command, relevant output, and exit code as evidence; do not substitute a broad
green build for a red-capable reproduction.
