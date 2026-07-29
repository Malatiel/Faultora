# Changelog

All notable changes to Faultora are documented in this file.

## 0.7.0 — 2026-07-29

The events release. A scenario can now publish a command, observe the events it
caused, and prove a claim that spans two protocols — which is the first time
this tool says anything about a distributed system rather than about one
request.

### Added

- **AsyncAPI 3.0 import** (M3-01). Servers, channels, operations, messages,
  schemas, correlation locations, and Kafka bindings become catalog entries.
  AsyncAPI states an operation's direction from the *application's* point of
  view, so the importer inverts it: a channel the application receives on is
  one a run publishes to, and that operation is the mutating one. Getting this
  backwards would classify a write as a read and let it past the execution
  policy unasked — ADR-015 records the rule and why it is enforced in exactly
  one place. AsyncAPI 2.x is refused by name rather than read under 3.0's rule,
  which would reverse every operation in the document.
- **Kafka connector** (M3-02). Publish and observe are ordinary `operation`
  steps, so retries, deadlines, dependencies, `eventually`, and generated
  payloads work on them unchanged and the scenario contract gains no new step
  type. A publish waits for the broker's acknowledgement. An observation is
  bounded below by the run's own start, above by a wait the run's request
  timeout caps, and in volume by a message count and an evidence budget.
- **Observations are repeatable by selection.** A step declares which messages
  it is about — by key, header, or payload field — and its assertions run over
  those. Two iterations of a repeat block read the same window and each see
  only their own messages. ADR-014 has the full reasoning, including the design
  that was built first and was wrong.
- **A run leaves nothing on the broker.** Partitions are assigned directly and
  no offset is ever committed, so no consumer group is created and an
  interrupted run has nothing to clean up.
- **Four event assertions** (M3-04, event half): `event-count`,
  `event-unique`, `event-correlation`, and `event-sequence`. "The event
  eventually appears" is `event-count min: 1` inside an `eventually` block —
  the polling already existed, and an appearance is a count that stops being
  zero.
- **`--asyncapi`, and `--openapi` alongside it.** A run compiles against the
  union of its descriptions. A name claimed by two documents is an error rather
  than whichever was loaded second.
- **`MESSAGE_PUBLISHED` and `MESSAGES_OBSERVED` journal events**, carrying
  coordinates and digests. An observation records both what its window
  contained and how much of it the step's selector claimed, so a scenario
  missing a selector on a busy channel is visible in the report.
- **`examples/payment-worker`**: a Kafka consumer that settles a payment once
  however many times the command arrives, and a variant that does not. The
  published scenario passes against the first and fails against the second — a
  reliability test that has never failed proves nothing.
- The Kafka connector is constructed only when the catalog has a Kafka target,
  so an HTTP-only run opens no broker client.

### Changed

- Applying an evidence policy moved into the SPI, so a connector whose evidence
  is not an HTTP response applies the same rules to it. Message payloads obey
  `captureBodies`, `maxBodyBytes`, and `redactPaths`; message headers obey
  `captureHeaders` and the denylist, because a token in a message header is as
  much a secret as one in an HTTP header. A selector still reads the message as
  it arrived: a policy that withholds payloads must not change which messages a
  scenario sees.
- The destination policy moved into `faultora-net` and now decides for every
  connector. Kafka bootstrap hosts face the same private-range refusal HTTP
  does. What Kafka cannot do is pin the addresses it verified, because its
  client resolves its own brokers — `docs/SECURITY.md` states the asymmetry
  rather than implying parity.
- A step's protocol evidence is published to later steps under
  `steps.<name>.protocol`, namespaced so a protocol adding a `status` of its
  own cannot displace the response one.
- Toxiproxy proxy names are percent-encoded into the admin path, so a
  scenario-supplied name containing a separator addresses the proxy that bears
  it and never a different admin resource. Toxic names carry a per-run token,
  so a toxic leaked by an earlier run cannot collide with this one's.

### Fixed

- `FaultSession` marks itself closed before its end-of-run sweep. A fault
  injected while a run was ending could previously be registered after the
  sweep had passed and stay injected; now whichever of the two sees the other
  rolls it back, and `start` refuses rather than returning a fault it cannot
  stand behind.
- A literal `waitMs` longer than the whole run's budget fails plan compilation
  instead of being silently shortened at run time.

### Documentation

- `docs/SECURITY.md` states what the destination policy actually decides,
  including that an allowlist replaces private-range classification rather than
  adding to it, and that classification is a property of each connector.
- `docs/SCENARIO_REFERENCE.md` documents event operations, the four event
  assertions, and the hazard of retrying an operation that is not idempotent —
  which this tool exists to find rather than to prevent.
- ADR-014 (event observation) and ADR-015 (AsyncAPI direction) are new;
  ADR-001 and ADR-005 are amended for the new modules and event types.

## 0.6.0 — 2026-07-29

The debt release: everything the documentation already claimed is now true.
No capability here is new to the roadmap — each one was owed by a milestone,
an ADR, or an architecture principle that had been stated and not built.

### Added

- Response-schema assertions (`assertionType: schema`), owed since M1-06. The
  schema is resolved during plan compilation with every `$ref` expanded, so an
  assertion against a contract the catalog does not declare fails before the
  run starts, and the assertion itself never reaches back into the catalog. An
  operation declaring several responses must be told which one to check.
- `SchemaValidator` honours `additionalProperties: false`, so a response
  carrying a field its contract does not declare fails, and `nullable: true`
  in both its OpenAPI 3.0 and JSON Schema 2020 spellings.
- `SchemaCatalog.inline` expands references into a self-contained schema, and
  stops at a repeat rather than looping on a self-referencing definition.
- The example smoke scenario asserts its response against the published
  OpenAPI document, and the example `Payment` schema declares which of its
  fields are required.

- Reference scenarios for the two recovery cases M2-05 asks for: a target that
  restarts mid-run, which the scenario survives by retrying, and a setup that
  half-succeeds, whose cleanup still disposes of what was created.
- `--allow-destructive` permits operations the description classifies as
  destructive. Architecture principle 7 has always said such operations
  require explicit policy; until now there was no way to give it, so a cleanup
  that deletes what its setup created could not run at all.
- `--allow-extension <class>` names a non-built-in extension a run may use.

### Fixed

- `TargetPolicy.maxDurationMs` bounds the run. It was declared and applied
  nowhere: the CLI announced a five-minute budget while a scenario naming a
  longer deadline simply exceeded it. A scenario without a deadline now
  inherits the policy's, and one asking for longer is refused rather than
  silently shortened.
- Numbers with an excluded bound are generated at a value the schema accepts.
  Only the integer path normalised exclusivity, so a rate declared above 0.5
  was sent as exactly 0.5 — and the project's own validator rejected it.
- An exception thrown inside a parallel, repeat, or eventually group fails
  that group instead of escaping the run loop. It previously took the terminal
  event and the whole cleanup phase with it.
- A node whose dependency did not pass is recorded as `SKIPPED`, with the
  reason, instead of disappearing from the report. JUnit output carries it as
  a skipped test case, where CI already knows the shape.
- JUnit timings are formatted independently of the platform locale. On a
  machine with a comma decimal separator the report was `time="0,026"`, which
  a CI parser rejects.
- `Ctrl+C` cancels the run instead of killing it: injected faults are rolled
  back, cleanup obligations are carried out, and the journal gets its terminal
  event. A signal that cannot be caught is still outside anyone's reach.
- A `wait` step declared in `cleanup` ran in the main phase instead of the
  cleanup one, because compilation treated it the same in every section. A
  wait that exists to outlive an injected fault therefore expired the fault
  before the step it was meant to interfere with, quietly turning a failing
  scenario into a passing one.
- Generated requests prefer an example declared on the media type, where most
  OpenAPI documents put it, instead of only one written inside the schema.
  Keywords beside a `$ref` now refine what it points at rather than being
  dropped, which is how the example reaches a shared component schema without
  altering it.
- The compile-time feasibility check derives its seeds exactly as the run
  does, and covers every iteration a repeat child will run under. A schema
  with alternatives could otherwise pass compilation on one branch and fail
  mid-run on another.
- `ExtensionPolicy.allowedExtensions` is enforced when extensions are
  discovered: an implementation outside the project's own package is refused
  and named unless the run allows it.

### Changed

- `AssertionContext` carries the resolved schema an assertion checks against.
  A provider that needs none ignores it; the two-argument constructor is
  unchanged.

## 0.5.2 — 2026-07-28

Attribution release; no functional changes.

### Fixed

- The executable JAR carried a generated `META-INF/NOTICE` whose header read
  "in this case for " with an empty project name, listing the notices of
  bundled dependencies and none for Faultora itself. The shade transformer now
  names the project and the copyright holder, and a root `NOTICE` ships in both
  the plain and the shaded artifact as `META-INF/NOTICE-faultora.txt`.

### Added

- `NOTICE` in the repository root, and a copyright line in the README. The
  project previously asserted ownership nowhere, which is friction for any
  organisation whose legal review looks for an identifiable holder before
  deploying self-hosted software.

## 0.5.1 — 2026-07-28

Corrections to request generation, found while reviewing 0.5.0.

### Fixed

- Numbers declared in a range narrower than two decimals — rates, shares, FX
  factors — were generated outside that range every time: rounding used a
  fixed scale, so a value between 0.001 and 0.004 became 0.00. The scale now
  follows the width of the range, and a rounded value that would leave the
  range falls back to its minimum.
- Properties marked `readOnly: true` are no longer generated into request
  bodies. They are server-managed, and sending one back is at best ignored
  and at worst rejected.
- The `invalid` strategy introduces its violation after the step's explicit
  `inputs` are applied, and avoids the fields they pin. Previously a pinned
  field could restore the broken constraint, so a valid payload was sent while
  the journal reported a violation that never reached the target.

## 0.5.0 — 2026-07-27

Requests can be built from the contract instead of written out by hand.

### Added

- Generated request values: a `generate` block on an operation step builds the
  named inputs from the operation's schemas. Strategies are `valid` (default),
  `boundary` — the smallest accepted payload with constrained values on their
  limits — and `invalid`, which breaks exactly one constraint and names it in
  the report. Explicit `inputs` are applied over generated values, merging
  objects field by field, so a scenario can generate a payload and still pin
  what it asserts on.
- Generation is reproducible: values derive from the run seed and the step ID,
  so the same `--seed` replays the identical payload. A retry and an
  `eventually` poll resend the same payload — inputs are now resolved once per
  step rather than once per attempt — while `repeat` iterations each get their
  own.
- An `example` declared in a schema is sent verbatim only when it satisfies
  that schema; a stale example falls through to generation.
- A schema the generator cannot satisfy fails plan compilation naming the
  field, instead of sending a request the contract rejects. Supported
  constraints are documented; `pattern` is deliberately not among them.
- `INPUTS_GENERATED` journal events record the seed, schema, strategy, and a
  digest of each generated value. The payload itself is request data and stays
  out of the journal. Console and HTML reports name the strategy per step.
- New module `faultora-schema` with the generator and a matching
  `SchemaValidator`, which is what proves generated payloads satisfy their
  source schema rather than the generator vouching for itself.
- Reference scenario `generated-payment.yaml`, and constraints on the example
  API's request schema so generation has something real to satisfy.

### Fixed

- The OpenAPI importer registers inline schemas — request bodies, parameters,
  and responses written without a `$ref` — under a synthetic ID instead of
  discarding them. An operation whose body was declared inline previously
  looked as if it took no structured input at all.

## 0.4.0 — 2026-07-27

Scenarios can now express iteration, convergence, and time limits: the last
control-flow gaps of the reliability engine. The engine and the reports were
restructured along the way, with no change to what a scenario does.

### Added

- Eventually (poll-until) groups: `type: eventually` polls one operation every
  `interval` until every `until` condition holds in the same poll, or the
  `timeout` budget is spent. A failed request is an unsatisfied poll rather
  than a failure, condition outcomes count towards the run's assertion totals,
  the final poll's evidence is bound to the polled step, and each poll is
  recorded as a `CONDITION_POLLED` journal event. The poll count is
  `1 + timeout / interval`, so the whole block is budgeted before the first
  request; a combination needing more than 100 polls is rejected during
  compilation instead of being silently capped.
- Repeat groups: `type: repeat` runs its child steps once per iteration, over
  a fixed `count` or a literal `forEach` list, binding `{{repeat.index}}` and
  `{{repeat.item}}`. Iterations are recorded under `<step-id>:<index>`, the
  plain step ID resolves to the last completed iteration, and the group stops
  at the first failing iteration.
- Scenario deadline: the top-level `timeout` field bounds the whole run. Once
  it elapses no further step starts, cleanup still runs, and the run fails
  with `SCENARIO_DEADLINE_EXCEEDED`.
- Reference scenarios `eventually-settlement.yaml` and `repeat-batch.yaml`.
  The example payment API now settles a payment asynchronously — reads report
  `pending` until the settlement delay has passed and `settled` afterwards —
  so the eventually scenario converges on real asynchronous state.
- Console and HTML reports show the poll count of an eventually group and
  every assertion of a node, not only the last one.

### Fixed

- Targets are resolved through the catalog instead of being fabricated from
  the CLI's base URL. A target's name, protocols, authentication schemes, and
  metadata now reach the connector; `--target <id>=<url>` binds one catalog
  target and plain `--target <url>` binds them all; an operation whose target
  is neither declared nor bound fails with `TARGET_NOT_FOUND` instead of being
  sent to a synthesized endpoint. ADR-012 records the decision.
- A failed assertion is journalled as `NODE_FAILED` with an `ASSERTION_FAILED`
  error. It previously emitted `NODE_COMPLETED` while returning a failed
  status, so the event stream disagreed with the run result and reports had to
  reconstruct the verdict from the assertion event.
- A `timeout` on a parallel group is now enforced instead of being parsed and
  discarded: children still running when it elapses are cancelled and reported
  as `DEADLINE_EXCEEDED`.
- Cleanup obligations are collected before execution starts, so a scenario
  deadline or cancellation can no longer skip a cleanup step that appears
  after the interrupted node.
- JUnit XML now attributes a failed assertion to its own node regardless of
  event order, instead of only to the most recently completed node.
- `dependsOn` pointing at a step that runs inside a group now waits for the
  group. Previously the dependent step was silently skipped, because the child
  is not a node the engine schedules on its own.

### Changed

- `TestCommand` keeps only the composition root. Argument syntax moved to
  `TestOptions`, run bounds to `RunPolicies`, catalog loading to
  `CatalogLoader`, and report rendering to `ReportWriter`.
- The execution engine is split by responsibility: `LocalEngine` now only
  schedules, and each node kind — operation, wait, assertion, fault, and the
  three group kinds — is executed by its own class in
  `dev.faultora.engine.exec`. Run events are emitted through one writer
  instead of being assembled at every call site.
- Report renderers share one projection of the event stream (`RunSummary`)
  instead of folding events three times. A new event type is now understood by
  console, HTML, and JUnit output at once.
- Assertion providers, report renderers, and source importers are discovered
  through `ServiceLoader`, as ADR-004 always specified; the service files
  existed but nothing loaded them. Connectors and fault providers stay
  explicitly constructed, because both carry the run-scoped policy that bounds
  what a run may reach or break. ADR-004 records the split.
- Wait steps compile to a dedicated `WaitNode` instead of an operation node
  with a synthetic `_wait` operation ID. Journal entries for wait steps now
  carry `"nodeType":"wait"` and no operation ID; reports are unchanged.
- The duration grammar, the scenario limits, and the SHA-256 content digest
  each have a single definition (`DurationSyntax`, `ScenarioLimits`,
  `ContentDigest`) shared by validation, compilation, and reporting.
- Plan nodes only carry the fields they can honour: deadlines and retry counts
  are no longer required of assertion, fault, and wait nodes.
- The end-to-end suite runs the published `examples/payment-service` scenarios
  and OpenAPI document directly. The duplicate copies under test resources had
  already drifted, so CI was proving something users never run.
- `retry`, `expectError`, `outputAs`, `inputs`, and `operationId` on a
  grouping step (`parallel`, `repeat`, `eventually`) are rejected during
  validation. They were previously accepted and silently ignored; they belong
  on the group's child steps.
- An assertion that targets a grouping step — explicitly, or by omitting
  `targetStep` when the last `execute` step is a group — is now a compilation
  error naming the problem. A group holds no evidence of its own, so the
  assertion previously failed at run time as unevaluatable.

## 0.3.1 — 2026-07-24

Compliance release; no functional changes.

### Added

- `THIRD-PARTY.txt` with copyright notices and license texts for every
  component bundled in the executable JAR (Apache-2.0, MIT, BSD-3-Clause,
  EPL-1.0), shipped in the repository root and inside the JAR as
  `META-INF/THIRD-PARTY.txt`.

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
