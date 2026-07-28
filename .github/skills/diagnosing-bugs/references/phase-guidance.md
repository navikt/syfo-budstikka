# Phase guidance

Treat the loop as a product: make it faster through focused setup and reusable
Testcontainers, sharper through exact assertions, and deterministic with a
`Clock`, seeded RNG, isolated schema, and controlled network. Spend
disproportionate effort here. For flaky faults, raise reproduction rate with
repetition, parallelism, stress, narrowed timing, or injected delays.

If no loop can be built, stop and request the reproducing environment, a HAR/log/
Kafka record/trace, or permission for temporary production instrumentation. Do
not form hypotheses first.

The completion contract is one already-run script/test/curl command that is
red-capable, deterministic or high-rate, fast, and agent-runnable. In phase 2,
verify the exact reported failure and minimize it one factor at a time. In phase
4 prefer debugger/REPL, then targeted `LoggerFactory.getLogger(...)` logs; never
use PII even in MDC, exception text, or temporary logs. Profile performance with
Micrometer, `measureTimedValue {}`, or `EXPLAIN ANALYZE`, not log floods.

For phase 5, a correct test seam reproduces the real caller path. Create the
failing test, fix, pass it, then rerun the unminimized loop. Before delivery,
remove `[DEBUG-...]` logs and harnesses, document evidence and the hypothesis,
and raise missing seams or hidden coupling as ADR candidates after the fix.
