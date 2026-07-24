# ADR-009: In-process fault provider for the first fault-injection slice

## Status

Accepted

## Context

Fault injection with business-invariant checking is Faultora's differentiator
(roadmap M2), and the data model was already scaffolded: the `FaultProvider`
SPI, `FaultStartNode`/`FaultStopNode` plan nodes, and
`FAULT_INJECTED`/`FAULT_ROLLED_BACK` events existed but nothing executed them.
The first executable slice had to choose a fault mechanism:

1. **Network-level faults** (Toxiproxy between Faultora or the target and its
   dependencies) exercise real network behavior but require Docker or another
   proxy deployment, complicate CI, and introduce clock-skew ambiguity in
   fault-window attribution across processes.
2. **In-process faults** act on Faultora's own outbound requests inside the
   CLI process: latency before send, rejection before send, and delivery with
   a discarded response.

The flagship invariant — "a duplicate payment is not created when a response
is lost" — structurally needs a request that reaches the target while the
client observes a failure, followed by a retry with the same idempotency key.

## Decision

- Ship an **in-process fault provider** (`faultora-faults-local`) first, with
  three fault types: `http-latency`, `http-error`, and `http-response-loss`.
- Implement it as a **connector decorator** (`FaultInjectingConnector`), so the
  HTTP connector and its security policies stay untouched.
- Injected failures surface as **normalized errors with `FAULT_*` codes**; the
  provider never fabricates target responses, so evidence cannot be mistaken
  for target behavior.
- Guarantee rollback in the engine's `FaultSession`: rollback is exactly-once
  under races between a fault-stop node, the hard-expiry watchdog (daemon
  thread), and the unconditional end-of-run sweep. The provider additionally
  stops matching requests after expiry even before rollback runs.
- Model the scenario surface as `faults:` steps with `faultType`, `duration`,
  `targetScope`, and `params`, plus an `expectError` flag on operation steps.
  The surface stays in `faultora.dev/v1alpha1` and may still change.
- Attribution of nodes to fault windows uses half-open interval overlap of
  journal timestamps under the single local JVM clock, and is reported as
  overlap, not causation.
- Because faults only affect Faultora's own traffic, the CLI allows the
  provider's capabilities by default; the `TargetPolicy` fault allowlist is
  still enforced by the plan compiler for other embedders.

## Rejected alternatives

- **Toxiproxy + Testcontainers first**: requires Docker in every environment
  that runs the suite, and cross-process clocks make exact fault-window
  attribution fuzzy. It remains the intended M2/M3 mechanism for genuine
  network faults and slots into the same `FaultProvider` SPI.
- **Fabricated HTTP error responses** (synthetic 503 bodies): evidence would
  be indistinguishable from real target behavior in reports.
- **Retry-node support in the same slice**: the duplicate-payment invariant is
  expressible with an explicit second step plus `expectError`, which avoids
  committing to retry semantics prematurely.

## Consequences

- Reliability scenarios run with zero external dependencies, locally and in CI.
- The provider cannot exercise the target's own dependency failures; that
  stays a roadmap item and is documented as such.
- The engine constructor gained an optional fault-provider map; the two-arg
  constructor is preserved for compatibility.
