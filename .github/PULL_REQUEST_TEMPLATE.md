## Description

<!-- What does this pull request change, and why? -->

## Changes

<!-- - `file/module`: What changed -->

## Issue

<!-- Closes #NUMBER / Relates to #NUMBER -->

## Verification

<!-- Paste fresh evidence from deterministic gates: command, relevant result, and exit code. -->

```text
./gradlew build   # exit: 0  (compile + ktlint + test)
```

## Checklist

- [ ] `./gradlew build` passes (compile + ktlint + test)
- [ ] No sensitive data is exposed, including tokens, credentials, national identity numbers, or other PII in logs
- [ ] If this used Grillmester/Kokk: fresh Grill-inspektor evidence is summarized for every R3/R4 slice and for an aggregate R3/R4 diff; an opted-in R0–R2 review uses the same complete boundary, with no duplicate final review for one slice
- [ ] If this was material upper-R2 direct Barista work without red flags: the optional Inspector review was offered; acceptance or deferral is recorded above
- [ ] Changed API or event contracts are coordinated with affected teams
- [ ] An ADR documents any hard-to-reverse decision (`docs/adr/`)
