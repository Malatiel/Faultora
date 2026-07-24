# Changelog

All notable changes to Faultora are documented in this file.

## 0.3.0 — 2026-07-24

Reliability scenarios become expressive: data flows between steps, requests
run concurrently, and faults extend to the real network.

### Added

- Step output binding: `outputAs: name` exposes a step's response as
  `steps.<name>.status/body/headers` to later steps' `{{...}}` templates,
  including inside nested `body` and `headers` maps.
- Runtime scenario inputs: `faultora test --input key=value` binds declared
  inputs (with defaults and required-input enforcement) as
  `{{inputs.<name>}}`.
- Bounded parallel groups: `type: parallel` steps run child operations
  concurrently under the policy's `maxConcurrency`, with per-child retry,
  `expectError`, `outputAs`, events, and evidence; the group passes only when
  every child passes.
- Flagship reference scenario `fault-concurrent-duplicate.yaml`: two
  concurrent create-payment requests with one `Idempotency-Key` under
  injected latency must produce exactly one payment. The example payment API
  gained a concurrent executor, an atomic idempotency implementation, and a
  deliberately broken check-then-act variant that the same scenario detects
  end to end.

- Retry policies on `setup` and `execute` operation steps: exponential
  backoff with deterministic seed-derived jitter, capped attempts (max 10),
  retry only for retryable errors, and `OPERATION_RETRIED` journal events.
  Console and HTML reports show per-node retry counts, and every attempt
  counts against the policy request budget.
- Reference scenario `fault-retry.yaml`: a payment succeeds by retrying
  through a brief injected outage.
- Toxiproxy network fault provider (`faultora-faults-toxiproxy`):
  `network-latency`, `network-timeout`, `network-reset`, and
  `network-bandwidth` fault types over the same `FaultProvider` SPI, driven
  through the Toxiproxy admin API with no extra client dependency. Enabled by
  the new `faultora test --toxiproxy-url` option; `targetScope` names the
  proxy to poison, toxics carry unique `faultora-` names, and rollback is
  idempotent.

## 0.2.0 — 2026-07-24

First slice of the reliability engine: in-process fault injection.

### Added

- `faults:` scenario steps compile and execute with the built-in in-process
  provider: `http-latency`, `http-error`, and `http-response-loss`.
- Guaranteed exactly-once fault rollback through a hard-expiry watchdog,
  explicit fault-stop plan nodes, and an unconditional end-of-run sweep.
- `expectError` step field for operations that must fail under an injected
  fault while keeping their dependents runnable.
- Fault windows with fault-to-node attribution in console and HTML reports;
  `FAULT_INJECTED` and `FAULT_ROLLED_BACK` events in the run journal.
- Fault-type allowlist enforcement in the execution policy.
- Reference reliability scenarios and end-to-end tests: SLA under injected
  latency, and duplicate-payment prevention under response loss with an
  idempotency-key retry (the example payment API now honors
  `Idempotency-Key`).

### Fixed

- The compiled plan is now sorted topologically, so `dependsOn` references to
  later steps or across sections execute in dependency order instead of being
  silently skipped.
- A failed operation node now emits `NODE_FAILED` to the journal (previously
  it emitted `NODE_COMPLETED`, and console/HTML reports showed it as passed).

## 0.1.1 — 2026-07-23

Maintenance release focused on public documentation and report correctness.

### Added

- Copy-ready GitHub Actions integration example.
- Complete `faultora.dev/v1alpha1` scenario and assertion reference.
- Verified console and HTML report examples in the project README.
- End-to-end coverage for all report formats and JSON response assertions.

### Fixed

- Duration assertions now enforce both bounds when `min` and `max` are set.
- Console and HTML reports preserve assertion outcomes and messages emitted
  before node completion.
- Reusing an output directory no longer mixes the new run with an existing
  event journal.
- Evidence-capture documentation now reflects the CLI's active policy.

## 0.1.0 — 2026-07-23

First runnable technical preview.

### Added

- Java 21 CLI with `init`, `discover`, `validate`, and `test` commands.
- OpenAPI 3.x import and versioned YAML scenarios.
- HTTP connector, core assertions, execution engine, and report renderers.
- Environment-backed bearer-token resolution.
- Executable release JAR with merged service-provider metadata.
- End-to-end payment-service fixture and CI test suite.

### Security

- DNS-aware SSRF policy and per-request address pinning.
- Redirect-hop validation, downgrade rejection, and cross-origin secret removal.
- Bounded response streaming with a hard payload limit.
- Fail-closed credential resolution and reusable request-scoped secret copies.
- Policy-bounded evidence capture, sensitive-header filtering, content-type
  allowlists, JSON-path redaction, and bounded evidence storage.
- Apache HttpClient wire and header logging disabled.

### Known scope limits

- HTTP APIs only.
- Local single-process execution.
- Environment variables are the only built-in secret provider.
