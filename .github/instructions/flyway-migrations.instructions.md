---
description: "Applies to Flyway migration files to preserve the append-only versioned migration history."
applyTo: "src/main/resources/database.migration/**"
---

# Flyway migration invariant

Applied versioned migrations (`V*__*.sql`) are append-only and must not be
edited. Add a new versioned migration for any change.
