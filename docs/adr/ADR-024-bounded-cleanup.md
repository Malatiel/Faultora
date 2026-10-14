# ADR-024: A separate budget for declared cleanup

## Status

Accepted

## Context

Cancellation and the scenario deadline stop execution, but cleanup still owns
what the scenario created. Individual connector timeouts did not bound the
whole cleanup phase: several slow operations could each consume a complete
timeout, and a cleanup wait could defer them indefinitely in practice.

Fault rollback has a separate safety obligation. Discarding the final sweep
because a database cleanup or wait spent its time would leave an injected fault
active. Its hard-expiry watchdog and end-of-run sweep must remain enabled.

## Decision

- Declared cleanup operations and cleanup waits share one monotonic budget
  beginning at cleanup entry, independent of the main execution deadline and
  cancellation flag. Scenario and target-policy duration limits bound the main
  execution phase; cleanup has its own safety allowance. The default is 30
  seconds for CLI and runner, which use
  the same engine composition. Embedding callers can supply a positive
  `cleanupTimeoutMs` to `LocalEngine`; nonpositive values are rejected.
- Each operation receives the remaining cleanup budget through the existing
  connector context. Its own node and connector timeouts may only narrow it.
  Input resolution and preparation consume the same phase budget; it is
  checked again before transport. Cleanup operations remain single attempts.
- Once the phase budget is spent, the engine sends no more cleanup requests.
  Every unfinished obligation receives a failed node result with category
  `TIMEOUT` and code `CLEANUP_DEADLINE_EXCEEDED`. The existing journal events
  record the failure counts and phase completion, followed by the run verdict.
  A cleanup failure prevents `PASSED`; an already cancelled run remains
  `CANCELLED` with cleanup failures visible in its result and journal.
- A failed individual cleanup operation does not suppress subsequent
  obligations while phase time remains.
- The final fault rollback sweep runs regardless of declared cleanup's
  outcome. It is outside this phase budget and retains each provider's I/O
  limits. An existing caller interrupt is cleared for cleanup and rollback,
  then restored, as required for interruptible management clients.
- Scenario, result, journal and connector SPI schemas stay unchanged.

## Rejected alternatives

- Restarting a timeout for every cleanup node leaves aggregate execution
  unbounded by the phase limit.
- Reusing the spent main deadline or cancellation flag prevents the cleanup
  that cancellation is supposed to preserve.
- Cancelling a worker future and returning while arbitrary cleanup keeps
  running would leave ownership and later journal writes unresolved.
- Skipping fault rollback at the cleanup deadline trades a shorter wait for
  an active injected fault. A run must still attempt that rollback.

## Consequences

The phase bound is cooperative for in-process callbacks. The HTTP connector
actively cancels transport, including body reads, at the supplied total
timeout; arbitrary callbacks ignoring timeouts or interruption cannot be
forcibly stopped by deadline propagation. The cleanup phase bound is not an
end-to-end shutdown bound: rollback and resource closing remain separate.
Forced process termination can still interrupt cleanup or rollback, and the
external-fault recovery limitation in ADR-010 remains.

Rollback attempts are not confirmation of recovery. A confirmed provider
failure or a watchdog rollback still in flight after session shutdown makes
the terminal outcome an infrastructure error. Other cleanup and rollback
obligations continue; raw provider exception messages are not exported.

Regression tests cover shared operations/waits, cancellation, elapsed main
deadlines, shorter child limits, failure reporting, preserved rollback and
real HTTP body disconnection. No new scenario field or command-line option
is required for the default limit.
