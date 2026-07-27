# ADR-011: Repeat and eventually blocks with compile-time bounds

## Status

Accepted

## Context

Roadmap M2-01 asks for fixed and data-driven repeats, eventually/poll-until
blocks, and per-node and scenario deadlines. Parallel groups, retries, and
per-step timeouts shipped in 0.3.0; iteration, convergence, and a run-wide
deadline were the remaining control-flow gaps.

Every added form of repetition multiplies traffic against a system under test.
The execution policy already caps `maxRequests`, but that check runs at
compile time over a static plan. A loop whose length is only known while the
run is in flight would make that check meaningless, and a poll loop with no
budget would let a scenario hang instead of failing.

## Decision

- **Repeat groups** (`type: repeat`) take exactly one of `count` (1–100) or a
  **literal** `forEach` list (at most 100 items). Both are resolved during
  plan compilation, so `iterations × children` enters the request budget
  before any request is sent.
- **Eventually groups** (`type: eventually`) poll exactly one child operation.
  The poll budget is `min(1 + timeout / interval, 100)` and is charged to the
  request budget in full. `interval` defaults to `1s` and may not exceed
  `timeout`.
- A **failed request inside an eventually block is an unsatisfied poll**, not
  a node failure. Convergence, not the first response, is what the block
  asserts.
- **`until` conditions reuse the assertion providers.** They are not a second
  expression language: an `until` entry is an `assertionType` with the
  parameters already documented for that type, and its outcome counts towards
  the run's assertion totals.
- **Iteration identity is explicit.** Each iteration of a repeat child is
  recorded under `<step-id>:<index>`, and the plain step ID resolves to the
  last completed iteration, so both a specific iteration and "the final state"
  can be asserted. Iterations do not share step outputs.
- **Grouping steps schedule, they do not invoke.** `retry`, `expectError`,
  `outputAs`, `inputs`, and `operationId` on a group are validation errors
  rather than ignored fields.
- The scenario-level `timeout` bounds the whole run: no further node starts
  once it elapses, cleanup obligations are collected before execution starts
  so they always run, and the run fails with `SCENARIO_DEADLINE_EXCEEDED`.

## Rejected alternatives

- **`forEach` over a runtime expression** (`forEach: "{{inputs.items}}"`):
  the natural spelling, but the iteration count would be unknown when the
  policy budget is checked, so a scenario could quietly multiply traffic
  against a production-adjacent target. Deferred until the engine enforces the
  request budget at runtime as well as at compile time.
- **Unbounded polling until timeout only**: without a poll cap, a
  millisecond-scale `interval` turns a converging assertion into a load
  generator.
- **A dedicated condition language for `until`**: a second dialect to learn,
  test, and sanitize, for expressions that the assertion providers already
  evaluate against the same evidence.
- **Nested groups**: a parallel group inside a repeat multiplies concurrency
  and budget in ways that are hard to review in a diff. Groups stay flat until
  there is a concrete scenario that needs nesting.

## Consequences

- The worst-case request count of any scenario is still knowable from the
  compiled plan alone, which keeps `maxRequests` a real control.
- Data-driven repeats cover fixed matrices (currencies, amounts, regions) but
  not runtime-sized collections; the documentation states this limit instead
  of implying general iteration.
- Reports gained a second per-node counter (`CONDITION_POLLED`) alongside
  retries, and nodes can now carry several assertion outcomes, which the
  console and HTML renderers list individually.
